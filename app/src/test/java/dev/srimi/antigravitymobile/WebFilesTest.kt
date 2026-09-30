package dev.srimi.antigravitymobile

import dev.srimi.antigravitymobile.runtime.WebFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun traversalHiddenDependenciesAndControlCharactersAreRejected() {
        listOf("../index.html", "/index.html", "a/../b", "a//b", "a\\b", ".env", "a/.git/config",
            "node_modules/x.js", "file:secret", "line\nbreak.html", "a\u0000b").forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) { WebFiles.relative(path) }
        }
        assertEquals("nested/index.html", WebFiles.relative("nested/index.html"))
    }
    @Test fun invalidArchiveCannotWriteOutsideDestination() {
        val root = temporary.newFolder()
        listOf("../escaped", "/absolute", ".env", "node_modules/dependency").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { WebFiles.extract(zip(listOf(path to byteArrayOf(1))).inputStream(), root) }
        }
        assertFalse(File(root.parentFile, "escaped").exists())
    }
    @Test fun regularHtmlCssJavascriptJsonAndBinaryAssetsRoundTrip() {
        val source = temporary.newFolder()
        val files = mapOf("index.html" to "<h1>Site</h1>".toByteArray(), "assets/app.js" to "console.log('real JS')".toByteArray(),
            "assets/style.css" to "body {color:red}".toByteArray(), "data.json" to "{\"value\":1}".toByteArray(),
            "image.png" to byteArrayOf(0, 1, 2, -1))
        files.forEach { (path, bytes) -> File(source, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) } }
        val archive = temporary.newFile()
        WebFiles.snapshot(source, archive)
        val target = temporary.newFolder()
        archive.inputStream().use { WebFiles.extract(it, target) }
        files.forEach { (path, bytes) -> assertArrayEquals(bytes, File(target, path).readBytes()) }
    }
    @Test fun symlinkInputAndExistingLinkedExtractionParentAreRefused() {
        val source = temporary.newFolder(); val outside = temporary.newFile().apply { writeText("private fixture") }
        java.nio.file.Files.createSymbolicLink(File(source, "link.html").toPath(), outside.toPath())
        val archive = temporary.newFile()
        assertThrows(IllegalStateException::class.java) { WebFiles.snapshot(source, archive) }
        val target = temporary.newFolder(); val foreign = temporary.newFolder()
        java.nio.file.Files.createSymbolicLink(File(target, "assets").toPath(), foreign.toPath())
        assertThrows(IllegalStateException::class.java) { WebFiles.extract(zip(listOf("assets/escaped" to byteArrayOf(1))).inputStream(), target) }
        assertFalse(File(foreign, "escaped").exists())
    }
    @Test fun zipBombIsBoundedByUncompressedBytes() {
        val compressed = ByteArrayOutputStream()
        ZipOutputStream(compressed).use { zip ->
            zip.putNextEntry(ZipEntry("large.bin"))
            val block = ByteArray(65536)
            repeat((WebFiles.MAX_BYTES / block.size).toInt() + 1) { zip.write(block) }
        }
        assertThrows(IllegalStateException::class.java) { WebFiles.extract(compressed.toByteArray().inputStream(), temporary.newFolder()) }
    }
    @Test fun snapshotRefusesOversizedOrTooManySourceFilesAndLeavesNoArchive() {
        val large = temporary.newFolder()
        java.io.RandomAccessFile(File(large, "large.bin"), "rw").use { it.setLength(WebFiles.MAX_BYTES + 1) }
        val archive = File(temporary.root, "large.zip")
        assertThrows(IllegalStateException::class.java) { WebFiles.snapshot(large, archive) }
        assertFalse(archive.exists())
        val many = temporary.newFolder()
        repeat(WebFiles.MAX_FILES + 1) { File(many, "f$it.txt").createNewFile() }
        val manyArchive = File(temporary.root, "many.zip")
        assertThrows(IllegalStateException::class.java) { WebFiles.snapshot(many, manyArchive) }
        assertFalse(manyArchive.exists())
    }
    @Test fun snapshotSkipsHiddenAndDependencyFoldersAndRefusesArchiveInsideSite() {
        val source = temporary.newFolder()
        File(source, "index.html").writeText("<p>ok</p>")
        File(source, ".git").mkdir(); File(source, ".git/config").writeText("private")
        File(source, "node_modules/pkg").mkdirs(); File(source, "node_modules/pkg/index.js").writeText("dependency")
        val archive = temporary.newFile()
        WebFiles.snapshot(source, archive)
        java.util.zip.ZipFile(archive).use { zip -> assertEquals(listOf("index.html"), zip.entries().asSequence().map { it.name }.toList()) }
        assertThrows(IllegalStateException::class.java) { WebFiles.snapshot(source, File(source, "inside.zip")) }
    }
    @Test fun excessiveFileCountAndFileDirectoryCollisionsAreRefused() {
        val entries = (1..WebFiles.MAX_FILES + 1).map { "f$it" to byteArrayOf() }
        assertThrows(IllegalStateException::class.java) { WebFiles.extract(zip(entries).inputStream(), temporary.newFolder()) }
        assertThrows(IllegalStateException::class.java) { WebFiles.extract(zip(listOf("a" to byteArrayOf(1), "a/b" to byteArrayOf(2))).inputStream(), temporary.newFolder()) }
    }
}
