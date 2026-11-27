package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.utils.humanBytes
import io.nekohasekai.sfa.vfs.VfsConnectionState
import io.nekohasekai.sfa.vfs.VfsRuntime
import io.nekohasekai.sfa.vfs.VfsScope
import io.nekohasekai.sfa.vfs.VfsSharingPolicy
import io.nekohasekai.sfa.vfs.relay.VfsRelayRuntime
import io.nekohasekai.sfa.vfs.relay.VfsRelayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the VFS provider connection alive while the app is in the background.
 * The ongoing notification is the user-visible counterpart: sharing never
 * continues silently, and Disconnect is always one tap away.
 */
class VfsSharingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private var lastServer: String? = null
    private var lastScope: Set<VfsScope> = emptySet()
    private var lastText: String? = null
    private var lastBigText: String? = null
    private var lastSubText: String? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        VfsRuntime.setSharingActive(true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            // Keep the foreground status until the state collector sees Idle;
            // stopping here could let the process die mid-close.
            VfsRuntime.disconnect()
            return START_NOT_STICKY
        }
        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                foregroundServiceType(),
            )
        } catch (error: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (collectJob == null) {
            collectJob = scope.launch { observe() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        running = false
        requested = false
        VfsRuntime.setSharingActive(false)
        VfsRelayRuntime.onSharingServiceStopped()
        super.onDestroy()
    }

    private suspend fun observe() {
        combine(
            VfsRuntime.state,
            VfsRuntime.uploadBytes,
            VfsRelayRuntime.state,
        ) { state, bytes, relay -> Triple(state, bytes, relay) }
            .collect { (state, bytes, relay) ->
                val oldKeep =
                    VfsSharingPolicy.shouldRun(
                        state = state,
                        hasActiveConnection = VfsRuntime.hasActiveConnection(),
                    )
                val relayActive =
                    relay is VfsRelayState.Connecting ||
                        relay is VfsRelayState.Connected ||
                        relay is VfsRelayState.Backoff
                if (!oldKeep && !relayActive) {
                    stopSelf()
                    return@collect
                }
                if (relayActive) {
                    publishRelay(relay)
                } else {
                    if (state is VfsConnectionState.Connected) {
                        lastServer = state.serverName ?: lastServer
                        lastScope = state.scope
                    }
                    publish(state, bytes)
                }
            }
    }

    private fun publishRelay(state: VfsRelayState) {
        val (text, bigText, subText) =
            when (state) {
                is VfsRelayState.Connected ->
                    Triple(
                        getString(R.string.vfs_relay_notification_text, state.shares.size),
                        state.shares.mapNotNull { it.label.takeIf { label -> label.isNotBlank() } }
                            .joinToString(", ")
                            .ifBlank { getString(R.string.vfs_relay_notification_text, state.shares.size) },
                        null,
                    )

                is VfsRelayState.Backoff ->
                    Triple(
                        getString(R.string.vfs_relay_notification_backoff),
                        state.message,
                        getString(R.string.vfs_relay_notification_backoff),
                    )

                else ->
                    Triple(
                        getString(R.string.vfs_relay_notification_connecting),
                        getString(R.string.vfs_relay_notification_connecting),
                        getString(R.string.vfs_relay_notification_connecting),
                    )
            }
        if (text == lastText && bigText == lastBigText && subText == lastSubText) return
        lastText = text
        lastBigText = bigText
        lastSubText = subText
        runCatching {
            getSystemService<NotificationManager>()?.notify(
                NOTIFICATION_ID,
                buildNotification(text, bigText, subText, stopRelay = true),
            )
        }
    }

    private fun publish(
        state: VfsConnectionState,
        bytes: Long,
    ) {
        val server = lastServer
        val text: String
        val bigText: String
        if (server != null) {
            text = getString(R.string.vfs_notification_text, server, humanBytes(bytes))
            val scopes = lastScope.sortedBy { it.ordinal }.joinToString(", ") { scopeLabel(it) }
            bigText =
                if (scopes.isEmpty()) {
                    text
                } else {
                    text + "\n" + getString(R.string.vfs_notification_scopes, scopes)
                }
        } else {
            text = getString(R.string.vfs_status_connecting)
            bigText = text
        }
        val subText =
            when (state) {
                is VfsConnectionState.Connecting,
                is VfsConnectionState.Backoff,
                -> getString(R.string.vfs_status_connecting)

                else -> null
            }
        if (text == lastText && bigText == lastBigText && subText == lastSubText) return
        lastText = text
        lastBigText = bigText
        lastSubText = subText
        runCatching {
            getSystemService<NotificationManager>()?.notify(
                NOTIFICATION_ID,
                buildNotification(text, bigText, subText),
            )
        }
    }

    private fun buildNotification(
        text: String? = null,
        bigText: String? = null,
        subText: String? = null,
        stopRelay: Boolean = false,
    ) = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
        .setShowWhen(false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSmallIcon(R.drawable.ic_menu)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentTitle(
            getString(if (stopRelay) R.string.vfs_relay_notification_title else R.string.vfs_notification_title),
        )
        .setContentText(text ?: getString(R.string.vfs_status_connecting))
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                bigText ?: getString(R.string.vfs_status_connecting),
            ),
        )
        .apply { subText?.let { setSubText(it) } }
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                pendingIntentFlags(),
            ),
        )
        .apply {
            if (!stopRelay) {
                addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        getText(R.string.vfs_status_disconnect),
                        PendingIntent.getService(
                            this@VfsSharingService,
                            0,
                            Intent(this@VfsSharingService, VfsSharingService::class.java).setAction(ACTION_DISCONNECT),
                            pendingIntentFlags(),
                        ),
                    ).build(),
                )
            }
        }
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.vfs_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun scopeLabel(scope: VfsScope): String =
        getString(
            when (scope) {
                VfsScope.CONDUCTOR -> R.string.vfs_scope_conductor
                VfsScope.TRACE -> R.string.vfs_scope_trace
                VfsScope.SYSTEM_PROMPT -> R.string.vfs_scope_system_prompt
                VfsScope.CONVERSATION -> R.string.vfs_scope_conversation
                VfsScope.WORKDIR -> R.string.vfs_scope_workdir
                VfsScope.APPFILES -> R.string.vfs_scope_appfiles
            },
        )

    companion object {
        private const val NOTIFICATION_ID = 4
        private const val NOTIFICATION_CHANNEL = "vfs-sharing"
        private const val ACTION_DISCONNECT = "io.nekohasekai.sfa.action.VFS_DISCONNECT"

        @Volatile
        private var running = false

        @Volatile
        private var requested = false

        /**
         * Requested from the VFS state observer, which only reaches Connecting
         * after a user-approved (foreground) action. Returns false when Android
         * rejects the background start; the runtime then falls back to grace.
         */
        fun start(context: Context): Boolean {
            if (running || requested) return true
            requested = true
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, VfsSharingService::class.java),
                )
                true
            } catch (error: Exception) {
                requested = false
                false
            }
        }

        fun stop(context: Context) {
            requested = false
            if (!running) return
            runCatching { context.stopService(Intent(context, VfsSharingService::class.java)) }
        }

        /** Runtime gate: never open a provider WS without the visible FGS. */
        fun isActive(): Boolean = running || requested

        private fun foregroundServiceType() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }

        private fun pendingIntentFlags() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
    }
}
