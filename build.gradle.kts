plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.1.21" apply false
}

// Keep transient compiler files out of cloud-synced source directories.
// Each worktree can select its own output root with -PagmBuildRoot=/absolute/path.
val agmBuildRoot = providers.gradleProperty("agmBuildRoot")
    .orElse("${System.getProperty("user.home")}/.cache/antigravity-mobile-build")
allprojects {
    layout.buildDirectory.set(rootProject.file("${agmBuildRoot.get()}/${project.name}"))
}
