package io.nekohasekai.sfa.vfs

import android.content.Context
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.agent.AgentApiTrace
import io.nekohasekai.sfa.conductor.ConductorSessionStore
import io.nekohasekai.sfa.conductor.conductorConversationMarkdown

/** Wires the pure conductor source to the app's working directory and runtime. */
class AndroidVirtualDirectorySource(
    context: Context,
) : VirtualDirectorySource {
    private val appContext = context.applicationContext
    private val delegate =
        ConductorVirtualDirectorySource(
            workingDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir,
            runtimeFiles =
                object : VirtualDirectoryRuntimeFiles {
                    override fun traceMarkdown(): String = AgentApiTrace.asMarkdown()

                    override fun conversationMarkdown(): String {
                        val session = ConductorSessionStore(appContext).load()
                        return conductorConversationMarkdown(
                            items = session?.items.orEmpty(),
                            appVersion = BuildConfig.VERSION_NAME,
                            sessionStartVersion = session?.appVersion.orEmpty(),
                        )
                    }

                    override fun systemPromptMarkdown(): String =
                        AgentApiTrace.lastSystemPrompt()?.takeIf { it.isNotBlank() }
                            ?: "No system prompt has been recorded in this session."
                },
        )

    override suspend fun stat(path: String): VfsEntry = delegate.stat(path)

    override suspend fun list(path: String): List<VfsEntry> = delegate.list(path)

    override suspend fun read(
        path: String,
        offset: Long,
        length: Int,
    ): VfsRead = delegate.read(path, offset, length)
}
