package io.nekohasekai.sfa.conductor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConductorInputEditTest {
    @Test
    fun `slash inserts at the start and parks the cursor after it`() {
        val edit = ConductorInputEdits.slash("hello", maxChars = 8000)

        assertEquals("/hello", edit.text)
        assertEquals(1, edit.cursor)
    }

    @Test
    fun `slash on an empty field leaves the cursor after the slash`() {
        val edit = ConductorInputEdits.slash("", maxChars = 8000)

        assertEquals("/", edit.text)
        assertEquals(1, edit.cursor)
    }

    @Test
    fun `slash does not insert twice`() {
        val edit = ConductorInputEdits.slash("/help", maxChars = 8000)

        assertEquals("/help", edit.text)
        assertNull(edit.cursor)
    }

    @Test
    fun `mention appends at the end with a separating space`() {
        val edit = ConductorInputEdits.mention("hello", maxChars = 8000)

        assertEquals("hello @", edit.text)
        assertEquals(edit.text.length, edit.cursor)
    }

    @Test
    fun `mention does not add a second space`() {
        val edit = ConductorInputEdits.mention("hello ", maxChars = 8000)

        assertEquals("hello @", edit.text)
        assertEquals(edit.text.length, edit.cursor)
    }

    @Test
    fun `mention replaces a blank field`() {
        val edit = ConductorInputEdits.mention("  ", maxChars = 8000)

        assertEquals("@", edit.text)
        assertEquals(1, edit.cursor)
    }
}
