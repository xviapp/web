package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class LocalAndHistoryTest {
    @Test fun localAndPrivateAddressesAreDirect() {
        listOf("localhost", "dev.localhost", "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.1.1", "100.64.0.1", "router", "nas.local", "printer.lan", "box.home.arpa", "[::1]", "fd00::1", "fe80::1").forEach {
            assertTrue(it, LocalAddress.isLocal(it))
        }
        listOf("example.com", "8.8.8.8", "172.32.0.1", "192.169.0.1", "100.128.0.1", "2001:db8::1", "", null).forEach {
            assertFalse(it.toString(), LocalAddress.isLocal(it))
        }
    }

    @Test fun historyIsGroupedByCalendarDay() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val now = 1_791_640_000_000L // 2026-10-10 evening in Shanghai
        val today = HistoryDays.startOfDay(now, zone)
        val hour = 60 * 60 * 1000L
        val entries = listOf(
            PageEntry("https://a.example", "a", now - hour),
            PageEntry("https://b.example", "b", today + hour),
            PageEntry("https://c.example", "c", today - hour),
            PageEntry("https://d.example", "d", today - 3 * 24 * hour)
        )
        val rows = HistoryDays.group(entries, now, { "day${it.daysAgo}" }, zone)
        assertEquals(listOf("day0", entries[0], entries[1], "day1", entries[2], "day3", entries[3]), rows)
    }

    @Test fun siteSettingsKeepOnlyWhatWasSet() {
        val setting = SiteSetting(desktop = true, textZoom = 130, images = false)
        assertEquals(mapOf("desktop" to 1, "textZoom" to 130, "images" to 0), setting.toValues())
        assertEquals(setting, SiteSetting.fromValues(setting.toValues()))
        assertTrue(SiteSetting().isEmpty)
        assertEquals(SiteSetting(), SiteSetting.fromValues(mapOf("textZoom" to 9999)))
    }
}
