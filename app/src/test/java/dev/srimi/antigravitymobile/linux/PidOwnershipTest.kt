package dev.srimi.antigravitymobile.linux

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** agm-linux.sh signals a recorded PID only while it still has the recorded start time. Needs Linux /proc. */
class PidOwnershipTest {
    private val script = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "tools/linux-runtime/agm-linux.sh") }.first { it.exists() }

    private fun owned(pidFile: File): String? {
        val home = Files.createTempDirectory("agm-home").toFile()
        val process = ProcessBuilder("bash", script.path, "_owned", pidFile.path)
            .apply { environment()["AGM_HOME"] = home.path }.redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        return if (process.waitFor() == 0) out else null
    }

    @Test fun reusedPidWithDifferentStartTimeIsNotOwned() {
        assumeTrue("needs Linux /proc", File("/proc/self/stat").exists())
        val child = ProcessBuilder("sh", "-c", "echo $$; exec sleep 30").start()
        try {
            val pid = child.inputStream.bufferedReader().readLine().trim().toLong()
            val start = File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]
            val file = Files.createTempFile("agm", ".pid").toFile()
            file.writeText("$pid $start\n")
            assertEquals(pid.toString(), owned(file))
            file.writeText("$pid ${start.toLong() + 1}\n") // same number, different process
            assertNull(owned(file))
            file.writeText("$pid\n") // legacy file, unrelated command line
            assertNull(owned(file))
            file.writeText("not-a-pid 1\n")
            assertNull(owned(file))
        } finally { child.destroyForcibly() }
    }

    @Test fun pidFileHelpersArePresentInBothCopies() {
        val text = script.readText()
        assertTrue(text.contains("record_pid \"\$AGM_HOME/desktop.pid\" \$!"))
        assertFalse(text.contains("echo \$! >"))
    }
}
