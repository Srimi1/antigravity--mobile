plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}
// Compile-and-test harness for environments without Google Maven (see README.md). Not the product build.
val app = file("../../app/src")
val androidJar = System.getenv("ANDROID_JAR") ?: (System.getProperty("user.home") + "/android-sdk/platforms/android-36/android.jar")
sourceSets {
    main { kotlin.srcDirs(app.resolve("main/java"), file("stubs")) }
    test { kotlin.srcDirs(app.resolve("test/java")) }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation("org.jetbrains.compose.material3:material3-desktop:1.8.0")
    implementation("org.jetbrains.compose.material:material-icons-core-desktop:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.nimbusds:nimbus-jose-jwt:9.37.3")
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.5.202508271544-r")
    compileOnly(files(androidJar))
    testImplementation("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
tasks.test { testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
configurations.all {
    exclude(group = "androidx.lifecycle"); exclude(group = "androidx.annotation"); exclude(group = "androidx.collection")
    exclude(group = "androidx.arch.core"); exclude(group = "androidx.savedstate")
}
val deviceTest by sourceSets.creating {
    kotlin.srcDirs(app.resolve("androidTest/java"), file("devstubs"))
    compileClasspath += sourceSets.main.get().output + configurations.testCompileClasspath.get() + files(androidJar)
}
tasks.named("check") { dependsOn("compileDeviceTestKotlin") }
