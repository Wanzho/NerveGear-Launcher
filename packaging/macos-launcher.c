/* Native app bootstrap only: resolves its own bundle and executes the bundled JVM. */
#include <mach-o/dyld.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char executable[PATH_MAX], resolved[PATH_MAX], java[PATH_MAX], jar[PATH_MAX];
    uint32_t size = sizeof(executable);
    if (_NSGetExecutablePath(executable, &size) || !realpath(executable, resolved)) return 70;
    char *slash = strrchr(resolved, '/');
    if (!slash) return 70;
    *slash = '\0';
    if (snprintf(java, sizeof(java), "%s/../runtime/bin/java", resolved) >= sizeof(java)
        || snprintf(jar, sizeof(jar), "%s/../app/launcher.jar", resolved) >= sizeof(jar)) return 70;
    char **arguments = calloc((size_t) argc + 8, sizeof(char *));
    if (!arguments) return 71;
    arguments[0] = java;
    arguments[1] = "--add-modules=javafx.controls,jdk.zipfs,jdk.crypto.ec,jdk.localedata";
    arguments[2] = "-Xdock:name=刀剑神域·誓约";
    arguments[3] = "-jar";
    arguments[4] = jar;
    int count = 5;
    for (int i = 1; i < argc; i++) {
        /* Older Finder versions can pass a process serial-number argument. */
        if (strncmp(argv[i], "-psn_", 5)) arguments[count++] = argv[i];
    }
    execv(java, arguments);
    perror("Cannot execute bundled Java runtime");
    free(arguments);
    return 72;
}
