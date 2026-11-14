package io.nekohasekai.sfa.vfs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsScopedSourceTest {
    private val source =
        object : VirtualDirectorySource {
            override suspend fun stat(path: String): VfsEntry =
                VfsEntry(path, path.substringAfterLast('/'), false, 1L, 1L)

            override suspend fun list(path: String): List<VfsEntry> =
                when (path) {
                    "" -> listOf(entry("conductor", true), entry(".runtime", true))
                    "conductor" -> listOf(entry("conductor/a.md", false))
                    ".runtime" ->
                        listOf(
                            entry(".runtime/trace.md", false),
                            entry(".runtime/system_prompt.md", false),
                            entry(".runtime/conversation.md", false),
                        )

                    else -> emptyList()
                }

            override suspend fun read(
                path: String,
                offset: Long,
                length: Int,
            ): VfsRead = VfsRead("ok".toByteArray(), true)
        }

    @Test
    fun conductorOnlyCannotSeeRuntimeFiles() =
        runBlocking {
            val scoped = VfsScopedSource(source, setOf(VfsScope.CONDUCTOR))

            assertEquals(listOf("conductor"), scoped.list("").map { it.path })
            assertEquals("conductor", scoped.stat("conductor").path)
            assertEquals("not_found", failure { scoped.stat(".runtime/trace.md") }.code)
            assertEquals("not_found", failure { scoped.list(".runtime") }.code)
        }

    @Test
    fun traceOnlyHidesConductorAndOtherRuntimeFiles() =
        runBlocking {
            val scoped = VfsScopedSource(source, setOf(VfsScope.TRACE))

            assertEquals(listOf(".runtime"), scoped.list("").map { it.path })
            assertEquals(
                listOf(".runtime/trace.md"),
                scoped.list(".runtime").map { it.path },
            )
            assertEquals("not_found", failure { scoped.stat("conductor/a.md") }.code)
            assertEquals("not_found", failure { scoped.stat(".runtime/conversation.md") }.code)
        }

    @Test
    fun conversationIsExposedOnlyWhenRequested() =
        runBlocking {
            val defaultScope =
                setOf(VfsScope.CONDUCTOR, VfsScope.TRACE, VfsScope.SYSTEM_PROMPT)
            val without = VfsScopedSource(source, defaultScope)
            assertEquals("not_found", failure { without.stat(".runtime/conversation.md") }.code)

            val with = VfsScopedSource(source, defaultScope + VfsScope.CONVERSATION)
            assertEquals(".runtime/conversation.md", with.stat(".runtime/conversation.md").path)
        }

    @Test
    fun readOutsideTheScopeIsNotFound() =
        runBlocking {
            val scoped = VfsScopedSource(source, setOf(VfsScope.CONDUCTOR))

            assertEquals("not_found", failure { scoped.read(".runtime/trace.md", 0, 1) }.code)
            assertEquals("ok", String(scoped.read("conductor/a.md", 0, 2).data))
        }

    private fun entry(
        path: String,
        directory: Boolean,
    ) = VfsEntry(path, path.substringAfterLast('/'), directory, if (directory) 0L else 1L, 1L)

    private suspend fun failure(block: suspend () -> Unit): VfsException {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected VfsException, got $error", error is VfsException)
        return error as VfsException
    }
}
