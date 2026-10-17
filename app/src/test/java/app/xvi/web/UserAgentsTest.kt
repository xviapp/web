package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UserAgentsTest {
    @Test fun desktopFollowsTheWebViewChromeMajorVersion() {
        val mobile = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP3A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"
        val desktop = UserAgents.desktop(mobile)
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36", desktop)
        assertFalse(desktop.contains("Mobile"))
        assertFalse(desktop.contains("wv"))
    }

    @Test fun unknownUserAgentFallsBackToAFixedVersion() {
        assertEquals(true, UserAgents.desktop(null).contains("Chrome/130.0.0.0"))
        assertEquals(true, UserAgents.desktop("Custom/1.0").contains("Chrome/130.0.0.0"))
    }
}
