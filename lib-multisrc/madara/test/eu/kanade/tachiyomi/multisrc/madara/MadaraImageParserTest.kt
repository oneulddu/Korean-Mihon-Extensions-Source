package eu.kanade.tachiyomi.multisrc.madara

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MadaraImageParserTest {
    @Test
    fun overlappingContainersAndImagesProduceEachNodeOnceInOrder() {
        val document = Jsoup.parse(
            """
            <div class="reading-content"><div class="text-left">
              <div class="page-break"><img id="first" src="/1.jpg"><img id="second" src="/2.jpg"></div>
              <div class="page-break"><img id="repeat" src="/1.jpg"></div>
              <img id="last" src="/3.jpg">
            </div></div>
            """.trimIndent(),
            "https://rawdex.net/chapter/1/",
        )
        val images = selectMadaraPageImages(
            document,
            "div.page-break, li.blocks-gallery-item, .reading-content .text-left:not(:has(.blocks-gallery-item)) img",
        )
        assertEquals(listOf("first", "second", "repeat", "last"), images.map { it.id() })
        assertSame(document.getElementById("first"), images[0])
        assertEquals(listOf("/1.jpg", "/2.jpg", "/1.jpg", "/3.jpg"), images.map { it.attr("src") })
    }

    @Test
    fun galleryAndNestedContainersKeepDistinctImagesAndSkipEmptyWrappers() {
        val document = Jsoup.parse(
            """
            <div class="page-break"><ul><li class="blocks-gallery-item"><img id="one" src="1.jpg"></li>
            <li class="blocks-gallery-item"><img id="two" src="2.jpg"></li></ul></div>
            <div class="page-break"></div>
            """.trimIndent(),
        )
        val images = selectMadaraPageImages(document, "div.page-break, li.blocks-gallery-item, img")
        assertEquals(listOf("one", "two"), images.map { it.id() })
    }

    @Test
    fun widthUsesNumericDescriptorInsteadOfUrlOrLexicalOrder() {
        assertEquals(
            "https://cdn.example/a.jpg",
            selectMadaraSrcSetImage("https://cdn.example/z.jpg 900w, https://cdn.example/a.jpg 1200w, https://cdn.example/m.jpg 80w"),
        )
    }

    @Test
    fun densitySupportsDecimalsWhitespaceAndImplicitOne() {
        assertEquals("high.jpg", selectMadaraSrcSetImage("normal.jpg,\n high.jpg\t2.5x, medium.jpg 2x"))
        assertEquals("normal.jpg", selectMadaraSrcSetImage("normal.jpg, small.jpg .5x"))
        assertEquals("single.jpg", selectMadaraSrcSetImage("single.jpg"))
    }

    @Test
    fun relativeAndProtocolRelativeUrlsResolveAgainstImageBaseUri() {
        val document = Jsoup.parse(
            """<base href="https://cdn.example/assets/"><img srcset="/small.jpg 320w, ../large.jpg 1200w">""",
            "https://rawdex.net/chapter/1/",
        )
        val element = document.selectFirst("img")!!
        assertEquals(
            "https://cdn.example/large.jpg",
            resolveMadaraSrcSetImage(element, selectMadaraSrcSetImage(element.attr("srcset"))),
        )
        assertEquals("https://other.example/2.jpg", resolveMadaraSrcSetImage(element, "//other.example/2.jpg"))
    }

    @Test
    fun invalidDescriptorsDoNotBeatValidCandidates() {
        assertEquals(
            "valid.jpg",
            selectMadaraSrcSetImage("bad.jpg Infinityx, valid.jpg 2x, zero.jpg 0x, negative.jpg -3w, invalid.jpg 10q"),
        )
        assertNull(selectMadaraSrcSetImage(""))
        assertNull(selectMadaraSrcSetImage("invalid.jpg 3.5w, also-invalid.jpg 2x 3x"))
    }

    @Test
    fun commasInsideImageUrlsArePreserved() {
        assertEquals(
            "https://cdn.example/resize,w_1200/page.jpg",
            selectMadaraSrcSetImage("small.jpg 320w, https://cdn.example/resize,w_1200/page.jpg 1200w"),
        )
    }
}
