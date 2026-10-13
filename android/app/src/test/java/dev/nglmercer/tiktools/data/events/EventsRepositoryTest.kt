package dev.nglmercer.tiktools.data.events

import dev.nglmercer.tiktools.core.model.LiveEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventsRepositoryTest {
    private fun event(index: Int) = LiveEvent(LiveEvent.Category.LIKE, "user", "User", "$index", 2, 0, at = index.toLong())

    @Test
    fun burstPublishesOnceAndKeepsEveryStatisticWithBoundedHistory() = runTest {
        val repository = EventsRepository(this, 50)
        repeat(1000) { repository.append(event(it)) }
        assertTrue(repository.history.value.isEmpty())
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(1000L, repository.stats.value.events)
        assertEquals(2000L, repository.stats.value.likes)
        assertEquals(300, repository.history.value.size)
        assertEquals("999", repository.history.value.first().text)
        assertEquals("700", repository.history.value.last().text)
        val snapshot = repository.history.value
        repository.append(event(1000))
        assertSame(snapshot, repository.history.value)
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals("1000", repository.history.value.first().text)
    }

    @Test
    fun pauseClearAndSessionResetCannotRepublishOldHistory() = runTest {
        val repository = EventsRepository(this, 50)
        repository.append(event(1))
        repository.setPaused(true)
        repository.append(event(2))
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(2L, repository.stats.value.events)
        assertEquals(1, repository.history.value.size)
        repository.setPaused(false)
        repository.append(event(3))
        repository.beginSession()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(repository.history.value.isEmpty())
        assertEquals(0L, repository.stats.value.events)
    }

    @Test
    fun legacyUnscopedRepositoryPublishesImmediately() {
        val repository = EventsRepository()
        repository.append(event(1))
        assertEquals(1L, repository.stats.value.events)
        assertEquals(1, repository.history.value.size)
    }
}
