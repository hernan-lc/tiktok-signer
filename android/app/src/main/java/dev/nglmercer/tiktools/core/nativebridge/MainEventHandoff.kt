package dev.nglmercer.tiktools.core.nativebridge

import android.os.Handler

/** One scheduled main-thread task, a bounded FIFO, and terminal overload instead of silent loss. */
internal class MainEventHandoff(
    private val handler: Handler,
    private val capacity: Int = 1024,
    private val maxBytes: Long = 8 * 1024 * 1024,
    private val batchSize: Int = 64,
    private val intervalMs: Long = 16,
    onEvent: (String) -> Unit,
    onState: (String, String) -> Unit,
    onOverflow: () -> Unit,
) {
    init {
        require(capacity in 1..16384 && maxBytes > 0 && batchSize > 0 && intervalMs >= 0)
    }
    private sealed interface Notice {
        val bytes: Long
        data class Event(val json: String) : Notice { override val bytes = json.length.toLong() * 2 + 64 }
        data class State(val kind: String, val detail: String) : Notice { override val bytes = (kind.length.toLong() + detail.length) * 2 + 64 }
        data object Overflow : Notice { override val bytes = 0L }
    }
    private val lock = Any()
    private val queue = ArrayDeque<Notice>()
    private var queuedBytes = 0L
    private var scheduled = false
    private var stopped = false
    private var eventConsumer: ((String) -> Unit)? = onEvent
    private var stateConsumer: ((String, String) -> Unit)? = onState
    private var overflowConsumer: (() -> Unit)? = onOverflow
    @Volatile var callbackFailures: Long = 0
        private set
    @Volatile var overflows: Long = 0
        private set
    @Volatile var discardedEvents: Long = 0
        private set
    val depth: Int get() = synchronized(lock) { queue.size }
    val bytes: Long get() = synchronized(lock) { queuedBytes }

    fun event(json: String) = offer(Notice.Event(json))
    fun state(kind: String, detail: String) = offer(Notice.State(kind, detail))

    private fun offer(notice: Notice) {
        var overflow: (() -> Unit)? = null
        synchronized(lock) {
            if (stopped) return
            if (queue.size >= capacity || notice.bytes > maxBytes - queuedBytes) {
                discardedEvents += queue.count { it is Notice.Event } + if (notice is Notice.Event) 1 else 0
                queue.clear()
                queuedBytes = 0
                stopped = true
                overflows++
                queue.addLast(Notice.Overflow)
                overflow = overflowConsumer
            } else {
                queue.addLast(notice)
                queuedBytes += notice.bytes
            }
            if (!scheduled) {
                scheduled = true
                handler.post(pump)
            }
        }
        // Caller is the native worker: stop asynchronously, without blocking reception or UI.
        overflow?.invoke()
    }

    private val pump = object : Runnable {
    override fun run() {
        repeat(batchSize) {
            val notice = synchronized(lock) {
                if (queue.isEmpty()) null else queue.removeFirst().also { queuedBytes -= it.bytes }
            } ?: return@repeat
            try {
                when (notice) {
                    is Notice.Event -> eventConsumer?.invoke(notice.json)
                    is Notice.State -> stateConsumer?.invoke(notice.kind, notice.detail)
                    Notice.Overflow -> stateConsumer?.invoke("error", "Event delivery queue overflow; LIVE connection stopped")
                }
            } catch (_: Exception) {
                val stop = synchronized(lock) {
                    discardedEvents += queue.count { it is Notice.Event }
                    if (notice is Notice.Event) discardedEvents++
                    callbackFailures++
                    overflowConsumer
                }
                stop?.invoke()
                try { stateConsumer?.invoke("error", "Event listener failed; LIVE connection stopped") } catch (_: Exception) { }
                close()
            }
        }
        synchronized(lock) {
            scheduled = false
            if (queue.isNotEmpty()) {
                scheduled = true
                handler.postDelayed(this, intervalMs)
            }
        }
    }

    }

    /** Run on main before awaiting native disconnect; releases queued events and listener references. */
    fun close() {
        synchronized(lock) {
            stopped = true
            queue.clear()
            queuedBytes = 0
            handler.removeCallbacks(pump)
            scheduled = false
            eventConsumer = null
            stateConsumer = null
            overflowConsumer = null
        }
    }
}
