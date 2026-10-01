import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "dev.srimi.antigravitymobile"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.srimi.antigravitymobile.probe"
        minSdk = 29
        targetSdk = 36
        versionCode = 11
        versionName = "0.5.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        create("personal") {
            val file = rootProject.file(".signing/personal.p12")
            if (file.exists()) {
                storeFile = file
                storePassword = "local-probe"
                keyAlias = "personal-probe"
                keyPassword = "local-probe"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (rootProject.file(".signing/personal.p12").exists()) {
                signingConfig = signingConfigs.getByName("personal")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libexecution_probe.so" }
        // JGit ships OSGi metadata that has no use in an APK.
        resources { excludes += listOf("about.html", "plugin.properties", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    }
    androidResources { noCompress += "apk" }
    sourceSets.getByName("main").java.srcDir(rootProject.file("runtime-contract/src/main/java"))
    lint {
        // JGit references java.lang.management/javax.management only for JMX (opt-in) and gc pid locks,
        // which GitRuntime disables. Keep the finding visible as a warning instead of failing release lint.
        warning += "InvalidPackage"
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

val compileNativeProbe by tasks.registering(Exec::class) {
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: Properties().apply {
            val props = rootProject.file("local.properties")
            if (props.exists()) props.inputStream().use { load(it) }
        }.getProperty("sdk.dir") ?: error("Set ANDROID_HOME or sdk.dir in local.properties")
    val host = if (System.getProperty("os.name").contains("Mac")) "darwin-x86_64" else "linux-x86_64"
    val compiler = "$sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android29-clang"
    val source = file("src/main/cpp/execution_probe.c")
    val output = layout.buildDirectory.file("generated/probe/arm64-v8a/libexecution_probe.so").get().asFile
    inputs.file(source)
    outputs.file(output)
    doFirst { output.parentFile.mkdirs(); check(file(compiler).exists()) { "Install NDK 27.2.12479018 first" } }
    commandLine(compiler, "-O2", "-fPIE", "-pie", "-Wl,-z,max-page-size=16384", source, "-o", output)
}
android.sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/probe"))
tasks.named("preBuild").configure { dependsOn(compileNativeProbe) }
val bundleSample by tasks.registering(Zip::class) {
    from(rootProject.file("samples/HelloPhone")) { exclude(".gradle/**", ".kotlin/**", "**/build/**", "local.properties") }
    destinationDirectory.set(layout.buildDirectory.dir("generated/sample-assets"))
    archiveFileName.set("hello-phone.zip")
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/sample-assets"))
tasks.named("preBuild").configure { dependsOn(bundleSample) }
val bundleWebsite by tasks.registering(Zip::class) {
    from(rootProject.file("samples/HelloWeb"))
    destinationDirectory.set(layout.buildDirectory.dir("generated/sample-assets"))
    archiveFileName.set("hello-web.zip")
}
tasks.named("preBuild").configure { dependsOn(bundleWebsite) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    kapt("androidx.room:room-compiler:2.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("com.nimbusds:nimbus-jose-jwt:9.37.3")
    implementation("androidx.core:core-ktx:1.16.0")
    // Pure-Java Git; 5.13 is the last line built for Java 8 APIs available on Android 10.
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.5.202508271544-r")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Real org.json for JVM tests; Android's copy is a stub there.
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// Match the companion signer to each main APK variant; never embed the private key.
androidComponents.onVariants { variant ->
    val capital = variant.name.replaceFirstChar { it.uppercase() }
    val bundle = tasks.register<BundleWorkerApk>("bundle${capital}BuildWorker") {
        dependsOn(":build-worker:assemble$capital")
        input.set(project(":build-worker").layout.buildDirectory.file("outputs/apk/${variant.name}/build-worker-${variant.name}.apk"))
        output.set(layout.buildDirectory.dir("generated/${variant.name}/worker-assets"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(bundle) { it.output }
}

abstract class BundleWorkerApk : DefaultTask() {
    @get:InputFile abstract val input: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @TaskAction fun bundle() {
        val folder = output.get().asFile.apply { mkdirs() }
        input.get().asFile.copyTo(File(folder, "build-worker.apk"), overwrite = true)
    }
}
