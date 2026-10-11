package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
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

    /** Only numeric addresses are parsed, so a name that looks numeric must not resolve either. */
    @Test fun localCoverageStopsAtTheDocumentedRanges() {
        // Boundary addresses of the shared (100.64/10) and private (172.16/12) ranges.
        listOf("100.64.0.0", "100.127.255.255", "172.16.0.0", "172.31.255.255", "fc00::1", "fdff::1", "fe80::abcd").forEach {
            assertTrue(it, LocalAddress.isLocal(it))
        }
        // One step outside each of them, plus addresses that must never be treated as local.
        listOf("100.63.255.255", "100.128.0.0", "172.15.255.255", "172.32.0.0", "fb00::1", "fec0::1",
            "0.0.0.0", "255.255.255.255", "169.253.0.1", "localhost.example.com", "example.localhost.com").forEach {
            assertFalse(it, LocalAddress.isLocal(it))
        }
    }

    @Test fun zoneSuffixesAndBracketsAreStrippedBeforeMatching() {
        assertTrue(LocalAddress.isLocal("[fe80::1%wlan0]"))
        assertTrue(LocalAddress.isLocal("fe80::1%wlan0"))
        assertTrue(LocalAddress.isLocal("[::1]"))
        assertTrue(LocalAddress.isLocal("  LOCALHOST  "))
        assertTrue(LocalAddress.isLocal("nas.LAN."))
        assertFalse(LocalAddress.isLocal("[2001:db8::1%wlan0]"))
    }

    /** History is grouped by calendar day, which is not the same as a 24-hour period. */
    @Test fun theDayAfterAClockChangeIsStillOneDayAgo() {
        val newYork = TimeZone.getTimeZone("America/New_York")
        fun at(text: String) = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = newYork }.parse(text)!!.time

        // 2026-11-01 is 25 hours long in New York, so the previous midnight is not 24 hours back.
        val fallBack = at("2026-11-01 09:00")
        assertEquals(1, HistoryDays.day(at("2026-10-31 23:30"), fallBack, newYork).daysAgo)
        assertEquals(0, HistoryDays.day(at("2026-11-01 01:30"), fallBack, newYork).daysAgo)
        val entries = listOf(
            PageEntry("https://a.example", "a", at("2026-11-01 08:00")),
            PageEntry("https://b.example", "b", at("2026-11-01 00:30")),
            PageEntry("https://c.example", "c", at("2026-10-31 23:00")),
            PageEntry("https://d.example", "d", at("2026-10-31 01:00"))
        )
        assertEquals(listOf("day0", entries[0], entries[1], "day1", entries[2], entries[3]),
            HistoryDays.group(entries, fallBack, { "day${it.daysAgo}" }, newYork))

        // 2026-03-08 is 23 hours long: the same problem in the other direction.
        val springForward = at("2026-03-08 09:00")
        assertEquals(1, HistoryDays.day(at("2026-03-07 22:00"), springForward, newYork).daysAgo)
    }

    @Test fun daysAgoCountsAcrossAYearBoundary() {
        val newYork = TimeZone.getTimeZone("America/New_York")
        fun at(text: String) = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = newYork }.parse(text)!!.time
        val now = at("2027-01-01 10:00")
        assertEquals(1, HistoryDays.day(at("2026-12-31 10:00"), now, newYork).daysAgo)
        assertEquals(365, HistoryDays.day(at("2026-01-01 10:00"), now, newYork).daysAgo)
        assertFalse(HistoryDays.day(at("2026-12-31 10:00"), now, newYork).sameYear)
        assertTrue(HistoryDays.day(at("2027-01-01 00:01"), now, newYork).sameYear)
    }
}
