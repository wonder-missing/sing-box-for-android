package io.nekohasekai.sfa.telemetry

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import android.view.Choreographer

/**
 * Aggregated main-thread frame gaps. It records at most one event per minute,
 * only when the window contains a severe stall or enough janky frames; no
 * stacks, view names or content. The monitor runs only while an activity is
 * resumed so a background pause is not mistaken for a stall.
 */
internal object UiJankMonitor {
    private const val WINDOW_MS = 60_000L

    private var installed = false
    private var app: Application? = null
    private var foreground = 0
    private var lastFrameNanos = 0L
    private var windowStartMs = 0L
    private val window = JankWindow()

    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (foreground > 0) {
                    if (lastFrameNanos != 0L) {
                        window.addGap((frameTimeNanos - lastFrameNanos) / 1_000_000L)
                    }
                    lastFrameNanos = frameTimeNanos
                    val now = SystemClock.uptimeMillis()
                    if (windowStartMs == 0L) windowStartMs = now
                    if (now - windowStartMs >= WINDOW_MS) {
                        if (window.shouldEmit()) {
                            app?.let { TelemetryEventLog.recordAsync(it, "ui_jank", window.fields()) }
                        }
                        window.reset()
                        windowStartMs = now
                    }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

    fun install(application: Application) {
        if (installed) return
        installed = true
        app = application
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    foreground += 1
                    lastFrameNanos = 0L
                    windowStartMs = SystemClock.uptimeMillis()
                }

                override fun onActivityPaused(activity: Activity) {
                    foreground = (foreground - 1).coerceAtLeast(0)
                    if (foreground == 0) resetWindow()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun resetWindow() {
        lastFrameNanos = 0L
        windowStartMs = 0L
        window.reset()
    }
}

/**
 * Pure threshold logic so the aggregation can be unit-tested on the JVM.
 */
internal class JankWindow(
    private val jankMs: Long = 32L,
    private val severeMs: Long = 200L,
    private val minJanky: Int = 5,
) {
    private var frames = 0
    private var janky = 0
    private var severe = 0
    private var maxGapMs = 0L

    fun addGap(gapMs: Long) {
        if (gapMs <= 0) return
        frames += 1
        if (gapMs >= jankMs) janky += 1
        if (gapMs >= severeMs) severe += 1
        if (gapMs > maxGapMs) maxGapMs = gapMs
    }

    fun shouldEmit(): Boolean = severe > 0 || janky >= minJanky

    fun fields(): Map<String, Any> =
        mapOf(
            "frames" to frames,
            "jank32" to janky,
            "severe200" to severe,
            "max_ms" to maxGapMs,
        )

    fun reset() {
        frames = 0
        janky = 0
        severe = 0
        maxGapMs = 0L
    }
}
