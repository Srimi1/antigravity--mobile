package dev.srimi.antigravitymobile

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** App-private project records. Deleting a project removes only the app's own copy. */
class ProjectRepository(private val dao: ProjectDao, val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    init { root.mkdirs() }

    fun directory(project: ProjectRecord): File = File(root, project.directory)
    fun observe() = dao.observe()
    suspend fun find(id: String) = dao.find(id)

    /** Reserves a fresh directory and record. [populate] fills it; on failure the directory is removed. */
    suspend fun create(name: String, populate: suspend (File) -> Unit = {}): ProjectRecord {
        val clean = name.trim().take(80)
        require(clean.isNotEmpty()) { "Enter a project name" }
        val slug = clean.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').ifEmpty { "project" }.take(40)
        val folder = "$slug-${UUID.randomUUID().toString().take(8)}"
        val dir = File(root, folder)
        check(dir.mkdirs()) { "Could not create project storage" }
        try { populate(dir) } catch (error: Throwable) { Archives.deleteTree(dir); throw error }
        val now = clock()
        val project = ProjectRecord(UUID.randomUUID().toString(), clean, folder, now, now)
        dao.save(project)
        return project
    }

    suspend fun touch(project: ProjectRecord) = dao.save(project.copy(openedAt = clock()))
    suspend fun rename(project: ProjectRecord, name: String) {
        require(name.isNotBlank()) { "Enter a project name" }
        dao.save(project.copy(name = name.trim().take(80)))
    }
    suspend fun delete(project: ProjectRecord) {
        Archives.deleteTree(directory(project))
        dao.delete(project.id)
    }
}

object Archives {
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    const val MAX_ENTRIES = 50_000

    /** Extracts a ZIP below [destination], refusing traversal and enforcing size limits. */
    fun extract(input: InputStream, destination: File, executableNames: Set<String> = setOf("gradlew")) {
        val base = destination.canonicalFile
        var total = 0L
        var entries = 0
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                check(++entries <= MAX_ENTRIES) { "Archive has too many entries" }
                val target = File(base, entry.name).canonicalFile
                check(target.path.startsWith(base.path + File.separator)) { "Archive entry escapes the project" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile!!.mkdirs()
                    target.outputStream().use { out ->
                        val buffer = ByteArray(16 * 1024)
                        var count = zip.read(buffer)
                        while (count >= 0) {
                            total += count
                            check(total <= MAX_TOTAL_BYTES) { "Archive is larger than 512 MB" }
                            out.write(buffer, 0, count)
                            count = zip.read(buffer)
                        }
                    }
                    if (target.name in executableNames) target.setExecutable(true, true)
                }
                entry = zip.nextEntry
            }
        }
    }

    /** Writes [directory] as a ZIP. Symbolic links are skipped rather than followed. */
    fun write(directory: File, output: OutputStream, includeGit: Boolean = true) {
        val base = directory.canonicalFile
        ZipOutputStream(output).use { zip ->
            base.walkTopDown().onEnter { it == base || (!Files.isSymbolicLink(it.toPath()) && (includeGit || it.name != ".git")) }
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                .forEach { file ->
                    val entry = ZipEntry(file.relativeTo(base).invariantSeparatorsPath)
                    entry.time = file.lastModified()
                    zip.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    fun deleteTree(directory: File) {
        if (!directory.exists() && !Files.isSymbolicLink(directory.toPath())) return
        Files.walkFileTree(directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                java.nio.file.FileVisitResult.CONTINUE.also { Files.delete(file) }
            override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?) =
                java.nio.file.FileVisitResult.CONTINUE.also { if (exc != null) throw exc; Files.delete(dir) }
        })
    }
}

/** Honest local build readiness for a project directory. */
object BuildInspector {
    data class Report(
        val gradleProject: Boolean,
        val wrapper: Boolean,
        val androidApp: Boolean,
        val apks: List<String>,
        val status: CheckStatus,
        val reason: String,
    )

    const val BLOCKED_REASON = "No Android-host JDK, Kotlin compiler, Gradle daemon, aapt2 or d8 is bundled. " +
        "Android 10+ also refuses to execute programs downloaded into app storage, so a toolchain must ship " +
        "inside the APK's native library directory. Until that exists, this phone cannot compile projects."

    fun inspect(dir: File): Report {
        val settings = listOf("settings.gradle.kts", "settings.gradle").any { File(dir, it).isFile }
        val build = listOf("build.gradle.kts", "build.gradle").any { File(dir, it).isFile }
        val wrapper = File(dir, "gradlew").isFile && File(dir, "gradle/wrapper/gradle-wrapper.properties").isFile
        val android = dir.walkTopDown().maxDepth(3).onEnter { it.name != ".git" && it.name != "build" }
            .any { it.isFile && it.name.startsWith("build.gradle") && it.readText().let { text ->
                "com.android.application" in text || "android.application" in text } }
        val apks = dir.walkTopDown().onEnter { it.name != ".git" && !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && it.extension == "apk" }.take(50)
            .map { it.relativeTo(dir).invariantSeparatorsPath }.toList()
        return Report(settings || build, wrapper, android, apks, CheckStatus.BLOCKED, BLOCKED_REASON)
    }
}
