package app.obdforge.core.obd

import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class Priority(val level: Int) {
    /** User actions: read codes, clear codes, connect. */
    HIGH(0),
    /** Live data polling. */
    NORMAL(1),
    /** Background refreshes such as adapter voltage. */
    LOW(2),
}

/**
 * The single thread that owns the adapter. Every request goes through here, highest priority
 * first, so a user tapping "Read codes" jumps ahead of live polling and two requests can never
 * be on the wire at once (which corrupts ELM327 conversations).
 */
class CommandExecutor(name: String = "obd-command") {
    private val seq = AtomicLong()

    private inner class Task<T>(val priority: Priority, callable: Callable<T>) : FutureTask<T>(callable), Comparable<Task<*>> {
        val order = seq.incrementAndGet()
        override fun compareTo(other: Task<*>): Int =
            if (priority.level != other.priority.level) priority.level - other.priority.level else order.compareTo(other.order)
    }

    @Suppress("UNCHECKED_CAST")
    private val pool = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, PriorityBlockingQueue<Runnable>() as java.util.concurrent.BlockingQueue<Runnable>) { r ->
        Thread(r, name).apply { isDaemon = true }
    }

    fun <T> submit(priority: Priority, block: () -> T): Future<T> {
        val t = Task(priority, Callable { block() })
        pool.execute(t)
        return t
    }

    /** Number of requests waiting (used to back off polling when the user is busy). */
    val queued: Int get() = pool.queue.size

    fun clearPending() {
        val pending = ArrayList<Runnable>()
        pool.queue.drainTo(pending)
        pending.forEach { (it as? FutureTask<*>)?.cancel(false) }
    }

    fun shutdown() { pool.shutdownNow() }
}
