package io.nekohasekai.sfa.activity

import android.content.Context
import io.nekohasekai.sfa.BuildConfig
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

/**
 * Local-only activity ring. It exists so the app can show what happened in the
 * last two days without reading (or retaining) telemetry upload queues.
 *
 * Disabled means no file is opened, no core log is parsed and no event is
 * written. The only cost on the hot paths is one preference check.
 */
object ActivityLog {
    private const val MAX_FILE_BYTES = 4L * 1024L * 1024L
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val lock = Any()
    private val writer =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "activity-log").apply { isDaemon = true }
        }

    private val events = ArrayList<ActivityEvent>()
    private var loaded = false
    private var file: File? = null
    private var nextId = 1L
    private var dirty = false
    private var persistScheduled = false
    @Volatile
    private var lastPowerKey: String? = null

    private fun ensureLoadedLocked(context: Context): File {
        file?.let { return it }
        val f = File(context.applicationContext.filesDir, "activity/events.jsonl")
        if (!loaded) {
            loaded = true
            runCatching {
                if (f.isFile && f.length() <= MAX_FILE_BYTES) {
                    f.useLines(Charsets.UTF_8) { lines ->
                        lines.forEach { line ->
                            runCatching { json.decodeFromString<ActivityEvent>(line) }
                                .getOrNull()
                                ?.let { events.add(normalizeEvent(it)) }
                        }
                    }
                }
            }
            ActivityReducer.prune(events, System.currentTimeMillis())
            nextId = (events.maxOfOrNull { it.id } ?: 0L) + 1L
        }
        file = f
        return f
    }

    fun record(context: Context, event: ActivityEvent): Boolean {
        if (!ActivityPreferences.isEnabled(context)) return false
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        synchronized(lock) {
            ensureLoadedLocked(app)
            val stamped =
                event.copy(
                    id = nextId++,
                    lastTs = if (event.lastTs == 0L) event.ts else event.lastTs,
                )
            ActivityReducer.add(events, stamped, now)
            dirty = true
            schedulePersistLocked()
        }
        return true
    }

    fun recordAsync(context: Context, event: ActivityEvent) {
        if (!ActivityPreferences.isEnabled(context)) return
        val app = context.applicationContext
        runCatching { writer.execute { record(app, event) } }
    }

    /** Records at most once for a stable (type, timestamp, subject) identity. */
    private fun recordOnce(context: Context, event: ActivityEvent): Boolean {
        if (!ActivityPreferences.isEnabled(context)) return false
        val app = context.applicationContext
        synchronized(lock) {
            ensureLoadedLocked(app)
            if (events.any { it.type == event.type && it.ts == event.ts && it.subject == event.subject }) {
                return false
            }
        }
        return record(app, event)
    }

    /** Applies current semantics to rows written by older builds. */
    private fun normalizeEvent(event: ActivityEvent): ActivityEvent {
        var normalized = event
        if (normalized.lane == ActivityLane.CONNECTION &&
            normalized.type == ActivityTypes.CONNECTION_ERROR &&
            normalized.reason == null &&
            normalized.group != null
        ) {
            // Older builds stored the connection error class in `group`.
            normalized = normalized.copy(group = null, reason = normalized.group)
        }
        if (normalized.type == ActivityTypes.URLTEST) {
            // A node test is not a group-level anomaly.
            normalized = normalized.copy(severity = ActivitySeverity.INFO, obstacle = false)
        } else if (normalized.type == ActivityTypes.URLTEST_HEALTH) {
            normalized = normalized.copy(obstacle = false)
        }
        return normalized
    }

    fun observeCoreLogs(context: Context, lines: List<String>) {
        if (!ActivityPreferences.isEnabled(context)) return
        val app = context.applicationContext
        for (line in lines) {
            IPN_STATE.find(line)?.let { match ->
                recordTailscale(app, match.groupValues[1], match.groupValues[2])
            }
            URLTEST.find(line)?.let { match ->
                val group = match.groupValues[1]
                val outbound = match.groupValues[2]
                val ok = match.groupValues[3] == "available"
                val detail = match.groupValues[4]
                val ms = LEADING_INT.find(detail)?.value?.toIntOrNull()
                recordUrlTest(app, group, outbound, ok, ms)
            }
        }
    }

    fun serviceRunningAt(context: Context, at: Long): Boolean {
        val app = context.applicationContext
        synchronized(lock) {
            ensureLoadedLocked(app)
            ActivityReducer.prune(events, System.currentTimeMillis())
            val last = events.filter { it.lane == ActivityLane.SERVICE && it.ts <= at }.maxByOrNull { it.ts }
            return last?.type == "service_start"
        }
    }

    fun snapshot(context: Context, end: Long, windowMs: Long): List<ActivityEvent> {
        val app = context.applicationContext
        synchronized(lock) {
            ensureLoadedLocked(app)
            ActivityReducer.prune(events, System.currentTimeMillis())
            return ActivityReducer.window(events, end - windowMs, end)
        }
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            ensureLoadedLocked(app)
            events.clear()
            dirty = false
        }
        runCatching {
            writer.execute {
                synchronized(lock) { file?.let { runCatching { it.delete() } } }
            }
        }
    }

    fun recordVersionIfNeeded(context: Context) {
        val app = context.applicationContext
        val currentCode = BuildConfig.VERSION_CODE
        val lastCode = ActivityPreferences.lastVersionCode(app)
        if (currentCode == lastCode) return
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val now = System.currentTimeMillis()
        val firstInstall = info?.firstInstallTime ?: now
        val lastUpdate = info?.lastUpdateTime ?: now
        if (lastCode == 0) {
            val updated = lastUpdate > firstInstall + 60_000L
            recordAsync(
                app,
                ActivityEvent(
                    ts = if (updated) lastUpdate else firstInstall,
                    lane = ActivityLane.VERSION,
                    type = if (updated) ActivityTypes.APP_UPDATE else ActivityTypes.APP_INSTALL,
                    subject = BuildConfig.VERSION_NAME,
                    detail = if (updated) "installed / updated" else "first install",
                ),
            )
        } else {
            recordAsync(
                app,
                ActivityEvent(
                    ts = lastUpdate,
                    lane = ActivityLane.VERSION,
                    type = ActivityTypes.APP_UPDATE,
                    subject = BuildConfig.VERSION_NAME,
                    detail = "${ActivityPreferences.lastVersionName(app)} → ${BuildConfig.VERSION_NAME}",
                    from = ActivityPreferences.lastVersionName(app).ifBlank { null },
                    to = BuildConfig.VERSION_NAME,
                ),
            )
        }
        ActivityPreferences.setLastVersion(app, currentCode, BuildConfig.VERSION_NAME)
    }

    fun recordService(context: Context, started: Boolean, reason: String? = null) {
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.SERVICE,
                type = if (started) ActivityTypes.SERVICE_START else ActivityTypes.SERVICE_STOP,
                detail = reason,
                severity = if (!started && reason != null && reason != "stop") ActivitySeverity.WARN else ActivitySeverity.INFO,
                obstacle = !started && reason != null && reason != "stop",
            ),
        )
    }

    fun recordServiceAlert(context: Context, alert: String, detail: String? = null) {
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.SERVICE,
                type = ActivityTypes.SERVICE_ALERT,
                subject = alert,
                detail = detail,
                severity = ActivitySeverity.ERROR,
                obstacle = true,
            ),
        )
    }

    fun recordVpnRevoked(context: Context) {
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.SERVICE,
                type = ActivityTypes.VPN_REVOKED,
                severity = ActivitySeverity.ERROR,
                obstacle = true,
            ),
        )
    }

    fun recordServiceRestore(
        context: Context,
        action: String,
        result: String,
        startedByUser: Boolean,
        restoreAfterUpdate: Boolean,
    ) {
        val bad = result == "blocked_crash" || result == "failed" || result == "start_failed" || result == "exception"
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.SERVICE,
                type = ActivityTypes.SERVICE_RESTORE,
                subject = result,
                detail = "action=$action started_by_user=$startedByUser restore_after_update=$restoreAfterUpdate",
                severity = if (bad) ActivitySeverity.ERROR else ActivitySeverity.INFO,
                obstacle = bad,
            ),
        )
    }

    fun recordConfigReload(context: Context, reason: String? = null) {
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.SERVICE,
                type = ActivityTypes.CONFIG_RELOAD,
                subject = reason,
                severity = ActivitySeverity.INFO,
            ),
        )
    }

    fun recordNetwork(context: Context, transport: String, validated: Boolean, metered: Boolean) {
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.NETWORK,
                type = ActivityTypes.NETWORK_CHANGE,
                subject = transport,
                detail =
                    buildString {
                        if (validated) append("validated") else append("not validated")
                        if (metered) append(" · metered")
                    },
            ),
        )
    }

    fun recordNetworkLost(context: Context) {
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.NETWORK,
                type = ActivityTypes.NETWORK_LOST,
                severity = ActivitySeverity.ERROR,
                obstacle = true,
            ),
        )
    }

    fun recordTailscale(context: Context, from: String, to: String) {
        val down = from == "Running" && to != "Running"
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.TAILSCALE,
                type = ActivityTypes.TAILSCALE_STATE,
                subject = to,
                detail = "$from → $to",
                from = from,
                to = to,
                ok = to == "Running",
                severity = if (down) ActivitySeverity.WARN else ActivitySeverity.INFO,
                obstacle = down,
            ),
        )
    }

    fun recordPower(context: Context, doze: Boolean, powerSave: Boolean, interactive: Boolean) {
        val key = "$doze/$powerSave/$interactive"
        if (key == lastPowerKey) return
        val subject = powerStateLabel(doze, powerSave, interactive)
        val detail = "power_save=$powerSave interactive=$interactive"
        val app = context.applicationContext
        if (ActivityPreferences.isEnabled(app)) {
            synchronized(lock) {
                ensureLoadedLocked(app)
                // Service restarts re-snapshot the same state; store only changes.
                val last = events.lastOrNull { it.type == ActivityTypes.POWER_STATE }
                if (last?.subject == subject && last.detail == detail) {
                    lastPowerKey = key
                    return
                }
            }
        }
        lastPowerKey = key
        record(
            app,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.POWER,
                type = ActivityTypes.POWER_STATE,
                subject = subject,
                detail = detail,
            ),
        )
    }

    fun recordConnectionErrors(
        context: Context,
        attempts: List<Map<String, Any?>>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (attempts.isEmpty()) return
        data class Agg(var count: Int, var lastOutbound: String?, var cause: String?)
        val buckets = LinkedHashMap<String, Agg>()
        for (attempt in attempts) {
            if (attempt["result"] != "fail") continue
            val errorClass = attempt["error_class"]?.toString()?.take(32)?.ifBlank { null } ?: "other"
            val rawTs = (attempt["dial_ts"] as? Number)?.toLong()
            val ts = rawTs?.takeIf { it in now - ActivityReducer.RETENTION_MS..now + 5 * 60_000L } ?: now
            val bucket = ts / ActivityReducer.COALESCE_MS * ActivityReducer.COALESCE_MS
            val key = "$bucket|$errorClass"
            val agg = buckets.getOrPut(key) { Agg(0, null, null) }
            agg.count++
            agg.lastOutbound = attempt["outbound"]?.toString()?.take(80)
            agg.cause = attempt["cause"]?.toString()?.take(32) ?: attempt["error_class"]?.toString()?.take(32)
        }
        for ((key, agg) in buckets) {
            val parts = key.split('|', limit = 2)
            val bucket = parts[0].toLongOrNull() ?: now
            val errorClass = parts.getOrNull(1) ?: "other"
            record(
                context,
                ActivityEvent(
                    ts = bucket,
                    lane = ActivityLane.CONNECTION,
                    type = ActivityTypes.CONNECTION_ERROR,
                    reason = errorClass,
                    subject = agg.lastOutbound,
                    detail = agg.cause,
                    severity = ActivitySeverity.WARN,
                    obstacle = true,
                    count = agg.count,
                ),
            )
        }
    }

    fun recordTelemetry(
        context: Context,
        ok: Boolean,
        hadPending: Boolean,
        lines: Int,
        failure: String?,
    ) {
        val detail =
            when {
                !hadPending -> "no pending"
                ok -> "$lines lines"
                else -> failure ?: "failed"
            }
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.TELEMETRY,
                type = ActivityTypes.TELEMETRY_UPLOAD,
                detail = detail,
                ok = ok,
                severity = if (ok) ActivitySeverity.INFO else ActivitySeverity.WARN,
                obstacle = !ok,
            ),
        )
    }

    fun recordProfileUpdate(
        context: Context,
        name: String,
        changed: Boolean,
        ms: Long,
        error: String?,
    ) {
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.SUBSCRIPTION,
                type = ActivityTypes.PROFILE_UPDATE,
                subject = name,
                detail = error ?: if (changed) "changed" else "unchanged",
                ms = ms.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                ok = error == null,
                changed = changed,
                severity = if (error == null) ActivitySeverity.INFO else ActivitySeverity.WARN,
                obstacle = error != null,
            ),
        )
    }

    fun recordProfileSwitch(context: Context, name: String) {
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.SUBSCRIPTION,
                type = ActivityTypes.PROFILE_SWITCH,
                subject = name,
            ),
        )
    }

    fun recordUrlTest(
        context: Context,
        group: String,
        outbound: String,
        ok: Boolean,
        ms: Int?,
    ) {
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.URLTEST,
                type = ActivityTypes.URLTEST,
                subject = outbound,
                group = group,
                to = outbound,
                ms = ms,
                ok = ok,
                // A single node failing its test is how the group chooses a
                // better one. Group-level outages are recorded separately.
                severity = ActivitySeverity.INFO,
            ),
        )
    }

    fun recordUrlTestHealth(
        context: Context,
        group: String,
        outbound: String,
        up: Boolean,
        delayMs: Int?,
    ) {
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.URLTEST,
                type = ActivityTypes.URLTEST_HEALTH,
                subject = outbound,
                group = group,
                to = outbound,
                ms = delayMs,
                ok = up,
                severity = if (up) ActivitySeverity.INFO else ActivitySeverity.WARN,
            ),
        )
    }

    fun recordGroupSwitch(
        context: Context,
        group: String,
        from: String?,
        to: String,
        reason: String,
        ms: Int?,
        available: Int,
        total: Int,
    ) {
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.GROUP,
                type = ActivityTypes.GROUP_SWITCH,
                subject = group,
                group = group,
                from = from,
                to = to,
                reason = reason,
                ms = ms,
                available = available.takeIf { it > 0 },
                total = total.takeIf { it > 0 },
            ),
        )
    }

    /** The whole URLTest group has no node passing its last check. */
    fun recordGroupUnavailable(
        context: Context,
        group: String,
        selected: String?,
        total: Int,
        since: Long,
        observedAt: Long,
    ) {
        recordOnce(
            context,
            ActivityEvent(
                ts = since,
                lastTs = observedAt,
                lane = ActivityLane.GROUP,
                type = ActivityTypes.GROUP_UNAVAILABLE,
                subject = group,
                to = selected,
                reason = "no_available_nodes",
                available = 0,
                total = total.takeIf { it > 0 },
                severity = ActivitySeverity.ERROR,
                obstacle = true,
            ),
        )
    }

    /** The group keeps routing through a node that is failing its health check. */
    fun recordGroupStuck(
        context: Context,
        group: String,
        selected: String?,
        available: Int?,
        total: Int,
        since: Long,
        observedAt: Long,
    ) {
        recordOnce(
            context,
            ActivityEvent(
                ts = since,
                lastTs = observedAt,
                lane = ActivityLane.GROUP,
                type = ActivityTypes.GROUP_STUCK,
                subject = group,
                to = selected,
                reason = "selected_unhealthy",
                available = available,
                total = total.takeIf { it > 0 },
                severity = ActivitySeverity.WARN,
                obstacle = true,
            ),
        )
    }

    fun recordCrash(context: Context, id: String, ts: Long, detail: String? = null) {
        recordOnce(
            context,
            ActivityEvent(
                ts = ts,
                lane = ActivityLane.SYSTEM,
                type = ActivityTypes.CRASH,
                subject = id,
                detail = detail,
                severity = ActivitySeverity.ERROR,
                evidence = ActivityEvidence(ActivityEvidenceKind.CRASH, id),
                obstacle = true,
            ),
        )
    }

    fun recordOom(context: Context, id: String, ts: Long, detail: String? = null) {
        recordOnce(
            context,
            ActivityEvent(
                ts = ts,
                lane = ActivityLane.SYSTEM,
                type = ActivityTypes.OOM,
                subject = id,
                detail = detail,
                severity = ActivitySeverity.ERROR,
                evidence = ActivityEvidence(ActivityEvidenceKind.OOM, id),
                obstacle = true,
            ),
        )
    }

    fun recordProcessExit(
        context: Context,
        ts: Long,
        reason: String,
        detail: String?,
    ) {
        recordOnce(
            context,
            ActivityEvent(
                ts = ts,
                lane = ActivityLane.SYSTEM,
                type = ActivityTypes.PROCESS_EXIT,
                subject = reason,
                detail = detail,
                severity = ActivitySeverity.ERROR,
                obstacle = true,
            ),
        )
    }

    private fun schedulePersistLocked() {
        if (persistScheduled) return
        persistScheduled = true
        runCatching { writer.execute { persistNow() } }
    }

    private fun persistNow() {
        val snapshot: List<ActivityEvent>
        val target: File
        synchronized(lock) {
            persistScheduled = false
            if (!dirty) return
            dirty = false
            snapshot = events.toList()
            target = file ?: return
        }
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            val text = snapshot.joinToString("\n") { json.encodeToString(ActivityEvent.serializer(), it) }
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private val IPN_STATE = Regex("Switching ipn state (\\w+) -> (\\w+)")
    private val URLTEST = Regex("outbound/urltest\\[([^\\]]+)\\]: outbound (.+?) (available|unavailable): (.*)$")
    private val LEADING_INT = Regex("^\\d+")
}
