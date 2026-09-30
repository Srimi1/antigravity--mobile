package dev.srimi.antigravityruntime;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Dedicated, credential-free validation app. This is not a project sandbox or
 * a production command API. Tests operate only on their own scratch projects. */
public final class RuntimeHost {
    public final Context context;
    public final File root, jdk, sdk, gradle, nativeDir, evidence;
    private final JSONObject manifest;

    public RuntimeHost(Context context) throws Exception {
        this.context = context;
        root = new File(context.getFilesDir(), "runtime");
        jdk = new File(root, "jdk"); sdk = new File(root, "sdk"); gradle = new File(root, "gradle");
        nativeDir = new File(context.getApplicationInfo().nativeLibraryDir).getCanonicalFile();
        evidence = new File(context.getFilesDir(), "evidence");
        evidence.mkdirs();
        try (InputStream in = context.getAssets().open("runtime-manifest.json")) {
            manifest = new JSONObject(readText(in));
        }
        File marker = new File(root, "prepared.sha256");
        String hash = manifest.getString("dataSha256");
        if (!marker.isFile() || !new String(Files.readAllBytes(marker.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim().equals(hash)) {
            root.mkdirs();
            try (InputStream in = context.getAssets().open("runtime-data.zip")) { extract(in, root); }
            writeText(marker, hash);
        }
        JSONObject links = manifest.getJSONObject("nativeLinks");
        Iterator<String> names = links.keys();
        while (names.hasNext()) {
            String name = names.next();
            File link = bounded(root, name);
            File target = bounded(nativeDir, links.getString(name));
            if (!target.isFile() || !target.canExecute()) throw new IOException("APK executable missing: " + target);
            link.getParentFile().mkdirs();
            // Do not follow an old install's symlink when replacing the alias.
            if (Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.delete(link.toPath());
            Files.createSymbolicLink(link.toPath(), target.toPath());
        }
        writeText(new File(evidence, "runtime-manifest.json"), manifest.toString(2));
        new File(root, "tmp").mkdirs(); new File(root, "home").mkdirs();
        android.net.ConnectivityManager network = (android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.LinkProperties properties = network.getLinkProperties(network.getActiveNetwork());
        StringBuilder resolv = new StringBuilder();
        if (properties != null) for (java.net.InetAddress server : properties.getDnsServers()) resolv.append("nameserver ").append(server.getHostAddress()).append('\n');
        writeText(new File(root, "resolv.conf"), resolv.toString());
    }

    public static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
        return bytes.toString("UTF-8");
    }

    public static void writeText(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static File bounded(File root, String relative) throws IOException {
        if (relative.startsWith("/") || Arrays.asList(relative.split("/")).contains("..")) throw new IOException("Unsafe path");
        File file = new File(root, relative);
        // Check the parent, since the final component may be an executable alias.
        String parent = file.getParentFile().getCanonicalPath();
        String base = root.getCanonicalPath();
        if (!parent.equals(base) && !parent.startsWith(base + File.separator)) throw new IOException("Path escaped root");
        return file;
    }

    public static void extract(InputStream input, File directory) throws IOException {
        directory.mkdirs(); long bytes = 0; int count = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 50000) throw new IOException("Too many files");
                File file = bounded(directory, entry.getName());
                if (!file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)) throw new IOException("Archive escaped root");
                if (entry.isDirectory()) { file.mkdirs(); continue; }
                file.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(file)) {
                    int n;
                    while ((n = zip.read(buffer)) != -1) {
                        bytes += n;
                        if (bytes > 1024L * 1024 * 1024) throw new IOException("Archive too large");
                        out.write(buffer, 0, n);
                    }
                }
            }
        }
    }

    public File project(String name) throws IOException {
        File project = bounded(new File(context.getFilesDir(), "projects"), name);
        project.mkdirs(); return project;
    }

    public List<String> java(String... args) {
        List<String> command = new ArrayList<>();
        command.add(new File(jdk, "bin/java").getAbsolutePath());
        Collections.addAll(command, args); return command;
    }

    public List<String> nativeTool(String name, String... args) {
        List<String> command = java("--native", new File(nativeDir, "libag_" + name + ".so").getAbsolutePath());
        Collections.addAll(command, args); return command;
    }

    public List<String> gradle(String... args) {
        List<String> command = java("-Xmx1200m", "-classpath", new File(gradle, "lib/gradle-gradle-cli-main-8.13.jar").getAbsolutePath(), "org.gradle.launcher.GradleMain");
        Collections.addAll(command, "--no-daemon", "--console=plain", "--max-workers=2", "-Dorg.gradle.native=false",
            "-Dorg.gradle.internal.instrumentation.agent=false", "-Dorg.gradle.vfs.watch=false",
            "-Pkotlin.compiler.execution.strategy=in-process");
        Collections.addAll(command, args); return command;
    }

    public Result run(String label, File directory, List<String> command, long timeoutSeconds) throws Exception {
        Log.i("AntigravityRuntime", label + ": starting");
        long start = android.os.SystemClock.elapsedRealtime();
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.remove("AG_PROCESS_GROUP");
        env.put("JAVA_HOME", jdk.getAbsolutePath());
        env.put("AG_NATIVE_LIB", nativeDir.getAbsolutePath());
        env.put("AG_JAVA_VERSION", manifest.getString("javaVersion"));
        env.put("AG_USER_HOME", new File(root, "home").getAbsolutePath());
        env.put("AG_RESOLV_PATH", new File(root, "resolv.conf").getAbsolutePath());
        env.put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath() + ":/system/lib64");
        env.put("LD_PRELOAD", new File(nativeDir, "libag_jdk_paths.so").getAbsolutePath());
        env.put("ANDROID_HOME", sdk.getAbsolutePath()); env.put("ANDROID_SDK_ROOT", sdk.getAbsolutePath());
        env.put("GRADLE_USER_HOME", new File(root, "gradle-home").getAbsolutePath());
        env.put("TMPDIR", new File(root, "tmp").getAbsolutePath());
        env.put("JDK_JAVA_OPTIONS", "--add-modules=jdk.compiler,jdk.jartool,jdk.javadoc,jdk.jdeps,jdk.jlink,jdk.internal.opt" +
            " -Djava.home=" + jdk.getAbsolutePath() + " -Duser.home=" + new File(root, "home").getAbsolutePath() +
            " -Djava.io.tmpdir=" + new File(root, "tmp").getAbsolutePath() +
            " -Dext.net.resolvPath=" + new File(root, "resolv.conf").getAbsolutePath());
        Process process = builder.start();
        AtomicInteger group = new AtomicInteger();
        StringBuilder output = new StringBuilder();
        File log = new File(evidence, label + ".log");
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    Writer disk = new FileWriter(log)) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("AG_PROCESS_GROUP=")) {
                        group.compareAndSet(0, Integer.parseInt(line.substring("AG_PROCESS_GROUP=".length())));
                    } else {
                        disk.write(line + "\n"); disk.flush();
                        synchronized (output) { if (output.length() < 1024 * 1024) output.append(line).append('\n'); }
                    }
                }
            } catch (Exception error) { synchronized (output) { output.append("Reader: ").append(error); } }
        }, "runtime-output");
        reader.setDaemon(true); reader.start();
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } finally {
            signal(group.get(), OsConstants.SIGTERM);
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                signal(group.get(), OsConstants.SIGKILL); process.destroyForcibly();
            }
            reader.join(5000);
        }
        long elapsed = android.os.SystemClock.elapsedRealtime() - start;
        int exit = process.isAlive() ? -1 : process.exitValue();
        JSONObject record = new JSONObject().put("label", label).put("exit", exit).put("elapsedMs", elapsed)
            .put("timedOut", !finished).put("nativeGroup", group.get());
        writeText(new File(evidence, label + ".json"), record.toString(2));
        Log.i("AntigravityRuntime", label + ": exit=" + exit + " elapsedMs=" + elapsed);
        String text; synchronized (output) { text = output.toString(); }
        if (!finished) throw new IOException(label + " timed out\n" + text);
        return new Result(exit, text, elapsed);
    }

    private static void signal(int group, int signal) {
        if (group <= 0) return;
        try { Os.kill(-group, signal); } catch (Exception ignored) { /* Already exited. */ }
    }

    public static final class Result {
        public final int exit; public final String output; public final long elapsedMs;
        Result(int exit, String output, long elapsedMs) { this.exit = exit; this.output = output; this.elapsedMs = elapsedMs; }
        public void requireSuccess() throws IOException { if (exit != 0) throw new IOException("exit=" + exit + "\n" + output); }
    }
}
