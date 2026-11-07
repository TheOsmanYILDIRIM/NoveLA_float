package my.noveldokusha.text_translator.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelLexMarkersTest {
    private val term = NovelLexTermRef("0000000000000001", "Spirit Core", "Ruh Çekirdeği")

    @Test fun longestTermWinsAndWordBoundariesHold() {
        val index = NovelLexMarkers(listOf(term, NovelLexTermRef("0000000000000002", "Core")))
        val prepared = index.prepare("The Spirit Core and Core are here. Hardcore is not a term.")
        assertEquals(2, prepared.occurrences.size)
        assertTrue(prepared.markedText.contains("⟦NLX0000000000000001:SPIRIT CORE⟧"))
        assertTrue(prepared.markedText.contains("⟦NLX0000000000000002:CORE⟧"))
        assertTrue(prepared.markedText.contains("Hardcore"))
    }

    @Test fun suffixOutsideMarkerIsPreserved() {
        val index = NovelLexMarkers(listOf(term))
        val prepared = index.prepare("Michael absorbed the Spirit Core.")
        val restored = index.restore(
            prepared, "Michael ⟦NLX0000000000000001:RUH ÇEKİRDEĞİ⟧'ni özümsedi.", "tr"
        )
        assertTrue(restored.valid)
        assertEquals("Michael Ruh Çekirdeğini özümsedi.", restored.text)
    }

    @Test fun droppedIdCannotLeakProtocol() {
        val index = NovelLexMarkers(listOf(term))
        val prepared = index.prepare("The Spirit Core is here.")
        assertFalse(index.restore(prepared, "Ruh çekirdeği burada.", "tr").valid)
        assertFalse(index.restore(prepared, "⟦NLXAAAAAAAAAAAAAAAA:RUH ÇEKİRDEĞİ⟧", "tr").valid)
        assertFalse(index.restore(prepared, "⟦NLX0000000000000001:RUH ÇEKİRDEĞİ⟧ and ⟦NLX0000000000000001:X⟧", "tr").valid)
    }

    @Test fun nonLatinScriptsRemainUnchanged() {
        val id = "0000000000000003"
        val index = NovelLexMarkers(listOf(NovelLexTermRef(id, "灵魂核心", "Ruh Çekirdeği")))
        assertTrue(index.prepare("他找到灵魂核心了").markedText.contains("⟦NLX$id:灵魂核心⟧"))
    }

    @Test fun tenThousandTermsDontRequireTenThousandRegexPasses() {
        val many = (1..10000).map {
            NovelLexTermRef("%016X".format(it.toLong()), "Magical Spirit Artifact $it")
        }
        val index = NovelLexMarkers(many)
        val text = (1..200).joinToString(" ") { "Magical Spirit Artifact $it appeared." }
        assertEquals(200, index.prepare(text).occurrences.size)
    }
}
