package io.nekohasekai.sfa.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseFeedTest {
    private val feed =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <id>tag:github.com,2008:Repository/1/v1.15.0-alpha.13-wonder.10</id>
            <updated>2026-11-07T10:00:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/wonder-missing/sing-box-for-android/releases/tag/v1.15.0-alpha.13-wonder.10"/>
            <title>v1.15.0-alpha.13-wonder.10</title>
            <content type="html">&lt;p&gt;Wonder fork.&lt;br&gt;Signed with the repository signing key.&lt;/p&gt;</content>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/1/v1.15.0-alpha.13-wonder.9</id>
            <updated>2026-11-07T09:00:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/wonder-missing/sing-box-for-android/releases/tag/v1.15.0-alpha.13-wonder.9"/>
            <title>v1.15.0-alpha.13-wonder.9 (debug-signed)</title>
            <content type="html">&lt;p&gt;debug-signed&lt;/p&gt;</content>
          </entry>
        </feed>
        """.trimIndent()

    private val assetsPage =
        """
        <div>
          <ul>
            <li>
              <a href="/wonder-missing/sing-box-for-android/releases/download/v1.15.0-alpha.13-wonder.10/SFA-1.15.0-alpha.13-wonder.10-arm64-v8a.apk" rel="nofollow">
                <span class="text-bold">SFA-1.15.0-alpha.13-wonder.10-arm64-v8a.apk</span>
              </a>
              <span>sha256:df81a033</span>
              <span>34.7 MB</span>
            </li>
            <li>
              <a href="https://github.com/wonder-missing/sing-box-for-android/releases/download/v1.15.0-alpha.13-wonder.10/SFA-1.15.0-alpha.13-wonder.10-legacy-android-5-arm64-v8a.apk">legacy</a>
              <span>20.1 MB</span>
            </li>
            <li>
              <a href="/wonder-missing/sing-box-for-android/releases/download/v1.15.0-alpha.13-wonder.10/checksums.txt">checksums</a>
            </li>
          </ul>
        </div>
        """.trimIndent()

    @Test
    fun `parses atom entries`() {
        val entries = GitHubReleaseFeed.parseFeed(feed)

        assertEquals(2, entries.size)
        assertEquals("v1.15.0-alpha.13-wonder.10", entries[0].tag)
        assertEquals(
            "https://github.com/wonder-missing/sing-box-for-android/releases/tag/v1.15.0-alpha.13-wonder.10",
            entries[0].releaseUrl,
        )
        assertEquals("Wonder fork.\nSigned with the repository signing key.", entries[0].notes)
        assertEquals("v1.15.0-alpha.13-wonder.9", entries[1].tag)
        assertEquals("debug-signed", entries[1].notes)
    }

    @Test
    fun `parses apk assets and sizes`() {
        val assets = GitHubReleaseFeed.parseAssets(assetsPage)

        assertEquals(2, assets.size)
        assertEquals("SFA-1.15.0-alpha.13-wonder.10-arm64-v8a.apk", assets[0].name)
        assertEquals(
            "https://github.com/wonder-missing/sing-box-for-android/releases/download/v1.15.0-alpha.13-wonder.10/SFA-1.15.0-alpha.13-wonder.10-arm64-v8a.apk",
            assets[0].downloadUrl,
        )
        assertEquals((34.7 * 1024 * 1024).toLong(), assets[0].size)
        assertEquals("SFA-1.15.0-alpha.13-wonder.10-legacy-android-5-arm64-v8a.apk", assets[1].name)
    }

    @Test
    fun `selects arm64 apk and skips legacy build`() {
        val assets = GitHubReleaseFeed.parseAssets(assetsPage)

        val modern = GitHubReleaseFeed.selectApk(assets, listOf("arm64-v8a"), legacy = false)
        assertEquals("SFA-1.15.0-alpha.13-wonder.10-arm64-v8a.apk", modern?.name)

        val legacy = GitHubReleaseFeed.selectApk(assets, listOf("arm64-v8a"), legacy = true)
        assertEquals("SFA-1.15.0-alpha.13-wonder.10-legacy-android-5-arm64-v8a.apk", legacy?.name)

        assertNull(GitHubReleaseFeed.selectApk(assets, listOf("x86"), legacy = false))
    }

    @Test
    fun `computes wonder version codes`() {
        assertEquals(10, GitHubReleaseFeed.wonderBuild("v1.15.0-alpha.13-wonder.10"))
        assertEquals(11_404_010, GitHubReleaseFeed.versionCodeOf("v1.15.0-alpha.13-wonder.10"))
        assertEquals(0, GitHubReleaseFeed.versionCodeOf("v1.15.0-alpha.13"))
        assertTrue(GitHubReleaseFeed.matchesApk("SFA-x-arm64-v8a.apk", "arm64-v8a", legacy = false))
    }
}
