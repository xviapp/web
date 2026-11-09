package app.xvi.web

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class SiteDataTest {
    @Test fun deletionCoversTheRegistrableDomainAcrossSubdomainsAndPorts() {
        assertEquals("nodeseek.com", SiteData.domain("https://www.nodeseek.com/signIn.html"))
        assertEquals("example.co.uk", SiteData.domain("https://account.shop.example.co.uk:8443/login"))
        assertEquals("user.github.io", SiteData.domain("https://www.user.github.io/"))
        assertNotEquals(SiteData.domain("https://one.github.io"), SiteData.domain("https://two.github.io"))
    }

    @Test fun localHostsStayExactAndNonWebAddressesCannotBeCleared() {
        assertEquals("127.0.0.1", SiteData.domain("http://127.0.0.1:8080/test"))
        assertEquals("::1", SiteData.domain("http://[::1]:8080/test"))
        assertEquals("localhost", SiteData.domain("http://localhost/test"))
        assertNull(SiteData.domain("file:///data/test.html"))
        assertNull(SiteData.domain("about:blank"))
        assertNull(SiteData.domain(""))
    }
}
