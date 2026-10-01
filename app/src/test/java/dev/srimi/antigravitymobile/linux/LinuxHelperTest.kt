package dev.srimi.antigravitymobile.linux

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LinuxHelperTest {
    private val ready = TermuxStatus(true, "0.119", true, true, false)
    private fun kv(text: String) = TermuxProtocol.keyValues(text)

    @Test fun bundledHelperMatchesToolsCopy() {
        val bundled = javaClass.getResourceAsStream("/dev/srimi/antigravitymobile/linux/agm-linux.sh")!!.bufferedReader().readText()
        val tools = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "tools/linux-runtime/agm-linux.sh") }.first { it.exists() }
        assertEquals("app/src/main/resources copy must equal tools/linux-runtime/agm-linux.sh", tools.readText(), bundled)
    }

    @Test fun statusStates() {
        assertEquals(LinuxState.TermuxMissing, LinuxHelperOutput.status(emptyList(), ready.copy(installed = false)).state)
        assertEquals(LinuxState.NotInstalled, LinuxHelperOutput.status(kv("proot_distro=oci\ndistribution=missing"), ready).state)
        assertEquals(LinuxState.Installing, LinuxHelperOutput.status(kv("distribution=missing\njob_install=running"), ready).state)
        val failed = LinuxHelperOutput.status(kv("proot_distro=legacy\ndistribution=missing"), ready)
        assertEquals(LinuxState.Failed, failed.state)
        assertTrue(failed.detail.contains("pkg upgrade proot-distro"))
        val stopped = LinuxHelperOutput.status(kv("distribution=installed\ndebian_version=12.7\ndesktop=installed\ndesktop_running=no"), ready)
        assertEquals(LinuxState.Stopped, stopped.state); assertTrue(stopped.desktopInstalled)
        assertEquals(LinuxState.Running, LinuxHelperOutput.status(kv("distribution=installed\ndesktop=installed\ndesktop_running=yes"), ready).state)
        assertTrue(LinuxHelperOutput.status(kv("distribution=missing\njob_install=interrupted"), ready).detail.contains("interrupted"))
    }

    @Test fun storageCleanupAndClis() {
        val usage = LinuxHelperOutput.storage(kv("distribution_kb=2048\npackage_cache_kb=1024\ncli_kb=0\nworkspace_kb=10"), 5)
        assertEquals(2048L * 1024, usage.distributionBytes); assertEquals((2048L + 1024 + 0 + 10) * 1024, usage.totalBytes)
        assertNull(LinuxHelperOutput.storage(kv("distribution_kb=1"), 5).totalBytes)
        val result = LinuxHelperOutput.cleanup(kv("removed=workspaces\nfailed=desktop"), setOf(CleanupItem.Workspaces, CleanupItem.Desktop))
        assertEquals(setOf(CleanupItem.Workspaces), result.removed); assertEquals(setOf(CleanupItem.Desktop), result.failures.keys)
        val clis = LinuxHelperOutput.clis(kv("cli_codex=/opt/agm/node/bin/codex\nversion_codex=codex-cli 1.2.3\ncli_gemini=missing\nversion_gemini=x"))
        assertEquals("/opt/agm/node/bin/codex", clis.first { it.tool == CliTool.CODEX }.binaryPath)
        assertEquals("codex-cli 1.2.3", clis.first { it.tool == CliTool.CODEX }.version)
        assertFalse(clis.first { it.tool == CliTool.GEMINI }.installed); assertNull(clis.first { it.tool == CliTool.GEMINI }.version)
    }

    @Test fun termuxResultErrors() {
        assertThrows(TermuxUnavailable.ExternalAppsDisabled::class.java) {
            TermuxProtocol.result(null, null, null, 2, "RUN_COMMAND requires allow-external-apps property to be set to true", 1)
        }
        assertThrows(TermuxUnavailable.Failed::class.java) { TermuxProtocol.result(null, null, null, 1, "Executable not found", 1) }
        val ok = TermuxProtocol.result("a=1", "", 0, -1, null, 1)
        assertTrue(ok.succeeded)
        assertFalse(TermuxProtocol.result("", "boom", 3, -1, null, 1).succeeded)
        assertEquals(listOf("removed" to "a", "removed" to "b"), TermuxProtocol.keyValues("removed=a\nnoise\nremoved=b\n=x"))
    }
}
