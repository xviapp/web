package app.xvi.web

import org.junit.Assert.*
import org.junit.Test

class ProfileRegistryTest {
    private val two = ProfileRegistry().create("work", "工作")!!

    @Test fun startsWithOnlyTheMainProfile() {
        val fresh = ProfileRegistry.decode(null)
        assertEquals(listOf(BrowserProfile.MAIN), fresh.profiles.map { it.id })
        assertEquals(BrowserProfile.MAIN, fresh.active)
        assertEquals(BrowserProfile.MAIN, fresh.preferred)
    }

    @Test fun mainProfileUsesWebViewDefaultAndOthersTheirOwn() {
        assertNull(BrowserProfile(BrowserProfile.MAIN).webProfile)
        assertEquals("xvi-profile-work", BrowserProfile("work", "工作").webProfile)
    }

    @Test fun refusesBlankNamesTakenAndUnsafeIds() {
        assertNull(ProfileRegistry().create("a", "   "))
        assertNull(two.create("work", "别的"))
        assertNull(ProfileRegistry().create("../main", "逃逸"))
        assertNull(two.rename("work", " "))
        assertNull(two.rename("missing", "名字"))
    }

    @Test fun trimsAndLimitsNames() {
        val name = two.create("long", "  " + "字".repeat(100))!!.find("long")!!.name
        assertEquals(BrowserProfile.MAX_NAME, name.length)
    }

    @Test fun activeAndPreferredOnlyAcceptKnownProfiles() {
        assertEquals(BrowserProfile.MAIN, two.withActive("missing").active)
        assertEquals("work", two.withActive("work").active)
        assertEquals("work", two.withPreferred("work").preferred)
    }

    @Test fun mainAndShownProfilesCannotBeRemoved() {
        assertNull(two.remove(BrowserProfile.MAIN))
        assertNull(two.withActive("work").remove("work"))
        assertNull(two.remove("missing"))
    }

    @Test fun removingThePreferredProfileHandsOverToMain() {
        val removed = two.withPreferred("work").remove("work")!!
        assertEquals(BrowserProfile.MAIN, removed.preferred)
        assertNull(removed.find("work"))
        assertEquals(setOf("work"), removed.removing)
    }

    @Test fun survivesSaving() {
        val saved = two.create("home", "家")!!.withActive("work").withPreferred("home").remove("home")!!
        assertEquals(saved, ProfileRegistry.decode(saved.encode()))
    }

    @Test fun damagedTextFallsBackToMain() {
        assertEquals(ProfileRegistry(), ProfileRegistry.decode("not json"))
        val odd = ProfileRegistry.decode("""{"active":"gone","preferred":"../x","removing":["main","../y","old"],
            "profiles":[{"id":"../up","name":"坏"},{"id":"blank","name":" "},{"id":"ok","name":"好"},{"id":"ok","name":"重复"}]}""")
        assertEquals(listOf(BrowserProfile.MAIN, "ok"), odd.profiles.map { it.id })
        assertEquals(BrowserProfile.MAIN, odd.active)
        assertEquals(BrowserProfile.MAIN, odd.preferred)
        assertEquals(setOf("old"), odd.removing)
    }
}
