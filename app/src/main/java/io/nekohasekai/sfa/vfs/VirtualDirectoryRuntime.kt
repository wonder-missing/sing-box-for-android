package io.nekohasekai.sfa.vfs

import android.content.Context
import io.nekohasekai.sfa.agent.mcp.McpTokenCipherFactory
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the provider connection. The Tools page changes
 * settings and asks for a [reload]; the Application starts it after settings
 * are available.
 */
object VirtualDirectoryRuntime {
    const val TOKEN_ID = "virtual-directory"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<VirtualDirectoryState>(VirtualDirectoryState.Disabled)
    val state: StateFlow<VirtualDirectoryState> = _state.asStateFlow()
    private val _activity = MutableStateFlow(VirtualDirectoryActivity())
    val activity: StateFlow<VirtualDirectoryActivity> = _activity.asStateFlow()

    @Volatile
    private var provider: VirtualDirectoryProvider? = null

    @Synchronized
    fun initialize(context: Context) {
        if (provider != null) return
        val created =
            VirtualDirectoryProvider(
                source = AndroidVirtualDirectorySource(context.applicationContext),
                transport = OkHttpVirtualDirectoryTransport(),
                scope = scope,
            )
        provider = created
        scope.launch { created.state.collect { _state.value = it } }
        scope.launch { created.activity.collect { _activity.value = it } }
    }

    fun reload() {
        scope.launch {
            val current = provider ?: return@launch
            if (!Settings.virtualDirectoryEnabled) {
                current.stop()
                return@launch
            }
            val url = normalizeVirtualDirectoryUrl(Settings.virtualDirectoryUrl)
            if (url == null) {
                current.fail("Enter a ws:// or wss:// endpoint in the Virtual Directory settings.")
                return@launch
            }
            current.start(VirtualDirectoryConfig(url, readToken()))
        }
    }

    fun stop() {
        provider?.stop()
    }

    private fun readToken(): String? {
        val blob = Settings.virtualDirectoryTokenBlob
        if (blob.isBlank()) return null
        return McpTokenCipherFactory.create().decrypt(TOKEN_ID, blob)?.takeIf { it.isNotBlank() }
    }
}
