package io.nekohasekai.sfa.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppLogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `writes and reloads entries`() {
        val file = temporaryFolder.newFile("app.log")
        AppLog.initialize(file)

        AppLog.info("App started")
        AppLog.warning("Profile update failed")

        assertEquals(
            listOf("App started", "Profile update failed"),
            AppLog.entries().map { it.message },
        )
        assertEquals(
            listOf(AppLogLevel.Info, AppLogLevel.Warning),
            AppLog.entries().map { it.level },
        )

        AppLog.initialize(file)
        assertEquals(
            listOf("App started", "Profile update failed"),
            AppLog.entries().map { it.message },
        )
    }

    @Test
    fun `clear removes memory and file contents`() {
        val file = temporaryFolder.newFile("app.log")
        AppLog.initialize(file)
        AppLog.error("Something failed")

        AppLog.clear()

        assertTrue(AppLog.entries().isEmpty())
        assertEquals("", file.readText())
        AppLog.initialize(file)
        assertTrue(AppLog.entries().isEmpty())
    }

    @Test
    fun `keeps only the newest entries`() {
        val file = temporaryFolder.newFile("app.log")
        AppLog.initialize(file)

        repeat(600) { index -> AppLog.info("entry $index") }

        assertEquals(500, AppLog.entries().size)
        assertEquals("entry 599", AppLog.entries().last().message)
        assertEquals("entry 100", AppLog.entries().first().message)
    }

    @Test
    fun `ignores malformed lines`() {
        val file = temporaryFolder.newFile("app.log")
        file.writeText(
            """
            not-a-line
            123|Info|valid message
            456|Unknown|bad level
            789|Error|message with \| pipe
            """.trimIndent(),
        )

        AppLog.initialize(file)

        assertEquals(2, AppLog.entries().size)
        assertEquals("valid message", AppLog.entries().first().message)
        assertEquals("message with | pipe", AppLog.entries().last().message)
    }
}
