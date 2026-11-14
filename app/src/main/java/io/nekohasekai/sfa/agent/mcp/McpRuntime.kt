package io.nekohasekai.sfa.agent.mcp

import android.content.Context
import io.nekohasekai.sfa.agent.AgentTool
import io.nekohasekai.sfa.agent.ToolCancellation
import io.nekohasekai.sfa.vfs.VfsIssuer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

enum class McpPhase {
    OFF,
    CONNECTING,
    READY,
    ERROR,
}

data class McpListedTool(
    val serverId: String,
    val serverName: String,
    val remoteName: String,
    val localName: String,
    val summary: String,
    val asksFirst: Boolean,
    val required: List<String>,
)

data class McpServerView(
    val id: String,
    val name: String,
    val url: String,
    val hasToken: Boolean,
    val enabled: Boolean,
    val phase: McpPhase,
    val toolCount: Int,
    val fetched: Boolean,
    val error: String?,
)

sealed class McpAddResult {
    data object Saved : McpAddResult()

    data class Invalid(
        val error: McpConfigError,
    ) : McpAddResult()
}

class McpRuntime(
    private val repository: McpServerRepository,
    private val transportFactory: (McpServerConfig) -> McpTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val staleAfterMs: Long = 60_000,
    private val clientVersion: String = "wondersing",
) {
    private val gate = Mutex()
    private var servers: List<McpServerConfig> = emptyList()
    private val cache = mutableMapOf<String, ServerCache>()
    private val phase = mutableMapOf<String, McpPhase>()
    private val errors = mutableMapOf<String, String>()
    private val fetched = mutableSetOf<String>()
    private val connecting = mutableSetOf<String>()
    private val generation = mutableMapOf<String, Int>()
    private val attemptedAt = mutableMapOf<String, Long>()
    private val _views = MutableStateFlow<List<McpServerView>>(emptyList())
    val views: StateFlow<List<McpServerView>> = _views.asStateFlow()
    private val _listed = MutableStateFlow<List<McpListedTool>>(emptyList())
    val listed: StateFlow<List<McpListedTool>> = _listed.asStateFlow()
    private val cancels = CopyOnWriteArrayList<McpCancel>()

    @Volatile
    private var toolSnapshot: List<AgentTool> = emptyList()

    init {
        servers = repository.load()
        publishLocked()
    }

    fun tools(): List<AgentTool> = toolSnapshot

    suspend fun listedTools(): List<McpListedTool> = gate.withLock { listLocked() }

    /** Resolve exactly one enabled server whose issuer base matches a Link. */
    suspend fun resolveServerForVfs(issuer: String): McpServerConfig? =
        gate.withLock {
            servers
                .filter { config ->
                    config.enabled &&
                        VfsIssuer.matches(
                            issuer,
                            config.issuerBase.takeIf { it.isNotBlank() },
                            config.url,
                        )
                }.singleOrNull()
        }

    fun cancelActive() {
        cancels.forEach { it.cancel() }
    }

    suspend fun add(
        name: String,
        url: String,
        token: String,
    ): McpAddResult {
        val config =
            gate.withLock {
                val error = McpServerValidator.validate(name, url, token, servers.size)
                if (error != null) return McpAddResult.Invalid(error)
                val created =
                    McpServerConfig(
                        id = McpServerValidator.allocateId(name, servers.map { it.id }.toSet()),
                        name = name.trim(),
                        url = url.trim(),
                        token = token.trim(),
                        enabled = false,
                    )
                servers = servers + created
                repository.save(servers)
                publishLocked()
                created
            }
        connect(config, disableOnFailure = true)
        return McpAddResult.Saved
    }

    suspend fun remove(id: String) {
        gate.withLock {
            bumpLocked(id)
            servers = servers.filter { it.id != id }
            dropLocked(id)
            repository.save(servers)
            rebuildToolsLocked()
            publishLocked()
        }
    }

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        if (!enabled) {
            gate.withLock { disableLocked(id) }
            return
        }
        val config =
            gate.withLock {
                val current = servers.find { it.id == id } ?: return@withLock null
                servers = servers.map { if (it.id == id) it.copy(enabled = true) else it }
                repository.save(servers)
                publishLocked()
                current.copy(enabled = true)
            } ?: return
        connect(config, disableOnFailure = true)
    }

    suspend fun refresh(id: String) {
        val config = gate.withLock { servers.find { it.id == id && it.enabled } } ?: return
        connect(config, disableOnFailure = false)
    }

    suspend fun refreshIfStale() {
        val due =
            gate.withLock {
                val now = clock()
                servers.filter { config ->
                    if (!config.enabled) return@filter false
                    val cached = cache[config.id]
                    if (cached != null) return@filter now - cached.fetchedAt >= staleAfterMs
                    val attempted = attemptedAt[config.id] ?: return@filter true
                    now - attempted >= staleAfterMs
                }
            }
        coroutineScope {
            due.map { config -> async { connect(config, disableOnFailure = false) } }
                .forEach { it.await() }
        }
    }

    private suspend fun connect(
        config: McpServerConfig,
        disableOnFailure: Boolean,
    ) {
        val ticket =
            gate.withLock {
                if (servers.none { it.id == config.id }) return
                if (config.id in connecting) return
                connecting += config.id
                phase[config.id] = McpPhase.CONNECTING
                errors.remove(config.id)
                publishLocked()
                generation[config.id] ?: 0
            }
        val outcome = runCatching { open(config) }
        try {
            outcome.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            gate.withLock {
                if ((generation[config.id] ?: 0) != ticket) return@withLock
                if (servers.none { it.id == config.id }) return@withLock
                outcome.fold(
                    onSuccess = { opened ->
                        val previous = cache[config.id]
                        if (previous != null && !sameToolDefinitions(previous.remote, opened.remote)) {
                            errors[config.id] =
                                "MCP tool definitions changed. Disable and re-enable this server to review the new tools."
                            attemptedAt[config.id] = clock()
                            cache[config.id] = previous.copy(fetchedAt = clock())
                            phase[config.id] = McpPhase.READY
                        } else {
                            cache[config.id] = opened
                            fetched += config.id
                            attemptedAt.remove(config.id)
                            errors.remove(config.id)
                            phase[config.id] = McpPhase.READY
                        }
                        servers = servers.map { if (it.id == config.id) it.copy(enabled = true) else it }
                        repository.save(servers)
                    },
                    onFailure = { error ->
                        attemptedAt[config.id] = clock()
                        errors[config.id] = safeMessage(error)
                        if (disableOnFailure) {
                            cache.remove(config.id)
                            fetched.remove(config.id)
                            phase[config.id] = McpPhase.ERROR
                            servers = servers.map { if (it.id == config.id) it.copy(enabled = false) else it }
                            repository.save(servers)
                        } else {
                            phase[config.id] = if (cache[config.id] == null) McpPhase.ERROR else McpPhase.READY
                        }
                    },
                )
                rebuildToolsLocked()
                publishLocked()
            }
        } finally {
            gate.withLock {
                connecting -= config.id
                if (phase[config.id] == McpPhase.CONNECTING && servers.any { it.id == config.id }) {
                    phase[config.id] =
                        when {
                            cache[config.id] != null -> McpPhase.READY
                            servers.first { it.id == config.id }.enabled -> McpPhase.ERROR
                            else -> McpPhase.OFF
                        }
                }
                publishLocked()
            }
        }
    }

    private suspend fun open(config: McpServerConfig): ServerCache {
        val session =
            McpSession(
                transport = transportFactory(config),
                token = config.token,
                clientVersion = clientVersion,
            )
        val cancel = McpCancel()
        cancels.add(cancel)
        val remote =
            try {
                session.listTools(cancel)
            } finally {
                cancels.remove(cancel)
            }
        return ServerCache(
            session = session,
            remote = remote,
            tools = mcpAgentTools(config.name, config.id, remote, session),
            fetchedAt = clock(),
        )
    }

    private fun bumpLocked(id: String) {
        generation[id] = (generation[id] ?: 0) + 1
    }

    private fun disableLocked(id: String) {
        if (servers.none { it.id == id }) return
        bumpLocked(id)
        servers = servers.map { if (it.id == id) it.copy(enabled = false) else it }
        dropLocked(id)
        phase[id] = McpPhase.OFF
        repository.save(servers)
        rebuildToolsLocked()
        publishLocked()
    }

    private fun dropLocked(id: String) {
        cache.remove(id)
        fetched.remove(id)
        errors.remove(id)
        attemptedAt.remove(id)
    }

    private fun listLocked(): List<McpListedTool> =
        servers.filter { it.enabled }.flatMap { server ->
            val cached = cache[server.id] ?: return@flatMap emptyList()
            cached.remote.zip(cached.tools).map { (remote, tool) ->
                McpListedTool(
                    serverId = server.id,
                    serverName = server.name,
                    remoteName = remote.name,
                    localName = tool.definition.name,
                    summary = remote.description.ifBlank { remote.name }.take(180),
                    asksFirst = true,
                    required = requiredKeys(remote.inputSchema),
                )
            }
        }

    private fun rebuildToolsLocked() {
        _listed.value = listLocked()
        toolSnapshot =
            servers.filter { it.enabled }.flatMap { config ->
                cache[config.id]?.tools.orEmpty()
            }
    }

    private fun publishLocked() {
        _views.value =
            servers.map { config ->
                val cached = cache[config.id]
                McpServerView(
                    id = config.id,
                    name = config.name,
                    url = config.url,
                    hasToken = config.token.isNotBlank(),
                    enabled = config.enabled,
                    phase = phase[config.id] ?: if (config.enabled) McpPhase.READY else McpPhase.OFF,
                    toolCount = cached?.remote?.size ?: 0,
                    fetched = config.id in fetched,
                    error = errors[config.id],
                )
            }
    }

    private fun requiredKeys(schema: JsonObject): List<String> {
        val required = schema["required"] as? JsonArray ?: return emptyList()
        return required.mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }

    private fun sameToolDefinitions(
        old: List<McpRemoteTool>,
        new: List<McpRemoteTool>,
    ): Boolean =
        old.size == new.size &&
            old.map { it.name to it.fingerprint }.toSet() == new.map { it.name to it.fingerprint }.toSet()

    private fun safeMessage(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() } ?: "Could not reach the MCP server."
        return message.take(180)
    }

    private class McpCancel : ToolCancellation {
        @Volatile
        private var cancelled = false
        private val actions = CopyOnWriteArrayList<() -> Unit>()

        override fun isCancelled(): Boolean = cancelled

        override fun onCancel(action: () -> Unit) {
            if (cancelled) {
                runCatching { action() }
            } else {
                actions.add(action)
            }
        }

        fun cancel() {
            if (cancelled) return
            cancelled = true
            actions.forEach { runCatching { it() } }
            actions.clear()
        }
    }

    private data class ServerCache(
        val session: McpSession,
        val remote: List<McpRemoteTool>,
        val tools: List<AgentTool>,
        val fetchedAt: Long,
    )
}

object McpRuntimes {
    private val lock = Any()

    @Volatile
    private var instance: McpRuntime? = null

    fun get(context: Context): McpRuntime {
        instance?.let { return it }
        synchronized(lock) {
            instance?.let { return it }
            val file = File(context.applicationContext.filesDir, "conductor/mcp-servers.json")
            return McpRuntime(
                repository = FileMcpServerRepository(file, McpTokenCipherFactory.create()),
                transportFactory = { config -> McpHttpTransport(config.url, config.token) },
            ).also { instance = it }
        }
    }
}
