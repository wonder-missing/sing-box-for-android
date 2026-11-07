package io.nekohasekai.sfa.compose.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupLayoutTest {
    private fun item(tag: String) = GroupItem(tag, "type", "Type", 0L, 0)

    private val items = listOf(item("a"), item("b"), item("c"))

    @Test
    fun `two columns chunks pairs`() {
        assertEquals(listOf(listOf("a", "b"), listOf("c")), GroupLayout.rows(items, singleColumn = false).map { row -> row.map { it.tag } })
        assertEquals(2, GroupLayout.columns(singleColumn = false))
    }

    @Test
    fun `single column keeps one item per row`() {
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), GroupLayout.rows(items, singleColumn = true).map { row -> row.map { it.tag } })
        assertEquals(1, GroupLayout.columns(singleColumn = true))
    }

    @Test
    fun `empty group has no rows`() {
        assertTrue(GroupLayout.rows(emptyList(), singleColumn = false).isEmpty())
        assertTrue(GroupLayout.rows(emptyList(), singleColumn = true).isEmpty())
    }
}
