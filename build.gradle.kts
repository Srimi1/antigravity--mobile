plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.1.21" apply false
}

// Keep transient compiler files out of cloud-synced source directories.
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/.cache/antigravity-mobile-build/${project.name}"))
}
