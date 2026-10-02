plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val unsignedRelease = providers.gradleProperty("agmUnsignedRelease").map(String::toBoolean).getOrElse(false)
val prepared = file("${System.getProperty("user.home")}/.cache/antigravity-mobile-runtime/lab-generated")
val sdk = System.getenv("ANDROID_HOME") ?: error("Set ANDROID_HOME")
android {
    namespace = "dev.srimi.antigravitymobile.worker"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.srimi.antigravitymobile.worker"
        minSdk = 29; targetSdk = 36
        versionCode = 2; versionName = "0.4.0-tools"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        create("personal") {
            storeFile = rootProject.file(".signing/personal.p12")
            storePassword = "local-probe"; keyAlias = "personal-probe"; keyPassword = "local-probe"
        }
    }
    buildTypes { release { if (!unsignedRelease) signingConfig = signingConfigs.getByName("personal") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
    androidResources { noCompress += "zip" }
    sourceSets.getByName("main").apply {
        java.srcDir(rootProject.file("runtime-contract/src/main/java"))
        java.srcDir(rootProject.file("tools/android-runtime-lab/app/src/main/java"))
        assets.srcDir(File(prepared, "assets"))
        jniLibs.srcDir(File(prepared, "jniLibs"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/launcher"))
    }
}
for ((name, shared) in listOf("java_launcher" to false, "jdk_paths" to true)) {
    val compile = tasks.register<Exec>("compile$name") {
        val host = if (System.getProperty("os.name").contains("Mac")) "darwin-x86_64" else "linux-x86_64"
        val compiler = "$sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android29-clang"
        val source = rootProject.file("tools/android-runtime-lab/app/src/main/cpp/$name.c")
        val output = layout.buildDirectory.file("generated/launcher/arm64-v8a/libag_${if (shared) "jdk_paths" else "java"}.so").get().asFile
        inputs.file(source); outputs.file(output)
        doFirst { output.parentFile.mkdirs(); check(File(prepared,"assets/runtime-data.zip").exists()) { "Prepare runtime inputs first" } }
        commandLine(listOf(compiler,"-O2") + (if (shared) listOf("-fPIC","-shared") else listOf("-fPIE","-pie")) +
            listOf("-Wl,-z,max-page-size=16384",source.path,"-ldl","-o",output.path))
    }
    tasks.named("preBuild").configure { dependsOn(compile) }
}
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
