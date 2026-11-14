package app.obdforge.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class FoundDevice(
    val address: String,
    val name: String,
    val ble: Boolean,
    val bonded: Boolean,
    val rssi: Int?,
    val likelyObd: Boolean,
)

data class FoundWifi(val ssid: String, val level: Int, val likelyObd: Boolean)

private val OBD_NAME = Regex("(?i)(obd|elm|vlink|v-link|vgate|icar|obdlink|konnwei|kiwi|veepeak|carista|bafx|scan|ediag|lelink|wifi_obd|torque|kw9|viecar)")

/** Discovers Bluetooth Classic, BLE and Wi-Fi adapters. Caller must hold the runtime permissions. */
@SuppressLint("MissingPermission")
class AdapterScanner(private val context: Context) {
    private val btManager = context.getSystemService(BluetoothManager::class.java)
    val bluetooth: BluetoothAdapter? get() = btManager?.adapter

    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices
    private val _wifi = MutableStateFlow<List<FoundWifi>>(emptyList())
    val wifi: StateFlow<List<FoundWifi>> = _wifi
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val found = LinkedHashMap<String, FoundDevice>()
    private var receiverRegistered = false

    private fun publish() { _devices.value = found.values.sortedWith(compareByDescending<FoundDevice> { it.likelyObd }.thenByDescending { it.bonded }.thenByDescending { it.rssi ?: -200 }) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
                    val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                    if (d.type == BluetoothDevice.DEVICE_TYPE_LE) return
                    val name = runCatching { d.name }.getOrNull() ?: d.address
                    found[d.address + "c"] = FoundDevice(d.address, name, false, d.bondState == BluetoothDevice.BOND_BONDED, rssi, OBD_NAME.containsMatchIn(name))
                    publish()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scanning.value = false
            }
        }
    }

    private val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = runCatching { d.name }.getOrNull() ?: result.scanRecord?.deviceName ?: return
            found[d.address + "b"] = FoundDevice(d.address, name, true, d.bondState == BluetoothDevice.BOND_BONDED, result.rssi, OBD_NAME.containsMatchIn(name))
            publish()
        }
    }

    fun bonded(): List<FoundDevice> = runCatching {
        bluetooth?.bondedDevices?.map { d ->
            val name = d.name ?: d.address
            FoundDevice(d.address, name, d.type == BluetoothDevice.DEVICE_TYPE_LE, true, null, OBD_NAME.containsMatchIn(name))
        } ?: emptyList()
    }.getOrDefault(emptyList())

    fun startScan(classic: Boolean, ble: Boolean) {
        val bt = bluetooth ?: return
        found.clear()
        bonded().forEach { found[it.address + if (it.ble) "b" else "c"] = it }
        publish()
        _scanning.value = true
        if (classic) {
            if (!receiverRegistered) {
                val f = IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else context.registerReceiver(receiver, f)
                receiverRegistered = true
            }
            runCatching { bt.cancelDiscovery(); bt.startDiscovery() }
        }
        if (ble) runCatching {
            bt.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), leCallback)
        }
    }

    fun stopScan() {
        _scanning.value = false
        runCatching { bluetooth?.cancelDiscovery() }
        runCatching { bluetooth?.bluetoothLeScanner?.stopScan(leCallback) }
        if (receiverRegistered) { runCatching { context.unregisterReceiver(receiver) }; receiverRegistered = false }
    }

    fun device(address: String): BluetoothDevice? = runCatching { bluetooth?.getRemoteDevice(address) }.getOrNull()

    @Suppress("DEPRECATION")
    fun scanWifi() {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        runCatching { wm.startScan() }
        val list = runCatching { wm.scanResults }.getOrDefault(emptyList())
        _wifi.value = list.mapNotNull { r ->
            val ssid = if (Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString()?.trim('"') else r.SSID
            if (ssid.isNullOrBlank()) null else FoundWifi(ssid, r.level, OBD_NAME.containsMatchIn(ssid))
        }.distinctBy { it.ssid }.sortedWith(compareByDescending<FoundWifi> { it.likelyObd }.thenByDescending { it.level })
    }

    fun signalBars(rssi: Int?): Int = when {
        rssi == null -> -1
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
}

/**
 * Joins an adapter's Wi-Fi network for this app only (Android 10+), so the phone keeps mobile data
 * for everything else and our socket is routed to the adapter.
 */
class WifiNetworkBinder(private val context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    fun request(ssid: String, password: String?) {
        release()
        if (Build.VERSION.SDK_INT < 29) { _status.value = "Join \"$ssid\" in Android Wi-Fi settings, then connect."; return }
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply { if (!password.isNullOrEmpty()) setWpa2Passphrase(password) }.build()
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { _network.value = n; _status.value = "Joined $ssid" }
            override fun onUnavailable() { _status.value = "Could not join $ssid" }
            override fun onLost(n: Network) { _network.value = null; _status.value = "Lost $ssid" }
        }
        callback = cb
        _status.value = "Requesting $ssid…"
        runCatching { cm.requestNetwork(req, cb) }.onFailure { _status.value = "Wi-Fi request failed: ${it.message}" }
    }

    /** Finds a currently connected Wi-Fi network (when the user joined the adapter in settings). */
    fun currentWifi(): Network? = runCatching {
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrNull()

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        _network.value = null
    }
}
