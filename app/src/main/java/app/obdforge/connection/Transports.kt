package app.obdforge.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.Network
import android.os.Build
import app.obdforge.core.transport.ConnectionKind
import app.obdforge.core.transport.QueuedTransport
import app.obdforge.core.transport.TransportException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** Starts a daemon thread that copies an InputStream into the transport queue. */
private fun startReader(name: String, input: InputStream, onData: (ByteArray) -> Unit, onError: (Throwable) -> Unit): Thread =
    Thread({
        val buf = ByteArray(1024)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n < 0) throw TransportException("Stream closed by adapter")
                if (n > 0) onData(buf.copyOf(n))
            }
        } catch (t: Throwable) {
            onError(t)
        }
    }, name).apply { isDaemon = true; start() }

/**
 * Bluetooth Classic (SPP/RFCOMM): the most common ELM327 adapters. Tries the secure socket,
 * then the insecure one, then the channel-1 fallback that many cheap adapters need.
 */
@SuppressLint("MissingPermission")
class BluetoothClassicTransport(private val device: BluetoothDevice, private val adapter: BluetoothAdapter?) : QueuedTransport() {
    override val kind = ConnectionKind.BLUETOOTH_CLASSIC
    override val description: String = "${device.name ?: "Bluetooth"} (${device.address})"
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    private var reader: Thread? = null

    override fun open() {
        resetQueue()
        runCatching { adapter?.cancelDiscovery() } // discovery slows RFCOMM connects a lot
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            { device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket },
        )
        var last: Throwable? = null
        for (make in attempts) {
            val s = try { make() } catch (t: Throwable) { last = t; continue }
            try {
                s.connect()
                socket = s
                out = s.outputStream
                reader = startReader("bt-reader", s.inputStream, { deliver(it) }) { fail(it) }
                opened = true
                return
            } catch (t: Throwable) {
                last = t
                runCatching { s.close() }
            }
        }
        throw TransportException("Could not open Bluetooth connection to ${device.address}: ${last?.message}", last)
    }

    override fun write(data: ByteArray) {
        try { out?.write(data); out?.flush() ?: throw TransportException("Not connected") }
        catch (e: java.io.IOException) { fail(e); throw TransportException("Bluetooth write failed: ${e.message}", e) }
    }

    override fun close() {
        opened = false
        reader?.interrupt()
        runCatching { socket?.close() }
        socket = null
    }
}

/**
 * Bluetooth Low Energy adapters expose a serial-like GATT service. Vendors use different UUIDs,
 * so known ones are tried first and otherwise any service that has both a notify and a write
 * characteristic is used.
 */
@SuppressLint("MissingPermission")
class BleTransport(private val context: Context, private val device: BluetoothDevice) : QueuedTransport() {
    override val kind = ConnectionKind.BLUETOOTH_LE
    override val description: String = "${device.name ?: "BLE adapter"} (${device.address})"

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    @Volatile private var rssi: Int? = null
    private var connectLatch = CountDownLatch(1)
    private var servicesLatch = CountDownLatch(1)
    private var writeLatch = CountDownLatch(1)
    private var descLatch = CountDownLatch(1)
    private var mtuLatch = CountDownLatch(1)
    @Volatile private var connectError: String? = null

    companion object {
        // (service, notify, write) triples seen on common BLE OBD adapters.
        val KNOWN = listOf(
            Triple("0000fff0-0000-1000-8000-00805f9b34fb", "0000fff1-0000-1000-8000-00805f9b34fb", "0000fff2-0000-1000-8000-00805f9b34fb"),
            Triple("0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb"),
            Triple("000018f0-0000-1000-8000-00805f9b34fb", "00002af0-0000-1000-8000-00805f9b34fb", "00002af1-0000-1000-8000-00805f9b34fb"),
            Triple("e7810a71-73ae-499d-8c15-faa9aef0c3f2", "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f", "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
        )
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectLatch.countDown()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectError = "GATT disconnected (status $status)"
                connectLatch.countDown(); servicesLatch.countDown()
                if (opened) fail(TransportException("BLE link lost (status $status)"))
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { servicesLatch.countDown() }
        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) { if (status == BluetoothGatt.GATT_SUCCESS) mtu = m; mtuLatch.countDown() }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { writeLatch.countDown() }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { descLatch.countDown() }
        override fun onReadRemoteRssi(g: BluetoothGatt, r: Int, status: Int) { if (status == BluetoothGatt.GATT_SUCCESS) rssi = r }
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") c.value?.let { deliver(it.copyOf()) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { deliver(value.copyOf()) }
    }

