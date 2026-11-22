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
                                ?.let { events.add(it) }
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
                    type = if (updated) "app_update" else "app_install",
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
                    type = "app_update",
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
                type = if (started) "service_start" else "service_stop",
                detail = reason,
                obstacle = !started && reason != null && reason != "stop",
            ),
        )
    }

    fun recordNetwork(context: Context, transport: String, validated: Boolean, metered: Boolean) {
        record(
            context,
            ActivityEvent(
                ts = System.currentTimeMillis(),
                lane = ActivityLane.NETWORK,
                type = "network_change",
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
                type = "network_lost",
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
                type = "tailscale_state",
                subject = to,
                detail = "$from → $to",
                from = from,
                to = to,
                ok = to == "Running",
                obstacle = down,
            ),
        )
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
                type = "telemetry_upload",
                detail = detail,
                ok = ok,
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
                type = "profile_update",
                subject = name,
                detail = error ?: if (changed) "changed" else "unchanged",
                ms = ms.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                ok = error == null,
                changed = changed,
                obstacle = error != null,
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
                type = "urltest",
                subject = outbound,
                group = group,
                to = outbound,
                ms = ms,
                ok = ok,
                obstacle = !ok,
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
                type = "urltest_health",
                subject = outbound,
                group = group,
                to = outbound,
                ms = delayMs,
                ok = up,
                obstacle = !up,
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
                type = "group_switch",
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
