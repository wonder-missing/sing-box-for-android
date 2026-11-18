package io.nekohasekai.sfa.compose.component

import io.nekohasekai.sfa.vfs.VfsScope
import org.junit.Assert.assertEquals
import org.junit.Test

class VfsScopeOrderTest {
    @Test
    fun `empty scope has no chips`() {
        assertEquals(emptyList<VfsScope>(), vfsOrderedScopes(emptySet()))
    }

    @Test
    fun `chips follow the canonical consent order`() {
        val scopes = vfsOrderedScopes(setOf(VfsScope.APPFILES, VfsScope.CONDUCTOR, VfsScope.WORKDIR))
        assertEquals(listOf(VfsScope.CONDUCTOR, VfsScope.WORKDIR, VfsScope.APPFILES), scopes)
    }

    @Test
    fun `all scopes stay visible for horizontal scrolling`() {
        assertEquals(VfsScope.entries.toList(), vfsOrderedScopes(VfsScope.entries.toSet()))
    }
}
