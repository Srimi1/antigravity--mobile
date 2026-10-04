package dev.srimi.antigravitymobile

import dev.srimi.antigravitymobile.runtime.BuildCache
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BuildCacheTest {
    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("build-cache").toFile()
        try { block(root) } finally { BuildCache.deleteTree(root) }
    }

    @Test fun keyIsStablePathSafeAndPerProject() {
        val a = BuildCache.key("project-a")
        assertEquals(a, BuildCache.key("project-a"))
        assertNotEquals(a, BuildCache.key("project-b"))
        BuildCache.validateKey(a)
        assertThrows(IllegalArgumentException::class.java) { BuildCache.validateKey("../escape") }
    }

    @Test fun healthyBuildLeavesWarmCacheForNextBuild() = withRoot { root ->
        val key = BuildCache.key("p")
        val first = BuildCache.acquire(root, key, "b1")
        assertFalse(first.reused)
        File(first.directory, "caches/modules-2/dep.jar").apply { parentFile!!.mkdirs(); writeText("dep") }
        BuildCache.release(first, healthy = true)
        val second = BuildCache.acquire(root, key, "b2")
        assertTrue(second.reused)
        assertEquals("dep", File(second.directory, "caches/modules-2/dep.jar").readText())
    }

    @Test fun cacheOfInterruptedBuildIsWipedNotTrusted() = withRoot { root ->
        val key = BuildCache.key("p")
        val first = BuildCache.acquire(root, key, "b1")
        File(first.directory, "partial.bin").writeText("half-written")
        BuildCache.release(first, healthy = false)
        val second = BuildCache.acquire(root, key, "b2")
        assertFalse(second.reused)
        assertFalse(File(second.directory, "partial.bin").exists())
    }

    @Test fun workerDeathLeavesBusyMarkerSoNextBuildStartsClean() = withRoot { root ->
        val key = BuildCache.key("p")
        val first = BuildCache.acquire(root, key, "b1")
        File(first.directory, "x").writeText("x")
        // No release: the worker process was killed.
        assertFalse(BuildCache.acquire(root, key, "b2").reused)
    }

    @Test fun cachesAreNotSharedBetweenProjects() = withRoot { root ->
        val a = BuildCache.acquire(root, BuildCache.key("a"), "b1")
        File(a.directory, "poison").writeText("p"); BuildCache.release(a, true)
        val b = BuildCache.acquire(root, BuildCache.key("b"), "b2")
        assertNotEquals(a.directory, b.directory)
        assertFalse(File(b.directory, "poison").exists())
    }

    @Test fun oldestProjectCachesAreEvictedBeyondTheLimit() = withRoot { root ->
        val keys = (1..5).map { BuildCache.key("p$it") }
        keys.forEachIndexed { i, key ->
            val slot = BuildCache.acquire(root, key, "b$i", now = 1_000L * (i + 1))
            File(slot.directory, "f").writeText("x")
            BuildCache.release(slot, true, now = 1_000L * (i + 1))
        }
        val kept = File(root, "projects").list().orEmpty().toSet()
        assertEquals(BuildCache.MAX_PROJECT_CACHES, kept.size)
        assertEquals(keys.takeLast(BuildCache.MAX_PROJECT_CACHES).toSet(), kept)
    }

    @Test fun legacyPerBuildDirectoriesAreRemovedAndOneOffCacheIsDeleted() = withRoot { root ->
        File(root, "11111111-1111-1111-1111-111111111111/caches").apply { mkdirs() }
        val oneOff = BuildCache.acquire(root, null, "22222222-2222-2222-2222-222222222222")
        assertFalse(File(root, "11111111-1111-1111-1111-111111111111").exists())
        assertFalse(oneOff.shared)
        BuildCache.release(oneOff, healthy = true)
        assertFalse(oneOff.directory.exists())
    }

    @Test fun deleteTreeDoesNotFollowLinks() = withRoot { root ->
        val outside = File(root, "outside").apply { mkdirs() }
        File(outside, "keep").writeText("keep")
        val tree = File(root, "tree").apply { mkdirs() }
        Files.createSymbolicLink(File(tree, "link").toPath(), outside.toPath())
        BuildCache.deleteTree(tree)
        assertFalse(tree.exists())
        assertEquals("keep", File(outside, "keep").readText())
    }
}
