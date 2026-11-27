package io.nekohasekai.sfa.vfs.relay

import io.nekohasekai.sfa.vfs.InternalFilesVirtualDirectorySource
import io.nekohasekai.sfa.vfs.VfsEntry
import io.nekohasekai.sfa.vfs.VfsException
import io.nekohasekai.sfa.vfs.VfsRead
import io.nekohasekai.sfa.vfs.VirtualDirectorySource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RelaySourceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var externalDir: File
    private lateinit var internal: RelayAppFilesSource
    private lateinit var appData: AppDataVirtualDirectorySource
    private lateinit var workspace: RelayWorkspaceSource
    private lateinit var selected: UserSelectedVirtualDirectorySource

    private fun prepare() {
        filesDir = File(temp.root, "files").apply { mkdirs() }
        File(filesDir, "a.txt").writeText("alpha", Charsets.UTF_8)
        File(filesDir, "b.txt").writeText("bravo", Charsets.UTF_8)
        File(filesDir, "c.txt").writeText("charlie", Charsets.UTF_8)
        File(filesDir, "vfs").mkdirs()
        File(filesDir, "vfs/hidden").writeText("nope", Charsets.UTF_8)
        File(filesDir, "conductor").mkdirs()
        File(filesDir, "conductor/secret").writeText("secret", Charsets.UTF_8)
        File(filesDir, "activity").mkdirs()
        File(filesDir, "activity/events.jsonl").writeText("{\"a\":1}\n", Charsets.UTF_8)
        File(filesDir, "telemetry").mkdirs()
        File(filesDir, "telemetry/events.log").writeText("raw", Charsets.UTF_8)
        File(filesDir, "logs").mkdirs()
        File(filesDir, "logs/app.log").writeText(
            "2026-01-01 INFO Authorization: Bearer supersecret\n" +
                "2026-01-01 ERROR request https://user:pass@example.com/x failed\n",
            Charsets.UTF_8,
        )
        externalDir = File(temp.root, "external").apply { mkdirs() }
        File(externalDir, "file.txt").writeText("workspace", Charsets.UTF_8)
        File(externalDir, "sub").mkdirs()
        File(externalDir, "sub/nested.txt").writeText("nested", Charsets.UTF_8)
        File(externalDir, "conductor").mkdirs()
        File(externalDir, "conductor/c.md").writeText("c", Charsets.UTF_8)
        File(externalDir, ".runtime").mkdirs()
        File(externalDir, ".runtime/trace.md").writeText("trace", Charsets.UTF_8)
        internal = RelayAppFilesSource(InternalFilesVirtualDirectorySource(filesDir))
        workspace = RelayWorkspaceSource(externalDir)
        appData =
            AppDataVirtualDirectorySource(
                filesDir = filesDir,
                diagnosticsJson = { "{\"generated_at\":1,\"app_version\":\"test\"}" },
                groupsJson = { "{\"available\":false,\"groups\":[]}" },
            )
        selected =
            UserSelectedVirtualDirectorySource(
                resolver =
                    object : SelectedDocumentResolver {
                        override fun documents(uris: List<String>): List<SelectedDocument> =
                            uris.map { SelectedDocument(it, "picked.txt", 6, 10, "text/plain") }

                        override fun read(
                            uri: String,
                            offset: Long,
                            length: Int,
                        ): VfsRead {
                            val data = "picked".toByteArray()
                            val end = minOf(data.size.toLong(), offset + length).toInt()
                            return VfsRead(data.copyOfRange(offset.toInt(), end), end >= data.size)
                        }
                    },
                uris = listOf("content://pick/1"),
            )
    }

    private fun source(scopes: Set<RelayScope>): RelayAbsoluteSource {
        prepare()
        return RelayAbsoluteSource(
            scopes = scopes,
            internalFiles = internal,
            workspace = workspace,
            appData = appData,
            selected = selected,
            shareKey = ByteArray(32) { 1 },
            linkIdRaw = ByteArray(16) { 2 },
            now = { 1_000L },
        )
    }

    @Test
    fun absolutePathsAreStrict() =
        runBlocking {
            val files = source(setOf(RelayScope.APP_FILES))
            expectCode(RelayInnerError.INVALID_PATH) { files.stat("appfiles/a.txt") }
            expectCode(RelayInnerError.INVALID_PATH) { files.stat("/appfiles//a.txt") }
            expectCode(RelayInnerError.INVALID_PATH) { files.stat("/appfiles/../a.txt") }
            expectCode(RelayInnerError.INVALID_PATH) { files.stat("/appfiles/a.txt/") }
            expectCode(RelayInnerError.INVALID_PATH) { files.stat("/" + "a".repeat(4097)) }
        }

    @Test
    fun scopeMatrixHidesUngrantedTreesAndConductor() =
        runBlocking {
            val files = source(setOf(RelayScope.APP_FILES))
            assertEquals("/appfiles/a.txt", files.stat("/appfiles/a.txt").path)
            expectCode(RelayInnerError.NOT_FOUND) { files.stat("/appfiles/vfs/hidden") }
            expectCode(RelayInnerError.NOT_FOUND) { files.stat("/appfiles/conductor/secret") }
            expectCode(RelayInnerError.NOT_FOUND) { files.stat("/appdata/activity/events.jsonl") }
            expectCode(RelayInnerError.NOT_FOUND) { files.stat("/conductor/c.md") }
            expectCode(RelayInnerError.NOT_FOUND) { files.stat("/file.txt") }

            val workspaceOnly = source(setOf(RelayScope.APP_WORKSPACE))
            assertEquals("/file.txt", workspaceOnly.stat("/file.txt").path)
            expectCode(RelayInnerError.NOT_FOUND) { workspaceOnly.stat("/conductor/c.md") }
            expectCode(RelayInnerError.NOT_FOUND) { workspaceOnly.stat("/.runtime/trace.md") }
            expectCode(RelayInnerError.NOT_FOUND) { workspaceOnly.stat("/appfiles/a.txt") }
            assertEquals(listOf("/file.txt", "/sub"), workspaceOnly.list("/", null, 100).entries.map { it.path })

            assertFalse(RelayScope.fixed.any { it.wire.startsWith("conductor.") })
        }

    @Test
    fun workspaceSourceExcludesPhysicalConductorRuntimeAndReservedRoots() =
        runBlocking {
            prepare()
            for (reserved in listOf("conductor", ".runtime", "appfiles", "appdata", "selected", "vfs")) {
                File(externalDir, reserved).mkdirs()
            }
            expectCode(RelayInnerError.NOT_FOUND) { workspace.stat("conductor/c.md") }
            expectCode(RelayInnerError.NOT_FOUND) { workspace.stat(".runtime/trace.md") }
            expectCode(RelayInnerError.NOT_FOUND) { workspace.stat("appfiles/x") }
            val root = workspace.list("").map { it.name }
            assertTrue("file.txt" in root)
            assertTrue("sub" in root)
            assertFalse(root.any { it in setOf("conductor", ".runtime", "appfiles", "appdata", "selected", "vfs") })

            val link = File(externalDir, "link")
            if (runCatching { Files.createSymbolicLink(link.toPath(), File(externalDir, "conductor").toPath()) }.isSuccess) {
                expectCode(RelayInnerError.NOT_FOUND) { workspace.stat("link/c.md") }
            }
        }

    @Test
    fun rootListFollowsGrantedRootsAndExcludesSelectedFromEverything() =
        runBlocking {
            val everything = source(RelayScope.everything())
            val roots = everything.list("/", null, 100).entries.map { it.path }
            assertTrue("/appfiles" in roots)
            assertTrue("/appdata/activity" in roots)
            assertTrue("/file.txt" in roots)
            assertFalse("/selected" in roots)
            assertFalse(roots.any { it.startsWith("/conductor") || it.startsWith("/.runtime") })
            assertFalse(RelayScope.everything().contains(RelayScope.USER_SELECTED))
            assertTrue(RelayScope.everything().none { it.wire.startsWith("conductor.") })
        }

    @Test
    fun listIsBytewiseSortedAndPaginatesWithOpaqueCursor() =
        runBlocking {
            val files = source(setOf(RelayScope.APP_FILES))
            val first = files.list("/appfiles", null, 2)
            assertEquals(listOf("/appfiles/a.txt", "/appfiles/activity"), first.entries.map { it.path })
            assertNotNull(first.nextCursor)
            val second = files.list("/appfiles", first.nextCursor, 2)
            assertEquals(listOf("/appfiles/b.txt", "/appfiles/c.txt"), second.entries.map { it.path })
            assertNotNull(second.nextCursor)
            val third = files.list("/appfiles", second.nextCursor, 2)
            assertEquals(listOf("/appfiles/logs", "/appfiles/telemetry"), third.entries.map { it.path })
            assertNull(third.nextCursor)

            expectCode(RelayInnerError.CURSOR_INVALID) {
                files.list("/appfiles", first.nextCursor!!.dropLast(1) + "A", 2)
            }
            expectCode(RelayInnerError.CURSOR_INVALID) {
                files.list("/appfiles", first.nextCursor, 5)
            }
        }

    @Test
    fun readHonoursEtagAndRange() =
        runBlocking {
            val files = source(setOf(RelayScope.APP_FILES))
            val entry = files.stat("/appfiles/a.txt")
            assertNotNull(entry.etag)
            val read = files.read("/appfiles/a.txt", 0, 5, entry.etag)
            assertEquals("alpha", String(read.data, Charsets.UTF_8))
            assertTrue(read.eof)
            expectCode(RelayInnerError.BAD_REQUEST) {
                files.read("/appfiles/a.txt", 0, 5, "\"stale\"")
            }
            expectCode(RelayInnerError.RANGE_NOT_SATISFIABLE) {
                files.read("/appfiles/a.txt", 100, 5, null)
            }
        }

    @Test
    fun appDataLogsAreRedactedAndDiagnosticsExists() =
        runBlocking {
            val source = source(setOf(RelayScope.APP_DATA_LOGS, RelayScope.APP_DATA_DIAGNOSTICS))
            val log = source.read("/appdata/logs/app.log", 0, 4096, null)
            val text = String(log.data, Charsets.UTF_8)
            assertFalse(text.contains("supersecret"))
            assertFalse(text.contains("https://"))
            assertTrue(text.contains("INFO"))
            assertTrue(text.contains("ERROR"))
            val diagnostics = source.read("/appdata/diagnostics/index.json", 0, 4096, null)
            assertTrue(String(diagnostics.data, Charsets.UTF_8).contains("app_version"))
            expectCode(RelayInnerError.NOT_FOUND) {
                source.read("/appdata/diagnostics/other.json", 0, 1, null)
            }
        }

    @Test
    fun selectedScopeListsPickedFilesAndGrantChecksTheScope() =
        runBlocking {
            val selectedOnly = source(setOf(RelayScope.USER_SELECTED))
            val entries = selectedOnly.list("/selected", null, 100).entries
            assertEquals(1, entries.size)
            assertTrue(entries.single().name.endsWith("-picked.txt"))
            val read = selectedOnly.read(entries.single().path, 0, 6, entries.single().etag)
            assertEquals("picked", String(read.data, Charsets.UTF_8))
            val without = source(setOf(RelayScope.APP_FILES))
            expectCode(RelayInnerError.NOT_FOUND) { without.stat("/selected") }
        }

    private suspend fun expectCode(
        code: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (error: RelaySourceException) {
            assertEquals(code, error.code)
            return
        } catch (error: VfsException) {
            assertEquals(code, error.code)
            return
        }
        throw AssertionError("expected $code")
    }
}
