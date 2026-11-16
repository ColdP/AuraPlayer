// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun parseUpdateInfoReadsAuraPlayerSchema() {
        val raw = """
            {
              "VersionCode": 1000039,
              "VersionName": "1.0.0",
              "ReleaseNoteURL": "https://coldp.github.io/AuraPlayer/RELEASE_NOTES_1.0.0.md",
              "APKURL": "https://github.com/ColdP/AuraPlayer/releases/download/v1.0.0/AuraPlayer-v1.0.0.apk",
              "APKSize": 16849863
            }
        """.trimIndent()
        val parsed = AppUpdateManager.parseUpdateInfo(raw)
        assertEquals(1000039L, parsed.versionCode)
        assertEquals("1.0.0", parsed.versionName)
        assertEquals("https://coldp.github.io/AuraPlayer/RELEASE_NOTES_1.0.0.md", parsed.releaseNoteUrl)
        assertEquals("https://github.com/ColdP/AuraPlayer/releases/download/v1.0.0/AuraPlayer-v1.0.0.apk", parsed.apkUrl)
        assertEquals(16849863L, parsed.apkSize)
    }

    @Test
    fun formatApkSizeAndStripMarkdownFormatCleanly() {
        assertEquals("16.1 MB", AppUpdateManager.formatApkSize(16849863L))
        assertEquals("512 KB", AppUpdateManager.formatApkSize(512 * 1024L))
        assertEquals("500 B", AppUpdateManager.formatApkSize(500L))

        val stripped = AppUpdateManager.stripMarkdown("## Title\n- **Bold** item [link](https://example.com)")
        assertFalse(stripped.contains("##"))
        assertFalse(stripped.contains("**"))
        assertEquals("Title\nBold item link", stripped)
    }
}
