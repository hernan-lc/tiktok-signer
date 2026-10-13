package dev.nglmercer.tiktools.core.nativebridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.ttlsigner.TtlEvents
import com.example.ttlsigner.TtlNative
import dev.nglmercer.tiktools.core.network.BundleFetch
import kotlinx.coroutines.*
import kotlinx.coroutines.withContext

/**
 * Live event stream through the reused Rust core: sign, open the socket, decode batches, push JSON.
 * Callbacks arrive on the Rust worker thread and are posted to the main thread before reaching
 * [Listener], so UI code can touch views directly.
 */
class LiveClient
private constructor(handle: Long, private val main: Handler, private val listener: Listener, delivery: DeliveryOptions = DeliveryOptions()) {
    private val handle = java.util.concurrent.atomic.AtomicLong(handle)
    @Volatile private var closed = false
    private val shutdownLock = Any()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var shutdownJob: Job? = null
    data class DeliveryOptions(
        val capacity: Int = 1024,
        val maxBytes: Long = 8 * 1024 * 1024,
        val batchSize: Int = 64,
        val intervalMs: Long = 16,
    ) {
        init { require(capacity in 1..16384 && maxBytes > 0 && batchSize > 0 && intervalMs >= 0) }
    }
    data class DeliveryMetrics(val depth: Int, val bytes: Long, val overflows: Long, val discardedEvents: Long, val callbackFailures: Long)
    fun deliveryMetrics() = DeliveryMetrics(handoff.depth, handoff.bytes, handoff.overflows, handoff.discardedEvents, handoff.callbackFailures)

    private val handoff = MainEventHandoff(main,
        capacity = delivery.capacity, maxBytes = delivery.maxBytes, batchSize = delivery.batchSize, intervalMs = delivery.intervalMs,
        onEvent = { if (!closed) listener.onEvent(it) },
        onState = { kind, detail -> listener.onState(kind, detail) },
        onOverflow = { requestShutdown() },
    )

    private fun requestShutdown(): Job = synchronized(shutdownLock) {
        closed = true
        shutdownJob ?: cleanupScope.launch {
            try {
                val live = handle.getAndSet(0L)
                if (live != 0L) TtlNative.nDisconnect(live)
            } finally { cleanupScope.cancel() }
        }.also { shutdownJob = it }
    }

    interface Listener {
        fun onEvent(json: String)

        fun onState(kind: String, detail: String)
    }

    private val callback =
        object : TtlEvents {
            override fun onEvent(json: String) {
                if (!closed) handoff.event(json)
            }

            override fun onState(kind: String, detail: String) {
                if (!closed) handoff.state(kind, detail)
            }
        }

    /** Stop the stream and wait for the worker. Blocks; call off the UI thread. */
    suspend fun disconnect() {
        val shutdown = requestShutdown()
        withContext(Dispatchers.Main.immediate) { handoff.close() }
        shutdown.join()
    }

    companion object {
        /**
         * Open [roomId]'s event stream. Downloads (or reuses the cached) bundle and signs every
         * (re)connect with it; [cookieHeader] is the guest session the handshake presents.
         */
        suspend fun connect(
            context: Context,
            roomId: String,
            cookieHeader: String,
            optionsJson: String = "{}",
            listener: Listener,
        ): LiveClient = connectWithDelivery(context, roomId, cookieHeader, optionsJson, DeliveryOptions(), listener)

        suspend fun connectWithDelivery(
            context: Context,
            roomId: String,
            cookieHeader: String,
            optionsJson: String,
            delivery: DeliveryOptions,
            listener: Listener,
        ): LiveClient =
            withContext(Dispatchers.IO) {
                val bundle = BundleFetch.load(context.cacheDir)
                val main = Handler(Looper.getMainLooper())
                // The client is built first so its callback (bound to the listener) can
                // be handed to the native open; the handle lands right after.
                val client = LiveClient(0L, main, listener, delivery)
                val handle =
                    TtlNative.nConnect(bundle, optionsJson, roomId, cookieHeader, client.callback)
                check(handle != 0L) { "the native live client failed to open" }
                client.setHandle(handle)
                client
            }
    }

    private fun setHandle(handle: Long) {
        this.handle.set(handle)
        if (closed) {
            val live = this.handle.getAndSet(0L)
            if (live != 0L) TtlNative.nDisconnect(live)
        }
    }
}
