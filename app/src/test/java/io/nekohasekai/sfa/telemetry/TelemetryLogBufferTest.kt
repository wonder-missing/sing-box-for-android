package io.nekohasekai.sfa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM tests for the telemetry disk queue. No Android runtime involved: failures
 * are injected through [TelemetryFileOps].
 */
class TelemetryLogBufferTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeOps : TelemetryFileOps {
        var failOpen = false
        var failWrites = false
        var failRename = false
        var failCopy = false
        val renameCalls = AtomicInteger()
        val copyCalls = AtomicInteger()

        override fun ensureDir(dir: File): Boolean = dir.isDirectory || dir.mkdirs()

        override fun openOutput(file: File): OutputStream {
            if (failOpen) throw IOException("injected open failure")
            if (failWrites) {
                return object : OutputStream() {
                    override fun write(b: Int) {
                        throw IOException("injected disk full")
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        throw IOException("injected disk full")
                    }
                }
            }
            return file.outputStream()
        }

        override fun rename(from: File, to: File): Boolean {
            renameCalls.incrementAndGet()
            return if (failRename) false else from.renameTo(to)
        }

        override fun copy(from: File, to: File) {
            copyCalls.incrementAndGet()
            if (failCopy) throw IOException("injected copy failure")
            from.copyTo(to, overwrite = true)
        }