    override fun open() {
        resetQueue()
        connectLatch = CountDownLatch(1); servicesLatch = CountDownLatch(1); connectError = null
        val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, callback)
        gatt = g
        if (!connectLatch.await(12, TimeUnit.SECONDS) || connectError != null) { close(); throw TransportException(connectError ?: "BLE connect timeout") }
        mtuLatch = CountDownLatch(1)
        if (g.requestMtu(185)) mtuLatch.await(3, TimeUnit.SECONDS)
        if (!g.discoverServices() || !servicesLatch.await(10, TimeUnit.SECONDS)) { close(); throw TransportException("BLE service discovery failed") }
        val (notify, write) = pickCharacteristics(g) ?: run { close(); throw TransportException("No serial-style GATT service found on this device") }
        g.setCharacteristicNotification(notify, true)
        notify.getDescriptor(CCCD)?.let { d ->
            descLatch = CountDownLatch(1)
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) else {
                @Suppress("DEPRECATION") d.value = value
                @Suppress("DEPRECATION") g.writeDescriptor(d)
            }
            descLatch.await(3, TimeUnit.SECONDS)
        }
        writeChar = write
        opened = true
    }

    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for ((s, n, w) in KNOWN) {
            val svc = g.getService(UUID.fromString(s)) ?: continue
            val nc = svc.getCharacteristic(UUID.fromString(n)) ?: continue
            val wc = svc.getCharacteristic(UUID.fromString(w)) ?: continue
            return nc to wc
        }
        for (svc in g.services) {
            val nc = svc.characteristics.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 } ?: continue
            val wc = svc.characteristics.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 } ?: continue
            return nc to wc
        }
        return null
    }

    override fun write(data: ByteArray) {
        val g = gatt ?: throw TransportException("BLE not connected")
        val c = writeChar ?: throw TransportException("BLE not connected")
        val chunk = (mtu - 3).coerceAtLeast(20)
        val noResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var i = 0
        while (i < data.size) {
            val part = data.copyOfRange(i, minOf(data.size, i + chunk))
            writeLatch = CountDownLatch(1)
            val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, part, type) == BluetoothGatt.GATT_SUCCESS else {
                @Suppress("DEPRECATION") c.writeType = type
                @Suppress("DEPRECATION") c.value = part
                @Suppress("DEPRECATION") g.writeCharacteristic(c)
            }
            if (!ok) throw TransportException("BLE write rejected")
            writeLatch.await(if (noResponse) 50 else 1000, TimeUnit.MILLISECONDS)
            i += chunk
        }
        runCatching { g.readRemoteRssi() }
    }

    override fun signalQuality(): Int? = rssi?.let { ((it + 100) * 2).coerceIn(0, 100) }

    override fun close() {
        opened = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }
}

/**
 * Wi-Fi ELM327 adapters (usually 192.168.0.10:35000) create their own network without internet.
 * When a [network] is given (from the Wi-Fi connector) the socket is bound to it, otherwise Android
 * may try to route the connection over mobile data.
 */
class WifiTransport(private val host: String, private val port: Int, private val network: Network? = null) : QueuedTransport() {
    override val kind = ConnectionKind.WIFI
    override val description = "$host:$port"
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var reader: Thread? = null

    override fun open() {
        resetQueue()
        try {
            val s = network?.socketFactory?.createSocket() ?: Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 6000)
            socket = s
            out = s.getOutputStream()
            reader = startReader("wifi-reader", s.getInputStream(), { deliver(it) }) { fail(it) }
            opened = true
        } catch (e: Exception) {
            throw TransportException("Could not reach $host:$port (${e.message}). Is the phone joined to the adapter's Wi-Fi?", e)
        }
    }

    override fun write(data: ByteArray) {
        try { out?.write(data); out?.flush() ?: throw TransportException("Not connected") }
        catch (e: java.io.IOException) { fail(e); throw TransportException("Wi-Fi write failed: ${e.message}", e) }
    }

    override fun close() {
        opened = false
        reader?.interrupt()
        runCatching { socket?.close() }
        socket = null
    }
}
