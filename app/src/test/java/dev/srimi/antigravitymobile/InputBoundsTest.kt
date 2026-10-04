package dev.srimi.antigravitymobile

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/** Bounds on untrusted input read into the main app process (audit findings 3 and 5). */
class InputBoundsTest {
    @Test fun callbackLineIsReadAndCarriageReturnDropped() {
        val line = LoopbackRequest.readLine(ByteArrayInputStream("GET /auth/callback?state=x HTTP/1.1\r\nHost: a\r\n".toByteArray()), Long.MAX_VALUE)
        assertEquals("GET /auth/callback?state=x HTTP/1.1", line)
    }

    @Test fun oversizedCallbackLineFailsWithoutReadingItAll() {
        var served = 0L
        val endless = object : InputStream() { override fun read(): Int { served++; return 'a'.code } }
        assertThrows(IOException::class.java) { LoopbackRequest.readLine(endless, Long.MAX_VALUE) }
        assertTrue(served <= LoopbackRequest.MAX_LINE + 1L)
    }

    @Test fun slowCallbackClientHitsAbsoluteDeadline() {
        var clock = 0L
        val drip = object : InputStream() { override fun read(): Int { clock += 1_000; return 'a'.code } }
        assertThrows(IOException::class.java) { LoopbackRequest.readLine(drip, deadline = 5_000, now = { clock }) }
        assertTrue(clock <= 7_000)
    }

    @Test fun inspectorReadsOnlyHeadOfHugeBuildScript() {
        val dir = Files.createTempDirectory("inspect").toFile()
        File(dir, "settings.gradle.kts").writeText("")
        File(dir, "app").mkdirs()
        File(dir, "app/build.gradle.kts").outputStream().use { out ->
            out.write("plugins { id(\"com.android.application\") }\n".toByteArray())
            val filler = ByteArray(1024 * 1024) { ' '.code.toByte() }
            repeat(8) { out.write(filler) }
        }
        assertEquals(BuildInspector.MAX_SCRIPT_BYTES, BuildInspector.headText(File(dir, "app/build.gradle.kts")).length)
        assertTrue(BuildInspector.inspect(dir).androidApp)
    }
}
