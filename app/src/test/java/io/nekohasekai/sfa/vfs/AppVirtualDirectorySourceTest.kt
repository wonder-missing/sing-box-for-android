package io.nekohasekai.sfa.vfs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppVirtualDirectorySourceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class FakeRuntimeFiles(
        var trace: String = "# Trace\n",
        var conversation: String = "# Conversation\n",
        var prompt: String = "# System prompt\n",
    ) : VirtualDirectoryRuntimeFiles {
        override fun traceMarkdown(): String = trace

        override fun conversationMarkdown(): String = conversation

        override fun systemPromptMarkdown(): String = prompt
    }

    private fun appSource(
        externalDir: File,
        internalDir: File,
        runtime: FakeRuntimeFiles = FakeRuntimeFiles(),
    ): AppVirtualDirectorySource {
        val conductor =
            ConductorVirtualDirectorySource(
                workingDir = externalDir,
                runtimeFiles = runtime,
                now = { 5_000L },
            )
        return AppVirtualDirectorySource(
            external = WorkspaceVirtualDirectorySource(externalDir, conductor, now = { 5_000L }),
            conductor = conductor,
            internalFiles = InternalFilesVirtualDirectorySource(internalDir, now = { 5_000L }),
            now = { 5_000L },
        )
    }

    private fun write(
        root: File,
        path: String,
        text: String,
    ): File {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    @Test
    fun rootMergesExternalAppfilesAndRuntime() =
        runBlocking {
            val external = temp.newFolder("external")
            val internal = temp.newFolder("internal")
            write(external, "conductor/note.md", "hello conductor")
            write(external, "crash_reports/metadata.json", "{}")
            write(external, "appfiles/real.txt", "real")
            write(internal, "logs/app.log", "hello app")
            write(internal, "vfs/trust.json", "private")
            val source = appSource(external, internal)

            assertEquals(
                listOf("conductor", "crash_reports", "appfiles", ".runtime"),
                source.list("").map { it.name },
            )
            assertEquals(
                listOf("logs"),
                source.list("appfiles").map { it.name },
            )
            assertFalse(source.list("appfiles").any { it.name == "vfs" })
        }

    @Test
    fun routesEachRootToTheRightSource() =
        runBlocking {
            val external = temp.newFolder("external")
            val internal = temp.newFolder("internal")
            write(external, "conductor/note.md", "hello conductor")
            write(external, "crash_reports/metadata.json", "{}")
            write(internal, "logs/app.log", "hello app")
            write(internal, "vfs/trust.json", "private")
            val source = appSource(external, internal)

            assertEquals("conductor/note.md", source.stat("conductor/note.md").path)
            assertArrayEquals("hello conductor".toByteArray(), source.read("conductor/note.md", 0, 99).data)
            assertEquals("crash_reports/metadata.json", source.stat("crash_reports/metadata.json").path)
            assertEquals("appfiles/logs/app.log", source.stat("appfiles/logs/app.log").path)
            assertArrayEquals("hello app".toByteArray(), source.read("appfiles/logs/app.log", 0, 99).data)
            assertTrue(source.stat(".runtime/trace.md").size > 0)
            assertEquals("not_found", failure { source.stat("appfiles/vfs/trust.json") }.code)
        }

    @Test
    fun worksWithoutAnExternalWorkingDirectory() =
        runBlocking {
            val internal = temp.newFolder("internal")
            write(internal, "logs/app.log", "hello app")
            val conductor =
                ConductorVirtualDirectorySource(
                    workingDir = internal,
                    runtimeFiles = FakeRuntimeFiles(),
                    now = { 5_000L },
                )
            val source =
                AppVirtualDirectorySource(
                    external = null,
                    conductor = conductor,
                    internalFiles = InternalFilesVirtualDirectorySource(internal, now = { 5_000L }),
                    now = { 5_000L },
                )

            assertEquals(
                listOf("conductor", "appfiles", ".runtime"),
                source.list("").map { it.name },
            )
            assertArrayEquals("hello app".toByteArray(), source.read("appfiles/logs/app.log", 0, 99).data)
            assertEquals("not_found", failure { source.stat("crash_reports/metadata.json") }.code)
        }

    private suspend fun failure(block: suspend () -> Unit): VfsException {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected VfsException, got $error", error is VfsException)
        return error as VfsException
    }
}
