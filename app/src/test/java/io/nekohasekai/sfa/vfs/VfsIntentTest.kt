package io.nekohasekai.sfa.vfs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsIntentTest {
    @Test
    fun theReportedWholeWorkspacePhraseMatches() {
        val text =
            "你把咱们这个 APP 的整个工作目录都挂到 Portal 上去，" +
                "前面让你挂，你只挂了 Conductor 一个子目录。"

        assertTrue(isWholeWorkspaceRequest(text))
    }

    @Test
    fun aNamedSubsetStaysNarrow() {
        assertFalse(isWholeWorkspaceRequest("把 Conductor 目录挂到 Portal 上"))
        assertFalse(isWholeWorkspaceRequest("把 API trace 挂到 Portal 上"))
    }
}
