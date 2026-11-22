package io.nekohasekai.sfa.activity

import kotlinx.serialization.Serializable

/** Vertical depth of one activity lane. Order is top-to-bottom in the chart. */
@Serializable
enum class ActivityLane {
    SERVICE,
    NETWORK,
    TAILSCALE,
    TELEMETRY,
    SUBSCRIPTION,
    URLTEST,
    GROUP,
    VERSION,
}

/**
 * One locally recorded activity. High-frequency lanes (telemetry and URLTest)
 * are coalesced into five-minute buckets by [ActivityReducer] so the chart can
 * draw one mark per bucket without forcing points apart.
 */
@Serializable
data class ActivityEvent(
    val id: Long = 0,
    val ts: Long,
    val lastTs: Long = ts,
    val lane: ActivityLane,
    val type: String,
    val subject: String? = null,
    val detail: String? = null,
    val group: String? = null,
    val from: String? = null,
    val to: String? = null,
    val reason: String? = null,
    val ms: Int? = null,
    val available: Int? = null,
    val total: Int? = null,
    val ok: Boolean? = null,
    val changed: Boolean? = null,
    val obstacle: Boolean = false,
    val count: Int = 1,
) {
    fun merge(other: ActivityEvent): ActivityEvent = copy(
        lastTs = maxOf(lastTs, other.lastTs),
        ok = when {
            ok == false || other.ok == false -> false
            ok == null -> other.ok
            else -> true
        },
        changed = if (changed == true || other.changed == true) true else changed ?: other.changed,
        obstacle = obstacle || other.obstacle,
        ms = other.ms ?: ms,
        detail = other.detail ?: detail,
        available = other.available ?: available,
        total = other.total ?: total,
        reason = other.reason ?: reason,
        count = count + other.count,
    )
}

internal object ActivityReducer {
    const val MAX_EVENTS = 4_000
    const val RETENTION_MS = 48L * 60L * 60L * 1_000L
    const val COALESCE_MS = 5L * 60L * 1_000L
    private const val FUTURE_SKEW_MS = 5L * 60L * 1_000L

    fun add(events: MutableList<ActivityEvent>, event: ActivityEvent, now: Long) {
        prune(events, now)
        if (event.lane == ActivityLane.TELEMETRY || event.lane == ActivityLane.URLTEST) {
            val bucket = event.ts / COALESCE_MS
            val index =
                events.indexOfLast {
                    it.lane == event.lane &&
                        it.type == event.type &&
                        it.group == event.group &&
                        it.ts / COALESCE_MS == bucket
                }
            if (index >= 0) {
                events[index] = events[index].merge(event)
                return
            }
        }
        events.add(event)
        if (events.size > MAX_EVENTS) {
            events.subList(0, events.size - MAX_EVENTS).clear()
        }
    }

    fun prune(events: MutableList<ActivityEvent>, now: Long) {
        val floor = now - RETENTION_MS
        val ceiling = now + FUTURE_SKEW_MS
        events.removeAll { it.ts < floor || it.ts > ceiling }
        events.sortBy { it.ts }
    }

    fun window(events: List<ActivityEvent>, start: Long, end: Long): List<ActivityEvent> =
        events.filter { it.ts in start..end }.sortedBy { it.ts }
}
