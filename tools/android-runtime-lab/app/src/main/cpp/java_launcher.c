#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <limits.h>

/* An Android/Bionic executable installed by PackageManager. Each JVM runs in
 * its own process, rather than embedding a second VM in the Android UI process.
 * JLI_Launch is OpenJDK's public native launcher entry point. */
typedef int (*launch_fn)(int, char **, int, const char **, int, const char **,
                        const char *, const char *, const char *, const char *,
                        unsigned char, unsigned char, unsigned char, int);

int main(int argc, char **argv) {
    const char *native = getenv("AG_NATIVE_LIB");
    const char *home = getenv("JAVA_HOME");
    if (!native || !home) { fputs("JAVA_HOME and AG_NATIVE_LIB are required\n", stderr); return 2; }
    const char *group = getenv("AG_PROCESS_GROUP");
    char group_value[32];
    if (!group) {
        if (setpgid(0, 0) != 0) { perror("setpgid"); return 5; }
        snprintf(group_value, sizeof(group_value), "%ld", (long)getpid());
        setenv("AG_PROCESS_GROUP", group_value, 1);
        group = group_value;
        printf("AG_PROCESS_GROUP=%s\n", group);
        fflush(stdout);
    }
    if (argc >= 3 && !strcmp(argv[1], "--native")) {
        char executable[PATH_MAX];
        if (!realpath(argv[2], executable) || strncmp(executable, native, strlen(native)) ||
            executable[strlen(native)] != '/') {
            fputs("Native executable must belong to this APK\n", stderr); return 2;
        }
        execv(executable, argv + 2);
        perror("execv"); return 3;
    }
    char path[4096];
    if (snprintf(path, sizeof(path), "%s/libjli.so", native) >= (int)sizeof(path)) return 2;
    void *library = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!library) { fprintf(stderr, "OpenJDK launcher: %s\n", dlerror()); return 3; }
    launch_fn launch = (launch_fn)dlsym(library, "JLI_Launch");
    if (!launch) { fprintf(stderr, "JLI_Launch: %s\n", dlerror()); return 3; }
    const char *name = strrchr(argv[0], '/');
    name = name ? name + 1 : argv[0];
    const char *module = NULL;
    if (!strcmp(name, "javac")) module = "jdk.compiler/com.sun.tools.javac.Main";
    if (!strcmp(name, "jar")) module = "jdk.jartool/sun.tools.jar.Main";
    if (!strcmp(name, "jlink")) module = "jdk.jlink/jdk.tools.jlink.internal.Main";
    if (!strcmp(name, "javadoc")) module = "jdk.javadoc/jdk.javadoc.internal.tool.Main";
    /* JDK_JAVA_OPTIONS preprocessing belongs to OpenJDK's main() wrapper,
     * outside JLI_Launch. Pass the runtime paths explicitly for every JVM,
     * including compiler workers and Gradle's child process. */
    char java_home[4096], user_home[4096], temp[4096], resolver[4096], modules[4096];
    const char *user = getenv("AG_USER_HOME");
    const char *tmp = getenv("TMPDIR");
    const char *resolv = getenv("AG_RESOLV_PATH");
    if (snprintf(java_home, sizeof(java_home), "-Djava.home=%s", home) >= (int)sizeof(java_home) ||
        snprintf(user_home, sizeof(user_home), "-Duser.home=%s", user ? user : home) >= (int)sizeof(user_home) ||
        snprintf(temp, sizeof(temp), "-Djava.io.tmpdir=%s", tmp ? tmp : home) >= (int)sizeof(temp) ||
        snprintf(resolver, sizeof(resolver), "-Dext.net.resolvPath=%s", resolv ? resolv : "/etc/resolv.conf") >= (int)sizeof(resolver) ||
        snprintf(modules, sizeof(modules), "%s/tool-modules", home) >= (int)sizeof(modules)) return 2;
    char **args = calloc((size_t)argc + 12, sizeof(char *));
    if (!args) return 4;
    int count = 0;
    args[count++] = argv[0]; args[count++] = java_home; args[count++] = user_home;
    args[count++] = temp; args[count++] = resolver;
    args[count++] = "--add-modules=jdk.compiler,jdk.jartool,jdk.javadoc,jdk.jdeps,jdk.jlink,jdk.internal.opt";
    if (module) { args[count++] = "--module"; args[count++] = (char *)module; }
    for (int i = 1; i < argc; i++) args[count++] = argv[i];
    const char *version = getenv("AG_JAVA_VERSION");
    int result = launch(count, args, 0, NULL, 0, NULL, version ? version : "17",
                        "17", name, "openjdk", 0, 1, 0, 0);
    free(args);
    return result;
}
