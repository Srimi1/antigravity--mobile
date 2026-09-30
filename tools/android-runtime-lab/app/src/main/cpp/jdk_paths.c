#include <dlfcn.h>
#include <limits.h>
#include <link.h>
#include <malloc.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* This port uses HotSpot's untagged native pointers. The compatibility setting
 * is confined to the lab's worker processes, using the public NDK mallopt API.
 * It does not establish MTE compatibility for a shipping runtime. */
__attribute__((constructor)) static void heap_compatibility(void) {
    mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE);
}

/* PackageManager flattens native libraries into lib/arm64. HotSpot derives
 * its initial boot-module path from loader metadata before parsing -Djava.home.
 * Report the existing JDK-layout alias for this one library; the executable
 * mapping and all other dladdr metadata remain the real APK mapping. */
static const char *layout_alias(const char *filename) {
    const char *home = getenv("JAVA_HOME");
    if (filename && home) {
        const char *base = strrchr(filename, '/');
        base = base ? base + 1 : filename;
        if (!strcmp(base, "libjvm.so")) {
            static _Thread_local char alias[PATH_MAX];
            int length = snprintf(alias, sizeof(alias), "%s/lib/server/libjvm.so", home);
            if (length > 0 && length < (int)sizeof(alias) && access(alias, R_OK) == 0) return alias;
        }
    }
    return filename;
}

int dladdr(const void *address, Dl_info *info) {
    typedef int (*real_fn)(const void *, Dl_info *);
    real_fn real = (real_fn)dlsym(RTLD_NEXT, "dladdr");
    if (!real) return 0;
    int result = real(address, info);
    if (result) info->dli_fname = layout_alias(info->dli_fname);
    return result;
}

struct callback_context {
    int (*callback)(struct dl_phdr_info *, size_t, void *);
    void *data;
};
static int translated_callback(struct dl_phdr_info *info, size_t size, void *data) {
    struct callback_context *context = data;
    struct dl_phdr_info logical = *info;
    logical.dlpi_name = layout_alias(info->dlpi_name);
    return context->callback(&logical, size, context->data);
}
int dl_iterate_phdr(int (*callback)(struct dl_phdr_info *, size_t, void *), void *data) {
    typedef int (*real_fn)(int (*)(struct dl_phdr_info *, size_t, void *), void *);
    real_fn real = (real_fn)dlsym(RTLD_NEXT, "dl_iterate_phdr");
    if (!real) return -1;
    struct callback_context context = {callback, data};
    return real(translated_callback, &context);
}
