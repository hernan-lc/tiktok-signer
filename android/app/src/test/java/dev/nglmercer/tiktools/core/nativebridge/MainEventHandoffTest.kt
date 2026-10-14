package dev.nglmercer.tiktools.core.nativebridge

import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainEventHandoffTest {
    @Test
    fun offlineHandoffSchedulingBenchmark() {
        val handler = Handler(Looper.getMainLooper())
        for (batched in listOf(false, true)) {
            var delivered = 0
            val handoff = MainEventHandoff(handler, capacity = 4096,
                onEvent = { delivered++ }, onState = { _, _ -> }, onOverflow = { fail("overflow") })
            val samples = LongArray(1000)
            val started = System.nanoTime()
            repeat(1000) { index ->
                val before = System.nanoTime()
                if (batched) handoff.event("$index") else handler.post { delivered++ }
                samples[index] = System.nanoTime() - before
            }
            val enqueue = System.nanoTime() - started
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1000, delivered)
            samples.sort()
            println("handoff_batched=$batched events=1000 enqueue_total_ns=$enqueue enqueue_ns_p50=${samples[500]} enqueue_ns_p95=${samples[950]} enqueue_ns_p99=${samples[990]} jvm_used_bytes=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}")
            handoff.close()
        }
    }

    @Test
    fun thousandEventBurstIsOrderedAndDrainedInBatches() {
        val events = mutableListOf<String>()
        val handoff = MainEventHandoff(Handler(Looper.getMainLooper()),
            onEvent = { events.add(it) }, onState = { _, _ -> }, onOverflow = { fail("unexpected overflow") })
        repeat(1000) { handoff.event("$it") }
        assertEquals(1000, handoff.depth)
        assertTrue(events.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals((0..999).map { "$it" }, events)
        assertEquals(0, handoff.depth)
        assertEquals(0L, handoff.bytes)
        handoff.close()
    }

    @Test
    fun overflowIsTerminalExplicitAndRetainsOnlyOneNotification() {
        val events = mutableListOf<String>()
        val states = mutableListOf<String>()
        var stopped = 0
        val handoff = MainEventHandoff(Handler(Looper.getMainLooper()), capacity = 4,
            onEvent = { events.add(it) }, onState = { kind, _ -> states.add(kind) }, onOverflow = { stopped++ })
        repeat(1000) { handoff.event("$it") }
        assertEquals(1, stopped)
        assertEquals(1L, handoff.overflows)
        assertEquals(5L, handoff.discardedEvents)
        assertEquals(1, handoff.depth)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(events.isEmpty())
        assertEquals(listOf("error"), states)
        handoff.close()
    }

    @Test
    fun byteLimitAndCloseReleasePendingEventsWithoutFurtherCallbacks() {
        var events = 0
        var states = 0
        var overflows = 0
        val handoff = MainEventHandoff(Handler(Looper.getMainLooper()), maxBytes = 128,
            onEvent = { events++ }, onState = { _, _ -> states++ }, onOverflow = { overflows++ })
        handoff.event("x".repeat(128))
        assertEquals(1, overflows)
        handoff.close()
        handoff.event("after disconnect")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(0, events)
        assertEquals(0, states)
        assertEquals(0, handoff.depth)
        assertEquals(0L, handoff.bytes)
    }

    @Test
    fun listenerFailureStopsDeliveryAndReleasesTheRemainingQueue() {
        var stopped = 0
        var errors = 0
        val handoff = MainEventHandoff(Handler(Looper.getMainLooper()),
            onEvent = { throw IllegalStateException("synthetic listener failure") },
            onState = { kind, _ -> if (kind == "error") errors++ },
            onOverflow = { stopped++ })
        repeat(10) { handoff.event("$it") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, stopped)
        assertEquals(1, errors)
        assertEquals(1L, handoff.callbackFailures)
        assertEquals(0, handoff.depth)
        assertEquals(0L, handoff.bytes)
    }
}
