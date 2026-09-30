package dev.srimi.antigravitymobile

import dev.srimi.antigravitymobile.runtime.WebGuard
import org.junit.Assert.*
import org.junit.Test

class WebGuardTest {
    private fun inject(html: String) = String(WebGuard.inject(html.toByteArray()))

    @Test fun guardFollowsDoctypeSoPagesKeepStandardsMode() {
        val page = inject("<!DOCTYPE html>\n<h1>Site</h1>")
        assertTrue(page.startsWith("<!DOCTYPE html><script>"))
        assertTrue(page.endsWith("</script>\n<h1>Site</h1>"))
    }
    @Test fun guardIsOneLineSoConsoleLineNumbersMatchTheSavedFile() {
        val page = inject("<!doctype html><p>1</p>\n<p>2</p>")
        assertEquals(2, page.lines().size)
        assertTrue(page.lines()[0].contains("RTCPeerConnection"))
    }
    @Test fun byteOrderMarkAndLeadingWhitespaceAreKeptBeforeDoctype() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val result = WebGuard.inject(bom + "  <!doctype html><p>x</p>".toByteArray())
        assertArrayEquals(bom, result.copyOfRange(0, 3))
        assertTrue(String(result, 3, result.size - 3).startsWith("  <!doctype html><script>"))
    }
    @Test fun pagesWithoutDoctypeStartWithTheGuardAndKeepTheirBytes() {
        val original = "<h1>No doctype</h1>"
        val page = inject(original)
        assertTrue(page.startsWith("<script>"))
        assertTrue(page.endsWith("</script>$original"))
        assertEquals("<script>", inject("").take(8))
    }
}
