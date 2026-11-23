package io.nekohasekai.sfa.activity

import kotlinx.serialization.Serializable

/** Vertical depth of one activity lane. Order is top-to-bottom in the chart. */
@Serializable
enum class ActivityLane {
    SERVICE,
    NETWORK,
    CONNECTION,
    TAILSCALE,
    POWER,
    TELEMETRY,
    SUBSCRIPTION,
    GROUP,
    VERSION,
    SYSTEM,
}

@Serializable
enum class ActivitySeverity {
    INFO,
    WARN,
    ERROR,
}

@Serializable
enum class ActivityEvidenceKind {
    CRASH,
    OOM,
}

@Serializable
data class ActivityEvidence(
    val kind: ActivityEvidenceKind,
    val id: String,
)

/** Wire names shared by the store, chart and detail sheet. */
object ActivityTypes {
    const val SERVICE_START = "service_start"
    const val SERVICE_STOP = "service_stop"
    const val SERVICE_ALERT = "service_alert"
    const val VPN_REVOKED = "vpn_revoked"
    const val SERVICE_RESTORE = "service_restore"
    const val CONFIG_RELOAD = "config_reload"

    const val NETWORK_CHANGE = "network_change"
    const val NETWORK_LOST = "network_lost"
    const val CONNECTION_ERROR = "connection_error"

    const val TAILSCALE_STATE = "tailscale_state"
    const val POWER_STATE = "power_state"

    const val TELEMETRY_UPLOAD = "telemetry_upload"
    const val PROFILE_UPDATE = "profile_update"
    const val PROFILE_SWITCH = "profile_switch"
    const val GROUP_SWITCH = "group_switch"
    const val GROUP_UNAVAILABLE = "group_unavailable"
    const val GROUP_STUCK = "group_stuck"
    const val APP_INSTALL = "app_install"
    const val APP_UPDATE = "app_update"

    const val CRASH = "crash"
    const val OOM = "oom"
    const val PROCESS_EXIT = "process_exit"
}

/** Names the power state a user would notice first. */
internal fun powerStateLabel(doze: Boolean, powerSave: Boolean, interactive: Boolean): String =
    when {
        doze -> "doze"
        !interactive -> "screen off"
        powerSave -> "power save"
        else -> "awake"
    }

/**
 * One locally recorded activity. High-frequency lanes (telemetry and URLTest)
 * are coalesced into five-minute buckets by [ActivityReducer] so the chart can
 * draw one mark per bucket without forcing points apart.
 */
@Serializable
data class ActivityEvent(    val id: Long = 0,
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
    val severity: ActivitySeverity = ActivitySeverity.INFO,
    val evidence: ActivityEvidence? = null,
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
        severity = if (other.severity.ordinal > severity.ordinal) other.severity else severity,
        evidence = other.evidence ?: evidence,
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
        if (isCoalescible(event)) {
            val bucket = event.ts / COALESCE_MS
            val index =
                events.indexOfLast {
                    isCoalescible(it) &&
                        it.lane == event.lane &&
                        it.type == event.type &&
                        coalesceKey(it) == coalesceKey(event) &&
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

    private fun isCoalescible(event: ActivityEvent): Boolean =
        event.lane == ActivityLane.TELEMETRY ||
            (event.lane == ActivityLane.CONNECTION && event.type == ActivityTypes.CONNECTION_ERROR)

    /** Connection errors batch by error class; other coalescible events by group. */
    private fun coalesceKey(event: ActivityEvent): String? =
        if (event.lane == ActivityLane.CONNECTION) event.reason else event.group
}
