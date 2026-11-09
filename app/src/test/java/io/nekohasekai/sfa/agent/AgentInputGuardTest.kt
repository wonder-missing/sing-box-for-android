package io.nekohasekai.sfa.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInputGuardTest {
    @Test
    fun allowsNormalNaturalLanguage() {
        assertTrue(DefaultAgentInputGuard.check("probe https://example.com/health") is InputGuardResult.Allowed)
        assertTrue(DefaultAgentInputGuard.check("show me the current nodes") is InputGuardResult.Allowed)
    }

    @Test
    fun blocksShareLinks() {
        assertTrue(
            DefaultAgentInputGuard.check("use ss://YWVzLTI1Ni1nY206c2VjcmV0@example.com:8388") is
                InputGuardResult.Blocked,
        )
    }

    @Test
    fun blocksCredentials() {
        assertTrue(
            DefaultAgentInputGuard.check("token=SECRET") is InputGuardResult.Blocked,
        )
        assertTrue(
            DefaultAgentInputGuard.check("Authorization: Bearer SECRET") is InputGuardResult.Blocked,
        )
        assertTrue(
            DefaultAgentInputGuard.check("https://user:pass@example.com/") is InputGuardResult.Blocked,
        )
    }

    @Test
    fun blocksLogLikeText() {
        assertTrue(
            DefaultAgentInputGuard.check("time=2026-11-10 level=error msg=connection failed") is
                InputGuardResult.Blocked,
        )
    }
}
