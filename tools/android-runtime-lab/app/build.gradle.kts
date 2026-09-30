plugins { id("com.android.application") }

val prepared = file("${System.getProperty("user.home")}/.cache/antigravity-mobile-runtime/lab-generated")
val sdk = System.getenv("ANDROID_HOME") ?: error("Set ANDROID_HOME to the development SDK")
android {
    namespace = "dev.srimi.antigravityruntime"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.srimi.antigravityruntime.lab"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-lab"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
    androidResources { noCompress += "zip" }
    sourceSets.getByName("main").apply {
        assets.srcDir(File(prepared, "assets"))
        jniLibs.srcDir(File(prepared, "jniLibs"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/launcher"))
    }
}

val compileLauncher by tasks.registering(Exec::class) {
    val host = if (System.getProperty("os.name").contains("Mac")) "darwin-x86_64" else "linux-x86_64"
    val ndk = "$sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/$host"
    val source = file("src/main/cpp/java_launcher.c")
    val output = layout.buildDirectory.file("generated/launcher/arm64-v8a/libag_java.so").get().asFile
    inputs.file(source)
    outputs.file(output)
    doFirst {
        check(File(prepared, "assets/runtime-data.zip").isFile) { "Run tools/android-runtime-lab/prepare.py first" }
        output.parentFile.mkdirs()
    }
    commandLine("$ndk/bin/aarch64-linux-android29-clang", "-O2", "-fPIE", "-pie",
        "-Wl,-z,max-page-size=16384", source, "-ldl", "-o", output)
}
tasks.named("preBuild").configure { dependsOn(compileLauncher) }

val compileJdkPaths by tasks.registering(Exec::class) {
    val host = if (System.getProperty("os.name").contains("Mac")) "darwin-x86_64" else "linux-x86_64"
    val compiler = "$sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android29-clang"
    val source = file("src/main/cpp/jdk_paths.c")
    val output = layout.buildDirectory.file("generated/launcher/arm64-v8a/libag_jdk_paths.so").get().asFile
    inputs.file(source); outputs.file(output)
    doFirst { output.parentFile.mkdirs() }
    commandLine(compiler, "-O2", "-fPIC", "-shared", "-Wl,-z,max-page-size=16384", source, "-ldl", "-o", output)
}
tasks.named("preBuild").configure { dependsOn(compileJdkPaths) }

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
