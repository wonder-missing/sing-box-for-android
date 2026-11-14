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
    fun naturalWholeWorkspaceVariantsMatch() {
        listOf(
            "把工作目录挂上",
            "整个 APP 的工作目录",
            "工作目录全部挂上",
            "whole working directory",
            "挂到 Portal 上",
            "connect the VFS",
            "整个工作目录里的 trace 也挂上",
        ).forEach { text ->
            assertTrue(text, isWholeWorkspaceRequest(text))
        }
    }

    @Test
    fun namedSubsetsStayNarrow() {
        listOf(
            "把 trace 挂上",
            "只挂 conductor",
            "只要对话",
            "mount only the system prompt",
            "把工作目录里的 trace 挂上",
            "所有 trace 都挂上",
            "全部 trace 都挂上",
            "这个 link 有问题",
        ).forEach { text ->
            assertFalse(text, isWholeWorkspaceRequest(text))
        }
    }

    @Test
    fun aNamedSubsetStaysNarrow() {
        assertFalse(isWholeWorkspaceRequest("把 Conductor 目录挂到 Portal 上"))
        assertFalse(isWholeWorkspaceRequest("把 API trace 挂到 Portal 上"))
    }
}
