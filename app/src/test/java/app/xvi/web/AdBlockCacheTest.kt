package app.xvi.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The file the built filter is kept in between starts: it reads back only whole, and only for the same lists. */
class AdBlockCacheTest {
    private val filter = AdFilter.build(sequenceOf("||ads.com^", "/banner.", "##.ad", "example.com##.promo"))
    private val infos = mapOf("easylist" to AdBlock.ListInfo("EasyList", 3, 0L), AdBlock.CUSTOM to AdBlock.ListInfo("", 1, 0L))
    private val page = "https://www.example.com/"

    private fun withFile(test: (File) -> Unit) {
        val directory = java.nio.file.Files.createTempDirectory("adblock-cache").toFile()
        try { test(File(File(directory, "adblock"), "filter.bin")) } finally { directory.deleteRecursively() }
    }

    @Test fun readsBackWhatWasWrittenForTheSameLists() = withFile { file ->
        AdBlock.Cache.write(file, "key", infos, filter)
        val (readInfos, read) = AdBlock.Cache.read(file, "key")!!
        assertEquals(infos.keys.toList(), readInfos.keys.toList())
        assertEquals("EasyList", readInfos.getValue("easylist").name)
        assertEquals(3, readInfos.getValue("easylist").rules)
        assertEquals(filter.size, read.size)
        assertTrue(read.blocks("https://ads.com/x.js", page, AdFilter.SCRIPT))
        assertFalse(read.blocks("https://fine.org/x.js", page, AdFilter.SCRIPT))
        assertEquals(filter.hidingCss(page, listOf(".ad")), read.hidingCss(page, listOf(".ad")))
        assertFalse(File(file.path + ".part").exists())
    }

    @Test fun otherListsOrADamagedFileMeanParsingAgain() = withFile { file ->
        assertNull(AdBlock.Cache.read(file, "key"))
        AdBlock.Cache.write(file, "key", infos, filter)
        assertNull(AdBlock.Cache.read(file, "other"))
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 1))
        assertNull(AdBlock.Cache.read(file, "key"))
        file.writeBytes(bytes.clone().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() })
        assertNull(AdBlock.Cache.read(file, "key"))
        file.writeBytes(bytes)
        assertTrue(AdBlock.Cache.read(file, "key") != null)
    }
}
