package io.nekohasekai.sfa.bg

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.compose.ShortcutActionActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Status

object LauncherShortcuts {
    fun publish(context: Context, status: Status) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        publish25(context, status)
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private fun publish25(context: Context, status: Status) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val running = status == Status.Started
        val shortcuts = mutableListOf<ShortcutInfo>()

        shortcuts +=
            shortcut(
                context = context,
                id = "toggle_service",
                label = if (running) R.string.shortcut_stop_service else R.string.shortcut_start_service,
                icon = if (running) R.drawable.ic_stop_24 else R.drawable.ic_play_arrow_24,
                intent =
                Intent(context, ShortcutActionActivity::class.java).setAction(
                    if (running) Action.SHORTCUT_STOP_SERVICE else Action.SHORTCUT_START_SERVICE,
                ),
            )

        if (running) {
            shortcuts +=
                shortcut(
                    context = context,
                    id = "tailscale",
                    label = R.string.shortcut_tailscale,
                    icon = R.drawable.ic_electric_bolt_24,
                    intent = Intent(context, MainActivity::class.java).setAction(Action.SHORTCUT_TAILSCALE),
                )
        }

        shortcuts +=
            shortcut(
                context = context,
                id = "conductor",
                label = R.string.shortcut_conductor,
                icon = R.drawable.ic_message_24,
                intent = Intent(context, MainActivity::class.java).setAction(Action.SHORTCUT_CONDUCTOR),
            )

        shortcuts +=
            shortcut(
                context = context,
                id = "upload_telemetry",
                label = R.string.shortcut_upload_telemetry,
                icon = R.drawable.ic_conductor_send,
                intent = Intent(context, ShortcutActionActivity::class.java).setAction(Action.SHORTCUT_UPLOAD_TELEMETRY),
            )

        runCatching { manager.setDynamicShortcuts(shortcuts) }
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private fun shortcut(
        context: Context,
        id: String,
        @StringRes label: Int,
        @DrawableRes icon: Int,
        intent: Intent,
    ): ShortcutInfo =
        ShortcutInfo.Builder(context, id)
            .setShortLabel(context.getString(label))
            .setLongLabel(context.getString(label))
            .setIcon(Icon.createWithResource(context, icon))
            .setIntent(intent)
            .build()
}
