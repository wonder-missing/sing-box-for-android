package io.nekohasekai.sfa.compose.component

import io.nekohasekai.sfa.vfs.VfsScope
import org.junit.Assert.assertEquals
import org.junit.Test

class VfsScopeChipsTest {
    @Test
    fun `empty scope has no chips`() {
        val chips = vfsScopeChips(emptySet())
        assertEquals(emptyList<VfsScope>(), chips.visible)
        assertEquals(0, chips.hiddenCount)
    }

    @Test
    fun `chips follow the canonical consent order`() {
        val chips = vfsScopeChips(setOf(VfsScope.APPFILES, VfsScope.CONDUCTOR, VfsScope.WORKDIR))
        assertEquals(listOf(VfsScope.CONDUCTOR, VfsScope.WORKDIR, VfsScope.APPFILES), chips.visible)
        assertEquals(0, chips.hiddenCount)
    }

    @Test
    fun `default cap collapses the remaining scopes into a hidden count`() {
        val chips = vfsScopeChips(VfsScope.entries.toSet())
        assertEquals(VfsScope.entries.take(VFS_SCOPE_CHIP_LIMIT), chips.visible)
        assertEquals(VfsScope.entries.size - VFS_SCOPE_CHIP_LIMIT, chips.hiddenCount)
    }

    @Test
    fun `a larger limit shows every scope`() {
        val chips = vfsScopeChips(VfsScope.entries.toSet(), limit = VfsScope.entries.size)
        assertEquals(VfsScope.entries.toList(), chips.visible)
        assertEquals(0, chips.hiddenCount)
    }
}
