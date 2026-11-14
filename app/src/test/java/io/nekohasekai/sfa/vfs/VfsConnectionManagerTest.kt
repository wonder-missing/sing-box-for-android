package io.nekohasekai.sfa.vfs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsConnectionManagerTest {
    private var now = 1_000L
    private val issuer = "https://example.com"
    private val link = VfsLink(issuer, "link_1", setOf(VfsScope.CONDUCTOR, VfsScope.TRACE))
    private val target = VfsServerTarget("Portal", "wss://example.com/vfs/provider", "mcp-token")

    private class FakeConsent(
        var decision: VfsConsentDecision = VfsConsentDecision.ONCE,
    ) : VfsConsentPort {
        val requests = mutableListOf<VfsConsentRequest>()

        override suspend fun request(request: VfsConsentRequest): VfsConsentDecision {
            requests += request
            return decision
        }
    }

    private class FakeSession(
        override val hello: VfsServerHello,
    ) : VfsProviderSession {
        var closedCode: Int? = null
        var closedReason: String? = null

        override fun close(
            code: Int,
            reason: String,
        ) {
            closedCode = code
            closedReason = reason
        }
    }

    private class FakeConnector : VfsProviderConnector {
        val requests = mutableListOf<VfsProviderConnectRequest>()
        val sessions = mutableListOf<FakeSession>()
        var hello = VfsServerHello("vfsd", "resume-1", 5_000L)
        var error: VfsConnectException? = null

        override suspend fun connect(request: VfsProviderConnectRequest): VfsProviderSession {
            requests += request
            error?.let { throw it }
            return FakeSession(hello).also { sessions += it }
        }
    }

    private fun manager(
        connector: FakeConnector,
        consent: FakeConsent = FakeConsent(),
        trust: VfsTrustStore = VfsTrustStore(now = { now }, inactivityMs = 1_000L),
        resumes: VfsResumeTokenStore = InMemoryVfsResumeTokenStore(),
        foreground: Boolean = true,
        resolver: suspend (String) -> VfsServerTarget? = { target },
    ): VfsConnectionManager =
        VfsConnectionManager(
            resolveServer = resolver,
            consent = consent,
            connector = connector,
            deviceId = { "device-1" },
            resumeTokens = resumes,
            trust = trust,
            cooldowns =
                VfsCooldownStore(
                    factory = { VfsCooldown(now = { now }) },
                ),
            now = { now },
            isForeground = { foreground },
        )

    @Test
    fun firstConnectionStoresTheHelloResumeTokenAndExpiry() =
        runBlocking {
            val connector = FakeConnector()
            val resumes = InMemoryVfsResumeTokenStore()
            val manager = manager(connector, resumes = resumes)

            val result = manager.connect(link)

            assertEquals(null, connector.requests.single().resumeToken)
            assertEquals("device-1", connector.requests.single().deviceId)
            assertEquals(link.scope, connector.requests.single().scope)
            assertEquals(5_000L, result.expiresAt)
            assertEquals("resume-1", resumes.get(issuer, link.linkId))
            assertTrue(manager.state.value is VfsConnectionState.Connected)
        }

    @Test
    fun activeConnectionWithoutResumeDoesNotReplaceTheOldSession() =
        runBlocking {
            val connector = FakeConnector()
            val resumes = InMemoryVfsResumeTokenStore()
            val manager = manager(connector, resumes = resumes)

            manager.connect(link)
            resumes.remove(issuer, link.linkId)
            val result = manager.connect(link)

            assertEquals(1, connector.requests.size)
            assertEquals(null, connector.sessions.single().closedCode)
            assertEquals(link.scope, result.scope)
        }

    @Test
    fun validResumeReplacesTheOldSessionWith4004() =
        runBlocking {
            val connector = FakeConnector()
            val manager = manager(connector)

            manager.connect(link)
            connector.hello = VfsServerHello("vfsd", "resume-2", 6_000L)
            val result = manager.connect(link)

            assertEquals("resume-1", connector.requests.last().resumeToken)
            assertEquals(4004, connector.sessions.first().closedCode)
            assertEquals("replaced", connector.sessions.first().closedReason)
            assertEquals(6_000L, result.expiresAt)
        }

    @Test
    fun missingResumeTokenInHelloDoesNotClearTheStoredValue() =
        runBlocking {
            val connector = FakeConnector()
            val resumes = InMemoryVfsResumeTokenStore()
            val manager = manager(connector, resumes = resumes)

            manager.connect(link)
            connector.hello = VfsServerHello("vfsd", null, 6_000L)
            manager.connect(link)

            assertEquals("resume-1", resumes.get(issuer, link.linkId))
        }

    @Test
    fun unknownIssuerIsRejectedBeforeConsent() =
        runBlocking {
            val connector = FakeConnector()
            val consent = FakeConsent()
            val manager = manager(connector, consent, resolver = { null })

            val error = failure { manager.connect(link) }

            assertEquals("VFS_ISSUER_UNKNOWN", error.code)
            assertTrue(consent.requests.isEmpty())
            assertTrue(connector.requests.isEmpty())
        }

    @Test
    fun foregroundIsRequiredBeforeConsent() =
        runBlocking {
            val connector = FakeConnector()
            val consent = FakeConsent()
            val manager = manager(connector, consent, foreground = false)

            val error = failure { manager.connect(link) }

            assertEquals("VFS_CONFIRMATION_REQUIRED", error.code)
            assertTrue(consent.requests.isEmpty())
            assertTrue(connector.requests.isEmpty())
        }

    @Test
    fun denialStartsTheCooldownAndTheNextCallReportsIt() =
        runBlocking {
            val connector = FakeConnector()
            val consent = FakeConsent(VfsConsentDecision.DENY)
            val manager = manager(connector, consent)

            val denied = failure { manager.connect(link) }
            assertEquals("VFS_USER_DENIED", denied.code)
            assertEquals(30_000L, denied.retryAfterMs)

            val cooling = failure { manager.connect(link) }
            assertEquals("VFS_CONFIRMATION_COOLDOWN", cooling.code)
            assertTrue((cooling.retryAfterMs ?: 0L) > 0L)
            assertEquals(1, consent.requests.size)
            assertTrue(connector.requests.isEmpty())
        }

    @Test
    fun trustedServerSkipsTheConsentDialog() =
        runBlocking {
            val connector = FakeConnector()
            val consent = FakeConsent()
            val trust = VfsTrustStore(now = { now }, inactivityMs = 1_000L)
            trust.grant(
                VfsTrust(
                    issuer = issuer,
                    scope = link.scope,
                    lastUsedAt = now,
                    expiresAt = now + 1_000L,
                    processScoped = false,
                ),
            )
            val manager = manager(connector, consent, trust)

            manager.connect(link)

            assertTrue(consent.requests.isEmpty())
            assertTrue(connector.requests.isNotEmpty())
        }

    @Test
    fun terminalErrorClearsResumeAndTrust() =
        runBlocking {
            val connector = FakeConnector()
            val resumes = InMemoryVfsResumeTokenStore()
            val trust = VfsTrustStore(now = { now }, inactivityMs = 1_000L)
            val manager = manager(connector, resumes = resumes, trust = trust)

            manager.connect(link)
            assertNotNull(resumes.get(issuer, link.linkId))
            assertNotNull(trust.find(issuer, link.scope))

            connector.error = VfsConnectException("VFS_UNAUTHORIZED", "token rotated")
            val error = failure { manager.connect(link) }

            assertEquals("VFS_UNAUTHORIZED", error.code)
            assertNull(resumes.get(issuer, link.linkId))
            assertNull(trust.find(issuer, link.scope))
        }

    private suspend fun failure(block: suspend () -> Unit): VfsConnectException {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected VfsConnectException, got $error", error is VfsConnectException)
        return error as VfsConnectException
    }
}
