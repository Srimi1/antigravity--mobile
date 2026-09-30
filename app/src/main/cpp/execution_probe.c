#include <stdio.h>
#include <string.h>
#include <unistd.h>

/* A real Android/Bionic ARM64 executable, not a simulated terminal. */
int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc == 2 && strcmp(argv[1], "version") == 0) {
        puts("execution-probe 1.0 | Android/Bionic | arm64-v8a");
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "exit-7") == 0) {
        fputs("intentional failure\n", stderr);
        return 7;
    }
    if (argc == 2 && strcmp(argv[1], "wait") == 0) {
        puts("ready");
        for (int i = 0; i < 60; ++i) { sleep(1); printf("tick %d\n", i + 1); }
        return 0;
    }
    fputs("supported: version, exit-7, wait\n", stderr);
    return 2;
}
