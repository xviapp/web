package app.xvi.web

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** What a script's metadata says, where it runs, and which origins WebView hands it to. */
class UserScriptMetaTest {
    private fun script(vararg lines: String) = (listOf("// ==UserScript==") + lines.map { "// $it" } + listOf("// ==/UserScript==", "console.log(1);"))
        .joinToString("\n")
    private fun meta(vararg lines: String) = requireNotNull(UserScriptMeta.parse(script("@name Test", *lines), Locale.ROOT))

    @Test fun theBlockIsRead() {
        val code = "﻿" + script("@name   Example", "@namespace https://example.org", "@version 1.2.3", "@description Does things",
            "@match https://*.example.com/*", "@include http://old.example.net/*", "@exclude *://*/admin*", "@exclude-match https://example.com/private/*",
            "@run-at document-start", "@noframes", "@grant GM_setValue", "@grant GM_cookie", "@require https://cdn.example/lib.js",
            "@require not-a-url", "@updateURL https://example.org/x.meta.js", "@unknown whatever")
        val meta = requireNotNull(UserScriptMeta.parse(code, Locale.ROOT))
        assertEquals("Example", meta.name)
        assertEquals("https://example.org", meta.namespace)
        assertEquals("1.2.3", meta.version)
        assertEquals(listOf("https://*.example.com/*"), meta.matches)
        assertEquals(listOf("http://old.example.net/*"), meta.includes)
        assertEquals(UserScriptMeta.RunAt.START, meta.runAt)
        assertTrue(meta.noFrames)
        assertEquals(listOf("https://cdn.example/lib.js"), meta.requires)
        assertEquals("https://example.org/x.meta.js", meta.updateUrl)
        assertEquals(listOf("GM_cookie"), meta.unsupportedGrants)
        assertTrue(meta.block.startsWith("// ==UserScript==") && meta.block.endsWith("// ==/UserScript=="))
    }

    @Test fun withoutABlockOrANameThereIsNoScript() {
        assertNull(UserScriptMeta.parse("console.log(1)"))
        assertNull(UserScriptMeta.parse(script("@version 1")))
        assertNull(UserScriptMeta.parse("// ==UserScript==\n// @name Unfinished\n"))
    }

    @Test fun namesInTheReadersLanguageComeFirst() {
        val code = script("@name Plain", "@name:zh-CN 中文名", "@name:ja 日本語", "@description:zh 说明")
        assertEquals("中文名", UserScriptMeta.parse(code, Locale.SIMPLIFIED_CHINESE)?.name)
        assertEquals("说明", UserScriptMeta.parse(code, Locale.SIMPLIFIED_CHINESE)?.description)
        assertEquals("Plain", UserScriptMeta.parse(code, Locale.ENGLISH)?.name)
    }

    @Test fun runAtDefaultsToIdle() {
        assertEquals(UserScriptMeta.RunAt.IDLE, meta().runAt)
        assertEquals(UserScriptMeta.RunAt.IDLE, meta("@run-at document-body").runAt)
        assertEquals(UserScriptMeta.RunAt.END, meta("@run-at document-end").runAt)
    }

    @Test fun theSameNameAndNamespaceIsTheSameScript() {
        assertEquals(meta("@namespace a").id, meta("@namespace a", "@version 2").id)
        assertNotEquals(meta("@namespace a").id, meta("@namespace b").id)
    }

    @Test fun matchPatterns() {
        val meta = meta("@match *://*.example.com/news/*")
        assertTrue(meta.runsOn("https://example.com/news/1"))
        assertTrue(meta.runsOn("http://www.example.com/news/a?b=c"))
        assertTrue(meta.runsOn("https://a.b.example.com:8443/news/"))
        assertFalse(meta.runsOn("https://example.com/sports/1"))
        assertFalse(meta.runsOn("https://notexample.com/news/1"))
        assertFalse(meta.runsOn("https://example.com.evil.org/news/1"))
        assertFalse(meta.runsOn("ftp://example.com/news/1"))
        // The path must match from its start; the fragment does not count.
        assertFalse(meta("@match https://example.com/a").runsOn("https://example.com/b/a"))
        assertTrue(meta("@match https://example.com/a").runsOn("https://example.com/a#top"))
        assertTrue(meta("@match <all_urls>").runsOn("https://anything.org/"))
        assertTrue(meta("@match *://*/*").runsOn("http://192.168.1.1/x"))
    }

    @Test fun includesAndExcludes() {
        assertTrue(meta("@include *").runsOn("https://a.org/"))
        assertTrue(meta("@include http*://*.example.com/*").runsOn("https://m.example.com/x"))
        assertTrue(meta("@include https://example.tld/*").runsOn("https://example.co.uk/x"))
        assertTrue(meta("@include /^https://(www\\.)?example\\.org/\\d+$/").runsOn("https://www.example.org/42"))
        assertFalse(meta("@include /^https://(www\\.)?example\\.org/\\d+$/").runsOn("https://www.example.org/x"))
        val excluded = meta("@match https://example.com/*", "@exclude https://example.com/admin*", "@exclude-match https://example.com/login")
        assertTrue(excluded.runsOn("https://example.com/home"))
        assertFalse(excluded.runsOn("https://example.com/admin/users"))
        assertFalse(excluded.runsOn("https://example.com/login"))
        assertFalse(meta().runsOn("https://example.com/"))
        assertFalse(meta().hasTargets)
    }

    /** The page applies the same compiled patterns, so they must mean the same in JavaScript. */
    @Test fun patternsOnlyUseSyntaxBothEnginesShare() {
        val pattern = requireNotNull(UserScriptMeta.matchPattern("https://a+b.example.com/(x)?[y]{1}*"))
        assertFalse(pattern.source.contains("\\Q"))
        assertTrue(Regex(pattern.source).containsMatchIn("https://a+b.example.com/(x)?[y]{1}/z"))
        assertFalse(Regex(pattern.source).containsMatchIn("https://aab.example.com/x"))
    }