        override fun delete(file: File): Boolean = file.delete()
    }

    private fun pendingFile(): File = File(folder.root, "telemetry/pending.log")

    private fun reload(file: File): TelemetryLogBuffer = TelemetryLogBuffer(file)

    @Test
    fun persistFailureDoesNotThrowAndKeepsQueueDirtyForRetry() {
        val file = pendingFile()
        val ops = FakeOps().apply { failOpen = true }
        val callbacks = AtomicInteger()
        val buffer = TelemetryLogBuffer(file, ops) { callbacks.incrementAndGet() }

        buffer.append(listOf("first line"))

        assertFalse(buffer.persist())
        // The failure streak is reported once, not on every retry.
        assertFalse(buffer.persist())
        assertEquals(1, callbacks.get())
        assertEquals(listOf("first line"), buffer.peek())
        assertEquals("injected open failure", buffer.lastPersistFailure()?.message)

        // Dirty was preserved: once the disk recovers the same queue is written.
        ops.failOpen = false
        assertTrue(buffer.persist())
        assertNull(buffer.lastPersistFailure())
        assertEquals(listOf("first line"), reload(file).peek())

        // A new failure streak after a successful write is reported again.
        ops.failOpen = true
        buffer.append(listOf("second line"))
        assertFalse(buffer.persist())
        assertEquals(2, callbacks.get())
    }

    @Test
    fun throwingFailureCallbackCannotEscapePersist() {
        val file = pendingFile()
        val ops = FakeOps().apply { failOpen = true }
        val buffer = TelemetryLogBuffer(file, ops) { throw IllegalStateException("callback boom") }

        buffer.append(listOf("data"))
        assertFalse(buffer.persist())
        assertEquals(listOf("data"), buffer.peek())
    }

    @Test
    fun diskFullDuringWriteIsIsolatedAndRetried() {
        val file = pendingFile()
        val ops = FakeOps().apply { failWrites = true }
        val callbacks = AtomicInteger()
        val buffer = TelemetryLogBuffer(file, ops) { callbacks.incrementAndGet() }

        buffer.append(listOf("alpha", "beta"))
        assertFalse(buffer.persist())
        assertEquals(1, callbacks.get())
        assertEquals(listOf("alpha", "beta"), buffer.peek())

        ops.failWrites = false
        assertTrue(buffer.persist())
        assertEquals(listOf("alpha", "beta"), reload(file).peek())
    }

    @Test
    fun renameFailureFallsBackToCopy() {
        val file = pendingFile()
        val ops = FakeOps().apply { failRename = true }
        val buffer = TelemetryLogBuffer(file, ops)

        buffer.append(listOf("a", "b"))
        assertTrue(buffer.persist())

        assertTrue(ops.renameCalls.get() >= 1)
        assertEquals(1, ops.copyCalls.get())
        assertTrue(file.isFile)
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
        assertEquals(listOf("a", "b"), reload(file).peek())
    }

    @Test
    fun renameAndCopyFailureIsIsolated() {
        val file = pendingFile()
        val ops = FakeOps().apply {
            failRename = true
            failCopy = true
        }
        val buffer = TelemetryLogBuffer(file, ops)

        buffer.append(listOf("keep me"))
        assertFalse(buffer.persist())
        assertEquals(listOf("keep me"), buffer.peek())
        assertTrue(buffer.lastPersistFailure() is IOException)
    }

    @Test
    fun persistCreatesMissingParentDirectory() {
        val file = File(folder.root, "nested/telemetry/pending.log")
        val buffer = TelemetryLogBuffer(file)
        buffer.append(listOf("dir created"))
        assertTrue(buffer.persist())
        assertTrue(file.isFile)
        assertEquals(listOf("dir created"), reload(file).peek())
    }

    @Test
    fun staleTempFileIsReplaced() {
        val file = pendingFile()
        file.parentFile!!.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText("stale half-written content")

        val buffer = TelemetryLogBuffer(file)
        buffer.append(listOf("fresh"))
        assertTrue(buffer.persist())

        assertFalse(tmp.exists())
        assertEquals(listOf("fresh"), reload(file).peek())
    }

    @Test
    fun corruptFileLoadDoesNotThrow() {
        val file = pendingFile()
        file.parentFile!!.mkdirs()
        file.writeText("this-is-not-a-length-prefixed-record")

        val buffer = TelemetryLogBuffer(file)
        assertTrue(buffer.isEmpty())

        buffer.append(listOf("after corruption"))
        assertTrue(buffer.persist())
        assertEquals(listOf("after corruption"), reload(file).peek())
    }

    @Test
    fun truncatedTailKeepsValidPrefix() {
        val file = pendingFile()
        file.parentFile!!.mkdirs()
        // One complete record followed by a record whose payload is cut short.
        file.writeText("5\nhello\n100\nshort")

        val buffer = TelemetryLogBuffer(file)
        assertEquals(listOf("hello"), buffer.peek())
    }

    @Test
    fun oversizedLengthHeaderIsIgnored() {
        val file = pendingFile()
        file.parentFile!!.mkdirs()
        file.writeText("999999\npayload")

        val buffer = TelemetryLogBuffer(file)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun filePathThatIsADirectoryDoesNotThrowOnLoadOrPersist() {
        val file = pendingFile()
        file.mkdirs()
        // Keep the directory non-empty so the copy fallback cannot replace it.
        File(file, "child").writeText("x")
        assertTrue(file.isDirectory)

        val buffer = TelemetryLogBuffer(file)
        assertTrue(buffer.isEmpty())
        buffer.append(listOf("still in memory"))
        // Writing cannot replace a non-empty directory; it must fail quietly.
        assertFalse(buffer.persist())
        assertEquals(listOf("still in memory"), buffer.peek())
    }

    @Test
    fun concurrentPersistFromTwoInstancesKeepsFileParseable() {
        val file = pendingFile()
        val first = TelemetryLogBuffer(file)
        val second = TelemetryLogBuffer(file)
        first.append((0 until 100).map { "a$it" })
        second.append((0 until 100).map { "b$it" })

        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = listOf(
                pool.submit(
                    Runnable {
                        start.await()
                        repeat(50) { assertTrue(first.persist()) }
                    },
                ),
                pool.submit(
                    Runnable {
                        start.await()
                        repeat(50) { assertTrue(second.persist()) }
                    },
                ),
            )
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // Last writer wins, but the file must be one complete snapshot, never a mix.
        val lines = reload(file).peek()
        assertEquals(100, lines.size)
        assertTrue(lines.all { it.startsWith("a") } || lines.all { it.startsWith("b") })
    }

    @Test
    fun concurrentPersistDoesNotCorruptTheMainFile() {
        val file = pendingFile()
        val buffer = TelemetryLogBuffer(file)
        val threads = 8
        val perThread = 40
        val expected = Collections.synchronizedSet(mutableSetOf<String>())
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until threads).map { thread ->
                pool.submit(
                    Runnable {
                        start.await()
                        repeat(perThread) { index ->
                            val line = "thread-$thread-line-$index"
                            buffer.append(listOf(line))
                            expected.add(line)
                            assertTrue(buffer.persist())
                        }
                    },
                )
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads * perThread, buffer.size())
        assertTrue(buffer.persist())

        val reloaded = reload(file)
        assertEquals(threads * perThread, reloaded.size())
        assertEquals(expected.toSet(), reloaded.peek().toSet())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }
}
