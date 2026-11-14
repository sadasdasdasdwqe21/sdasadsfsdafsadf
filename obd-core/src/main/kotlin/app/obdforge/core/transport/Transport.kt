package app.obdforge.core.transport

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

enum class ConnectionKind(val label: String) {
    BLUETOOTH_CLASSIC("Bluetooth Classic"),
    BLUETOOTH_LE("Bluetooth LE"),
    WIFI("Wi-Fi"),
    SIMULATOR("Simulator"),
}

/** Thrown when the link to the adapter itself is gone (not when the car simply did not answer). */
class TransportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A byte pipe to an OBD adapter. Implementations exist for Bluetooth Classic (SPP), BLE (GATT
 * serial service), Wi-Fi (TCP) and the built-in simulator. All calls are blocking and are only
 * ever made from the single command thread, so implementations need no extra locking for I/O.
 */
interface ObdTransport {
    val kind: ConnectionKind
    val description: String
    val isOpen: Boolean

    @Throws(TransportException::class)
    fun open()

    @Throws(TransportException::class)
    fun write(data: ByteArray)

    /**
     * Reads available bytes, waiting up to [timeoutMs] for the first byte.
     * Returns an empty array on timeout. Throws [TransportException] if the link is lost.
     */
    @Throws(TransportException::class)
    fun read(timeoutMs: Long): ByteArray

    /** Drops anything waiting in the receive buffer. */
    fun drain()

    fun close()

    /** Link quality 0..100 if the transport can measure it (BLE RSSI, Wi-Fi RSSI), else null. */
    fun signalQuality(): Int? = null
}

/**
 * Base class for transports whose incoming data arrives on another thread (a socket reader
 * thread, BLE notifications, the simulator). Incoming chunks are queued and [read] waits on them.
 */
abstract class QueuedTransport : ObdTransport {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var failure: Throwable? = null
    @Volatile protected var opened = false

    override val isOpen: Boolean get() = opened && failure == null

    /** Called by subclasses when bytes arrive. */
    protected fun deliver(bytes: ByteArray) {
        if (bytes.isNotEmpty()) queue.offer(bytes)
    }

    /** Called by subclasses when the link fails; wakes up any waiting reader. */
    protected fun fail(t: Throwable) {
        failure = t
        queue.offer(ByteArray(0))
    }

    protected fun resetQueue() {
        queue.clear()
        failure = null
    }

    override fun read(timeoutMs: Long): ByteArray {
        failure?.let { throw TransportException("Link lost: ${it.message}", it) }
        val first = queue.poll(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: return ByteArray(0)
        failure?.let { throw TransportException("Link lost: ${it.message}", it) }
        if (queue.isEmpty()) return first
        // Merge whatever else is already waiting so callers see bigger chunks.
        val out = java.io.ByteArrayOutputStream()
        out.write(first)
        while (true) {
            val more = queue.poll() ?: break
            out.write(more)
        }
        return out.toByteArray()
    }

    override fun drain() {
        queue.clear()
    }
}
