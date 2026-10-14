package dev.nglmercer.tiktools.data.events

import dev.nglmercer.tiktools.core.model.LiveEvent
import dev.nglmercer.tiktools.data.rewards.PointsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class LiveStats(
    val events: Long = 0,
    val gifts: Long = 0,
    val likes: Long = 0,
    val followers: Long = 0,
    val viewers: Long = 0,
    val points: Double = 0.0,
)

class EventsRepository(
    private val scope: CoroutineScope? = null,
    private val updateIntervalMs: Long = 50,
) {
    init { require(updateIntervalMs > 0) }
    private var publication: Job? = null
    private var publicationGeneration = 0L
    private var totals = LiveStats()
    private var historyDirty = false

    // Business processing remains per event; only observable UI snapshots are batched.
    private fun publishLocked() {
        _stats.value = totals
        if (historyDirty) { _history.value = deque.toList(); historyDirty = false }
    }
    private fun schedulePublication() {
        if (scope == null) { publishLocked(); return }
        if (publication?.isActive == true) return
        val generation = publicationGeneration
        publication = scope.launch {
            delay(updateIntervalMs)
            synchronized(this@EventsRepository) {
                if (generation != publicationGeneration) return@synchronized
                publishLocked()
                publication = null
            }
        }
    }

    private val deque = ArrayDeque<LiveEvent>()
    private val _history = MutableStateFlow<List<LiveEvent>>(emptyList())
    val history = _history.asStateFlow()
    private val _stats = MutableStateFlow(LiveStats())
    val stats = _stats.asStateFlow()
    private val _paused = MutableStateFlow(false)
    val paused = _paused.asStateFlow()
    private val _lastAward = MutableStateFlow<PointsRepository.Award?>(null)
    val lastAward = _lastAward.asStateFlow()

    @Synchronized
    fun append(event: LiveEvent) {
        val old = totals
        totals =
            old.copy(
                events = old.events + 1,
                gifts = old.gifts + if (event.category == LiveEvent.Category.GIFT) 1 else 0,
                likes =
                    old.likes +
                        if (event.category == LiveEvent.Category.LIKE) event.count.coerceAtLeast(0)
                        else 0,
                followers =
                    old.followers + if (event.category == LiveEvent.Category.FOLLOW) 1 else 0,
                viewers =
                    if (event.category == LiveEvent.Category.ROOM) event.count.coerceAtLeast(0)
                    else old.viewers,
            )
        if (_paused.value) { schedulePublication(); return }
        deque.addFirst(event)
        while (deque.size > 300) deque.removeLast()
        historyDirty = true
        schedulePublication()
    }

    @Synchronized
    fun awarded(award: PointsRepository.Award) {
        _lastAward.value = award
        totals = totals.copy(points = totals.points + award.delta)
        schedulePublication()
    }

    fun setPaused(value: Boolean) {
        _paused.value = value
    }

    @Synchronized
    fun clearHistory() {
        deque.clear()
        historyDirty = false
        _history.value = emptyList()
    }

    @Synchronized
    fun beginSession() {
        clearHistory()
        publicationGeneration++
        publication?.cancel()
        publication = null
        totals = LiveStats()
        _stats.value = totals
        _lastAward.value = null
    }
}
