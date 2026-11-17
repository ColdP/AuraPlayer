package btm.m.auraplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSearchSettingsTest {
    @Test
    fun upgradesExistingQqSearchPreferenceWhileAllowingIndependentSelection() {
        assertEquals("qq", AuraSettings.normalizeCloudSearchSource(null, "qq"))
        assertEquals("netease", AuraSettings.normalizeCloudSearchSource(null, "all"))
        assertEquals("netease", AuraSettings.normalizeCloudSearchSource("netease", "qq"))
        assertEquals("qq", AuraSettings.normalizeCloudSearchSource("qq", "netease"))
        assertEquals("netease", AuraSettings.normalizeCloudSearchSource("unsupported", "qq"))
    }
}
