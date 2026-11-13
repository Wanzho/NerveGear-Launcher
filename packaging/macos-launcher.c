/* Keep the bundle executable alive; JLI owns the macOS main-thread run loop. */
#include <mach-o/dyld.h>
#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* JNI's jboolean is an unsigned byte. Keep this ABI identical to JDK 21 java.h. */
typedef int (*JliLaunch)(int, char **, int, const char **, int, const char **,
                        const char *, const char *, const char *, const char *,
                        unsigned char, unsigned char, unsigned char, int);
static JliLaunch launch;
static char **arguments;
static int argument_count;

/* Reused on JLI's worker-thread re-entry, exactly as jpackage's MacLauncher does. */
static int launch_vm(void) {
    return launch(argument_count, arguments, 0, NULL, 0, NULL,
                  "21", "21", "java", "java", 0, 0, 0, 0);
}

int main(int argc, char **argv) {
    if (launch) return launch_vm();
    char executable[PATH_MAX], resolved[PATH_MAX], library[PATH_MAX], jar[PATH_MAX];
    char classpath[PATH_MAX * 2], native_option[PATH_MAX + 32], jar_option[PATH_MAX + 32];
    uint32_t size = sizeof(executable);
    if (_NSGetExecutablePath(executable, &size) || !realpath(executable, resolved)) return 70;
    snprintf(executable, sizeof(executable), "%s", resolved);
    char *slash = strrchr(resolved, '/');
    if (!slash) return 70;
    *slash = '\0';
    int lib_length = snprintf(library, sizeof(library), "%s/../runtime/lib/libjli.dylib", resolved);
    int jar_length = snprintf(jar, sizeof(jar), "%s/../app/launcher.jar", resolved);
    if (lib_length < 0 || (size_t) lib_length >= sizeof(library)
        || jar_length < 0 || (size_t) jar_length >= sizeof(jar)) return 70;
    arguments = calloc((size_t) argc + 16, sizeof(char *));
    if (!arguments) return 71;
    arguments[argument_count++] = executable;
    if (argc > 1 && !strcmp(argv[1], "--sao-jvm")) {
        /* Bootstrap already fixed storage/update options and selected the JAR. */
        for (int i = 2; i < argc; i++) arguments[argument_count++] = argv[i];
    } else {
        arguments[argument_count++] = "--add-modules=javafx.controls,jdk.zipfs,jdk.crypto.ec,jdk.localedata";
        arguments[argument_count++] = "-Xdock:name=NerveGear启动器";
        if (argc == 3 && !strcmp(argv[1], "--sao-runtime-probe")) {
            /* Explicit diagnostic entry point: no bootstrap, toolkit or user data. */
            arguments[argument_count++] = "-Djava.awt.headless=true";
            int length = snprintf(classpath, sizeof(classpath), "%s:%s", jar, argv[2]);
            if (length < 0 || (size_t) length >= sizeof(classpath)) return 70;
            arguments[argument_count++] = "-cp";
            arguments[argument_count++] = classpath;
            arguments[argument_count++] = "RuntimeProbe";
            arguments[argument_count++] = "--hold";
        } else {
            int length = snprintf(classpath, sizeof(classpath), "%s/../app/macos-bootstrap.jar:%s", resolved, jar);
            if (length < 0 || (size_t) length >= sizeof(classpath)) return 70;
            snprintf(native_option, sizeof(native_option), "-Dsao.nativeLauncher=%s", executable);
            snprintf(jar_option, sizeof(jar_option), "-Dsao.bundledJar=%s", jar);
            arguments[argument_count++] = native_option;
            arguments[argument_count++] = jar_option;
            arguments[argument_count++] = "-cp";
            arguments[argument_count++] = classpath;
            arguments[argument_count++] = "org.jackhuang.hmcl.sao.SaoLauncherBootstrap";
            for (int i = 1; i < argc; i++) {
                if (strncmp(argv[i], "-psn_", 5)) arguments[argument_count++] = argv[i];
            }
        }
    }
    void *handle = dlopen(library, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) { fprintf(stderr, "Cannot load bundled JLI: %s\n", dlerror()); return 72; }
    launch = (JliLaunch) dlsym(handle, "JLI_Launch");
    if (!launch) { fprintf(stderr, "Cannot resolve JLI_Launch: %s\n", dlerror()); return 72; }
    /* Do not dlclose: JVM and JavaFX retain symbols for the process lifetime. */
    return launch_vm();
}