    @Test fun originRulesNarrowWhatPagesReceive() {
        assertEquals(setOf("https://example.com", "https://*.example.com"), meta("@match https://*.example.com/*").originRules())
        assertEquals(setOf("http://a.org", "https://a.org", "https://b.org:8443"), meta("@match *://a.org/*", "@match https://b.org:8443/x").originRules())
        assertEquals(UserScriptMeta.ALL_ORIGINS, meta("@match https://a.org/*", "@match *://*/*").originRules())
        assertEquals(UserScriptMeta.ALL_ORIGINS, meta("@match <all_urls>").originRules())
        assertEquals(UserScriptMeta.ALL_ORIGINS, meta("@include https://a.org/*").originRules())
        assertEquals(emptySet<String>(), meta("@match file:///*").originRules())
        // A rule without a port covers only the default one; local servers often use others.
        assertEquals(UserScriptMeta.ALL_ORIGINS, meta("@match http://127.0.0.1/*").originRules())
        assertEquals(UserScriptMeta.ALL_ORIGINS, meta("@match http://router.lan/*").originRules())
        assertEquals(setOf("http://192.168.1.1:8080"), meta("@match http://192.168.1.1:8080/*").originRules())
    }

    @Test fun scriptAddresses() {
        assertTrue(UserScriptMeta.isScriptUrl("https://update.greasyfork.org/scripts/1/Name.user.js"))
        assertTrue(UserScriptMeta.isScriptUrl("https://example.org/a.USER.JS?v=2"))
        assertFalse(UserScriptMeta.isScriptUrl("https://example.org/a.js"))
        assertFalse(UserScriptMeta.isScriptUrl("https://example.org/page?file=a.user.js"))
        assertFalse(UserScriptMeta.isScriptUrl("file:///sdcard/a.user.js"))
    }

    @Test fun versions() {
        assertTrue(UserScriptMeta.isNewer("1.10", "1.9"))
        assertTrue(UserScriptMeta.isNewer("2", "1.9.9"))
        assertTrue(UserScriptMeta.isNewer("2024.10.1", "2024.9.30"))
        assertFalse(UserScriptMeta.isNewer("1.0", "1"))
        assertFalse(UserScriptMeta.isNewer("1.2", "1.2"))
        assertFalse(UserScriptMeta.isNewer("1.2", "1.3"))
    }

    @Test fun storedScriptsReadBack() {
        val code = script("@name Stored", "@require https://cdn.example/lib.js", "@match https://a.org/*")
        val meta = requireNotNull(UserScriptMeta.parse(code))
        val script = UserScript(code, meta, mapOf("https://cdn.example/lib.js" to "var lib;"), enabled = false, allowPrivate = true,
            source = "https://a.org/x.user.js", installed = 1, updated = 2, checked = 3)
        assertEquals(script, UserScript.fromJson(JSONObject(script.toJson().toString())))
        assertNull(UserScript.fromJson(JSONObject().put("code", "not a script")))
    }

    @Test fun connectsAndResourcesAreRead() {
        val meta = meta("@connect API.Example.com", "@connect https://cdn.example.org/path", "@connect self",
            "@resource css https://cdn.example/a.css", "@resource   icon   https://cdn.example/i.png", "@resource broken not-a-url")
        assertEquals(listOf("api.example.com", "https://cdn.example.org/path", "self"), meta.connects)
        assertEquals(mapOf("css" to "https://cdn.example/a.css", "icon" to "https://cdn.example/i.png"), meta.resources)
        assertTrue(meta("@grant GM_xmlhttpRequest", "@grant GM.xmlHttpRequest", "@grant GM_registerMenuCommand").unsupportedGrants.isEmpty())
        assertEquals(listOf("GM_cookie"), meta("@grant GM_cookie").unsupportedGrants)
    }

    @Test fun whichHostsAScriptMayReachWithoutAsking() {
        val meta = meta("@connect example.com", "@connect https://cdn.example.org/x")
        assertTrue(meta.mayConnect("page.org", "page.org"))
        assertTrue(meta.mayConnect("example.com", "page.org"))
        assertTrue(meta.mayConnect("api.example.com", "page.org"))
        assertTrue(meta.mayConnect("cdn.example.org", "page.org"))
        assertFalse(meta.mayConnect("notexample.com", "page.org"))
        assertFalse(meta.mayConnect("example.com.evil.org", "page.org"))
        assertFalse(meta.mayConnect("sub.page.org", "page.org"))
        assertTrue(meta.mayConnect("other.net", "page.org", granted = setOf("other.net")))
        assertTrue(meta("@connect *").mayConnect("anything.org", null))
        assertFalse(meta().mayConnect("anything.org", null))
        assertFalse(meta().mayConnect("", ""))
    }

    @Test fun resourcesAndAllowedHostsAreStored() {
        val code = script("@name Stored", "@resource css https://cdn.example/a.css", "@match https://a.org/*")
        val script = UserScript(code, requireNotNull(UserScriptMeta.parse(code)), resources = mapOf("css" to UserScript.Resource("text/css", "cD17fQ==")),
            allowedHosts = setOf("api.other.net"))
        assertEquals(script, UserScript.fromJson(JSONObject(script.toJson().toString())))
    }

    @Test fun storedValuesKeepOnlyShortKeysAndText() {
        val values = UserScript.valuesFromJson(JSONObject().put("a", "1").put("b", 2).put("x".repeat(UserScript.MAX_KEY + 1), "\"y\""))
        assertEquals(mapOf("a" to "1"), values)
    }
}
