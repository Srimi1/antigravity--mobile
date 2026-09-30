package dev.srimi.antigravityruntime;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class AndroidRuntimeTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private RuntimeHost host() throws Exception { return new RuntimeHost(context()); }
    private void success(RuntimeHost host, String label, File project, List<String> command, long seconds) throws Exception {
        host.run(label, project, command, seconds).requireSuccess();
    }

    @Test public void javaCompilerProducesAndRunsRealBytecode() throws Exception {
        RuntimeHost host = host(); File project = host.project("java");
        RuntimeHost.writeText(new File(project, "Hello.java"),
            "public class Hello { public static void main(String[] args) { " +
            "if (javax.tools.ToolProvider.getSystemJavaCompiler() == null) throw new AssertionError(\"No compiler\");" +
            "System.out.println(\"ANDROID_JAVA_COMPILED_OK\"); } }");
        success(host, "javac", project, host.java("--module", "jdk.compiler/com.sun.tools.javac.Main", "Hello.java"), 60);
        assertTrue(new File(project, "Hello.class").length() > 0);
        RuntimeHost.Result result = host.run("java-run", project, host.java("-classpath", ".", "Hello"), 30);
        result.requireSuccess(); assertTrue(result.output, result.output.contains("ANDROID_JAVA_COMPILED_OK"));
    }

    @Test public void nativeJavaCanStartAnotherJvm() throws Exception {
        RuntimeHost host = host(); File project = host.project("children");
        RuntimeHost.writeText(new File(project, "Children.java"),
            "import java.io.*; public class Children { public static void main(String[] args) throws Exception { " +
            "if (args.length > 0) { System.out.println(\"CHILD_JVM_OK\"); return; } " +
            "Process p = new ProcessBuilder(System.getProperty(\"java.home\") + \"/bin/java\", \"-cp\", \".\", \"Children\", \"child\").inheritIO().start(); " +
            "if (p.waitFor() != 0) throw new AssertionError(\"Child failed\"); } }");
        success(host, "children-compile", project, host.java("--module", "jdk.compiler/com.sun.tools.javac.Main", "Children.java"), 60);
        RuntimeHost.Result result = host.run("children-run", project, host.java("-classpath", ".", "Children"), 30);
        result.requireSuccess(); assertTrue(result.output, result.output.contains("CHILD_JVM_OK"));
    }

    @Test public void gradleRunsOnBionic() throws Exception {
        RuntimeHost host = host(); File project = host.project("gradle-version");
        RuntimeHost.Result result = host.run("gradle-version", project, host.gradle("--version"), 120);
        result.requireSuccess(); assertTrue(result.output, result.output.contains("Gradle 8.13"));
    }

    @Test public void buildsAndSignsAnAndroidApkOnAndroid() throws Exception {
        RuntimeHost host = host(); File project = host.project("android-apk");
        File classes = new File(project, "classes"); classes.mkdirs();
        File dex = new File(project, "dex"); dex.mkdirs();
        File androidJar = new File(host.sdk, "platforms/android-36/android.jar");
        RuntimeHost.writeText(new File(project, "MainActivity.java"),
            "package dev.srimi.nativebuild; public class MainActivity extends android.app.Activity { " +
            "public void onCreate(android.os.Bundle state) { super.onCreate(state); " +
            "android.widget.TextView text = new android.widget.TextView(this); text.setText(\"Built entirely on Android\"); " +
            "text.setTextSize(24); text.setPadding(24,80,24,24); setContentView(text); } }");
        RuntimeHost.writeText(new File(project, "AndroidManifest.xml"),
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"dev.srimi.nativebuild\" android:versionCode=\"1\" android:versionName=\"1\">" +
            "<uses-sdk android:minSdkVersion=\"29\" android:targetSdkVersion=\"36\"/><application android:label=\"Phone Build Proof\" android:theme=\"@android:style/Theme.Material.Light.NoActionBar\">" +
            "<activity android:name=\".MainActivity\" android:exported=\"true\"><intent-filter><action android:name=\"android.intent.action.MAIN\"/>" +
            "<category android:name=\"android.intent.category.LAUNCHER\"/></intent-filter></activity></application></manifest>");
        RuntimeHost.writeText(new File(project, "res/values/strings.xml"), "<resources><string name=\"build_evidence\">Android native build</string></resources>");
        success(host, "android-javac", project, host.java("--module", "jdk.compiler/com.sun.tools.javac.Main", "-source", "8", "-target", "8",
            "-classpath", androidJar.getAbsolutePath(), "-d", classes.getAbsolutePath(), "MainActivity.java"), 60);
        success(host, "android-d8", project, host.java("-classpath", new File(host.sdk, "build-tools/36.0.0/lib/d8.jar").getAbsolutePath(),
            "com.android.tools.r8.D8", "--lib", androidJar.getAbsolutePath(), "--min-api", "29", "--output", dex.getAbsolutePath(),
            new File(classes, "dev/srimi/nativebuild/MainActivity.class").getAbsolutePath()), 120);
        success(host, "android-aapt2-compile", project, host.nativeTool("aapt2", "compile", "--dir", "res", "-o", "resources.zip"), 30);
        success(host, "android-aapt2-link", project, host.nativeTool("aapt2", "link", "-o", "unsigned.apk", "-I", androidJar.getAbsolutePath(),
            "--manifest", "AndroidManifest.xml", "resources.zip"), 30);
        success(host, "android-jar", project, host.java("--module", "jdk.jartool/sun.tools.jar.Main", "uf", "unsigned.apk", "-C", dex.getAbsolutePath(), "classes.dex"), 30);
        File key = new File(project, "fixture.p12");
        if (!key.exists()) success(host, "android-keytool", project, host.nativeTool("keytool", "-genkeypair", "-keystore", "fixture.p12", "-storetype", "PKCS12",
            "-storepass", "fixture-only", "-keypass", "fixture-only", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "7", "-dname", "CN=Runtime Lab Fixture"), 60);
        String signer = new File(host.sdk, "build-tools/36.0.0/lib/apksigner.jar").getAbsolutePath();
        success(host, "android-sign", project, host.java("-jar", signer, "sign", "--ks", "fixture.p12", "--ks-pass", "pass:fixture-only",
            "--key-pass", "pass:fixture-only", "--out", "phone-built.apk", "unsigned.apk"), 60);
        success(host, "android-verify", project, host.java("-jar", signer, "verify", "--verbose", "phone-built.apk"), 30);
        assertTrue(new File(project, "phone-built.apk").length() > 0);
    }

    @Test public void gradleBuildsComposeSampleOnAndroid() throws Exception {
        String task = InstrumentationRegistry.getArguments().getString("runtimeTaskId");
        if (task == null || !task.matches("compose-[a-z0-9-]{1,64}"))
            throw new IOException("Provide a fresh explicit runtimeTaskId for each Compose build");
        RuntimeHost host = host(); File project = host.project(task);
        try (InputStream input = context().getAssets().open("hello-phone.zip")) { RuntimeHost.extract(input, project); }
        RuntimeHost.writeText(new File(project, "local.properties"), "sdk.dir=" + host.sdk.getAbsolutePath() + "\n");
        RuntimeHost.writeText(new File(project, "gradle.properties"),
            "android.useAndroidX=true\norg.gradle.jvmargs=-Xmx1200m -Dfile.encoding=UTF-8\n" +
            "org.gradle.daemon=false\norg.gradle.parallel=false\norg.gradle.vfs.watch=false\n" +
            "android.aapt2FromMavenOverride=" + new File(host.sdk, "build-tools/36.0.0/aapt2").getAbsolutePath() + "\n" +
            "kotlin.compiler.execution.strategy=in-process\n");
        success(host, task + "-gradle", project, host.gradle("--gradle-user-home",
            new File(host.root, "gradle-home-" + task).getAbsolutePath(), ":app:assembleDebug", "--stacktrace"), 1200);
        assertTrue(new File(project, "app/build/outputs/apk/debug/app-debug.apk").length() > 0);
    }

    @Test public void incompleteActionIsNeverReplayed() throws Exception {
        RuntimeHost host = host(); File project = host.project("interruption-guard");
        String label = "interruption-guard";
        RuntimeHost.writeText(new File(host.evidence, label + ".begin.json"),
            "{\"attemptId\":\"new-interrupted-attempt\",\"state\":\"RUNNING\"}");
        // An older successful record must not hide an interrupted later attempt.
        RuntimeHost.writeText(new File(host.evidence, label + ".json"),
            "{\"attemptId\":\"older-completed-attempt\",\"exit\":0}");
        try {
            host.run(label, project, host.java("--version"), 10);
            fail("Uncertain action was replayed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("never replay"));
        }
        assertFalse(new File(host.evidence, label + ".log").exists());
    }

    @Test public void cancellationKillsTheJvmAndItsChild() throws Exception {
        RuntimeHost host = host(); File project = host.project("cancel");
        RuntimeHost.writeText(new File(project, "Waiting.java"),
            "public class Waiting { public static void main(String[] args) throws Exception { " +
            "if (args.length == 0) new ProcessBuilder(System.getProperty(\"java.home\") + \"/bin/java\", \"-cp\", \".\", \"Waiting\", \"child\").inheritIO().start(); " +
            "System.out.println((args.length == 0 ? \"parent-pid:\" : \"child-pid:\") + ProcessHandle.current().pid()); " +
            "Thread.sleep(60000); } }");
        success(host, "cancel-compile", project, host.java("--module", "jdk.compiler/com.sun.tools.javac.Main", "Waiting.java"), 60);
        try { host.run("cancel-run", project, host.java("-cp", ".", "Waiting"), 6); fail("Expected cancellation timeout"); }
        catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("timed out")); }
        String log;
        try (InputStream input = new FileInputStream(new File(host.evidence, "cancel-run.log"))) { log = RuntimeHost.readText(input); }
        assertTrue(log, log.contains("parent-pid:")); assertTrue(log, log.contains("child-pid:"));
        for (String line : log.split("\n")) {
            if (line.startsWith("parent-pid:") || line.startsWith("child-pid:")) {
                int pid = Integer.parseInt(line.substring(line.indexOf(':') + 1));
                boolean alive = true;
                for (int n = 0; n < 30 && alive; n++) {
                    try { Os.kill(pid, 0); Thread.sleep(100); }
                    catch (android.system.ErrnoException error) {
                        if (error.errno != OsConstants.ESRCH) throw error;
                        alive = false;
                    }
                }
                assertFalse("Process survived cancellation: " + pid, alive);
            }
        }
    }
}
