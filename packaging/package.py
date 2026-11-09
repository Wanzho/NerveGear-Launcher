#!/usr/bin/env python3
"""Build four self-contained launcher distributions on macOS, without opening them."""

import argparse
import gzip
import hashlib
import json
import os
import plistlib
import shutil
import struct
import subprocess
import sys
import tarfile
import time
import zipfile
from pathlib import Path

from pe_icon import replace_icon

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
HEADLESS = "-Djava.awt.headless=true"
TARGETS = ("macos-arm64", "macos-x64", "windows-x64", "linux-x64")
PRODUCT = "SAO Oath Launcher"
WINDOWS_NAME = "刀剑神域·誓约 启动器"
MODULES = (
    "java.base,java.net.http,java.sql,javafx.controls,javafx.swing,jdk.attach,"
    "jdk.management,jdk.unsupported,jdk.charsets,jdk.crypto.ec,jdk.localedata,jdk.zipfs"
)
ADD_MODULES = "javafx.controls,jdk.zipfs,jdk.crypto.ec,jdk.localedata"


def run(command, *, log=None, cwd=None):
    """Execute a checked command with the headless build environment."""
    print("Running " + str(command[0]) + (" " + str(command[1]) if len(command) > 1 else ""), flush=True)
    env = os.environ.copy()
    env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + " " + HEADLESS).strip()
    if log:
        with Path(log).open("w") as stream:
            subprocess.run([str(item) for item in command], cwd=cwd, env=env,
                           stdout=stream, stderr=subprocess.STDOUT, check=True)
    else:
        subprocess.run([str(item) for item in command], cwd=cwd, env=env, check=True)


def digest(path):
    """Hash large archives without holding their payload in memory."""
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def check_space(path):
    """Stop before new work when the user-defined 20 GiB floor is reached."""
    free = shutil.disk_usage(path).free
    if free < 20 * 1024 ** 3:
        raise RuntimeError(f"Free space {free / 1024 ** 3:.2f} GiB is below the 20 GiB stop threshold")


def linked_copy(source, destination):
    """Share immutable large JAR bytes across same-volume staging directories."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if os.path.samefile(source, destination):
            return
        destination.unlink()
    try:
        os.link(source, destination)
    except OSError:
        # Different volumes can still use the script; the disk guard remains active.
        check_space(destination.parent)
        shutil.copy2(source, destination)


def obtain_toolchains(work, lock):
    """Download pinned official archives and extract only required target components."""
    downloads = work / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    homes = {}
    for target, asset in lock["targets"].items():
        check_space(work)
        archive = downloads / asset["filename"]
        if not archive.exists() or digest(archive) != asset["sha256"]:
            temporary = Path(str(archive) + ".part")
            run(["curl", "--fail", "--location", "--retry", "3", "--connect-timeout", "20",
                 "--max-time", "1800", "--output", temporary, asset["url"]])
            if temporary.stat().st_size != asset["size"] or digest(temporary) != asset["sha256"]:
                raise RuntimeError("Official archive SHA-256 mismatch: " + target)
            temporary.replace(archive)
        root = work / "toolchains" / target
        existing = list(root.glob("*/jmods/java.base.jmod"))
        if not existing:
            root.mkdir(parents=True, exist_ok=True)
            if archive.suffix == ".zip":
                with zipfile.ZipFile(archive) as source:
                    for item in source.infolist():
                        if "/jmods/" in item.filename:
                            source.extract(item, root)
            else:
                with tarfile.open(archive) as source:
                    source.extractall(root, members=None if target == "macos-arm64" else
                                      (item for item in source if "/jmods/" in item.name))
            existing = list(root.glob("*/jmods/java.base.jmod"))
        if len(existing) != 1:
            raise RuntimeError("Ambiguous or incomplete toolchain: " + target)
        homes[target] = existing[0].parent.parent
    return homes


def build_jar(args, work, host):
    """Serialize the one full Gradle build and preserve all user-selected pack inputs."""
    if args.jar:
        return args.jar.resolve()
    lockdir = Path.home() / "Developer/sao-launcher-runs/build.lock"
    lockdir.parent.mkdir(parents=True, exist_ok=True)
    while True:
        try:
            lockdir.mkdir()
            break
        except FileExistsError:
            if time.time() - lockdir.stat().st_mtime > 40 * 60:
                # User explicitly defined forty minutes as the stale-lock boundary.
                shutil.rmtree(lockdir)
            else:
                print("Build slot occupied; checking again in two minutes", flush=True)
                time.sleep(120)
    try:
        attempts_file = work / "full-gradle-builds.json"
        count = json.loads(attempts_file.read_text())["count"] if attempts_file.exists() else 0
        if count >= 3:
            raise RuntimeError("Maximum three full Gradle builds reached")
        attempts_file.write_text(json.dumps({"count": count + 1}) + "\n")
        env_home = os.environ.get("GRADLE_USER_HOME")
        if not env_home:
            os.environ["GRADLE_USER_HOME"] = str(Path.home() / ".cache/sao-launcher-gradle")
        os.environ["JAVA_HOME"] = str(host)
        registration = Path.home() / ".config/sao-launcher/microsoft-auth-id"
        if registration.is_file() and not os.environ.get("MICROSOFT_AUTH_ID"):
            os.environ["MICROSOFT_AUTH_ID"] = registration.read_text().strip()
        command = [REPO / "gradlew", "--offline", "--no-daemon", "--max-workers=2", HEADLESS,
                   "-Dorg.gradle.jvmargs=-Xmx3g -Djava.awt.headless=true -Dfile.encoding=UTF-8",
                   "-I", HERE / "headless.gradle", ":HMCL:jar", "-PsaoPack=" + str(args.pack),
                   "-PsaoPackBaseline=" + str(args.baseline), "-PsaoPackOffline=true"]
        run(command, log=args.output / "gradle-build.log", cwd=REPO)
    finally:
        lockdir.rmdir()
    jars = list((REPO / "HMCL/build/libs").glob("SAO-Oath-Launcher-*.jar"))
    if len(jars) != 1:
        raise RuntimeError("Expected one plain launcher JAR; pass --jar to select one")
    return jars[0]


def make_icons(work):
    """Derive every platform icon from the single replaceable PNG source."""
    root = work / "icons"
    root.mkdir(exist_ok=True)
    png = HERE / "icon.png"
    data = png.read_bytes()
    ico = root / "launcher.ico"
    width, height = struct.unpack_from(">II", data, 16)
    if width > 256 or height > 256:
        scaled = root / "icon-256.png"
        run(["sips", "-z", "256", "256", png, "--out", scaled])
        data, width, height = scaled.read_bytes(), 256, 256
    ico.write_bytes(struct.pack("<HHHBBBBHHII", 0, 1, 1, width % 256, height % 256,
                                0, 0, 1, 32, len(data), 22) + data)
    iconset = root / "launcher.iconset"
    iconset.mkdir(exist_ok=True)
    for size in (16, 32, 128, 256, 512):
        for scale in (1, 2):
            destination = iconset / f"icon_{size}x{size}{'@2x' if scale == 2 else ''}.png"
            run(["sips", "-z", str(size * scale), str(size * scale), png, "--out", destination])
    icns = root / "launcher.icns"
    # PNG-backed ICNS chunks avoid iconutil's image-service sandbox dependency.
    chunks = []
    for kind, name in ((b"icp4", "icon_16x16.png"), (b"icp5", "icon_32x32.png"),
                       (b"icp6", "icon_32x32@2x.png"), (b"ic07", "icon_128x128.png"),
                       (b"ic08", "icon_256x256.png"), (b"ic09", "icon_512x512.png"),
                       (b"ic10", "icon_512x512@2x.png")):
        payload = (iconset / name).read_bytes()
        chunks.append(kind + struct.pack(">I", len(payload) + 8) + payload)
    body = b"".join(chunks)
    icns.write_bytes(b"icns" + struct.pack(">I", len(body) + 8) + body)
    return ico, icns


def runtime(host, target_home, destination, log):
    """Cross-link using exclusively the target platform's JMODs, never host modules."""
    if destination.exists():
        shutil.rmtree(destination)
    run([host / "bin/jlink", "-J" + HEADLESS, "--module-path", target_home / "jmods",
         "--add-modules", MODULES, "--strip-debug", "--compress=2", "--no-header-files",
         "--no-man-pages", "--output", destination], log=log)


def sign_app(app, evidence):
    """Ad-hoc sign nested Mach-O code from the inside out, then seal the bundle."""
    for path in sorted(app.rglob("*"), key=lambda item: len(item.parts), reverse=True):
        if path.is_file() and not path.is_symlink():
            with path.open("rb") as stream:
                magic = stream.read(4)
            if magic in (b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe", b"\xbe\xba\xfe\xca"):
                run(["codesign", "--force", "--sign", "-", "--timestamp=none", path])
    run(["codesign", "--force", "--sign", "-", "--timestamp=none", app])
    run(["codesign", "--verify", "--deep", "--strict", "--verbose=2", app], log=evidence)


def notices(destination, lock):
    """Ship launcher license, exact runtime provenance and the existing attribution text."""
    directory = destination / "licenses"
    directory.mkdir(parents=True, exist_ok=True)
    shutil.copy2(REPO / "LICENSE", directory / "LICENSE-launcher-GPL-3.0.txt")
    shutil.copy2(HERE / "THIRD-PARTY-NOTICES.txt", directory / "THIRD-PARTY-NOTICES.txt")
    shutil.copytree(HERE / "dependency-notices", directory / "dependency-notices", dirs_exist_ok=True)
    shutil.copy2(HERE / "runtimes.lock.json", directory / "runtimes.lock.json")
    shutil.copy2(HERE / "README-zh.txt", destination / "README-zh.txt")


def mac_app(target, host, home, dist, work, jar, icns, evidence, lock):
    """Assemble and sign a target-architecture app bundle without launching it."""
    root = dist / target
    app = root / (PRODUCT + ".app")
    contents = app / "Contents"
    for name in ("MacOS", "Resources", "app"):
        (contents / name).mkdir(parents=True, exist_ok=True)
    runtime(host, home, contents / "runtime", evidence / (target + "-jlink.log"))
    architecture = "arm64" if target.endswith("arm64") else "x86_64"
    run(["clang", "-arch", architecture, "-mmacosx-version-min=11.0", "-Os", "-Wall", "-Wextra",
         HERE / "macos-launcher.c", "-o", contents / "MacOS/SAO-Oath-Launcher"])
    linked_copy(jar, contents / "app/launcher.jar")
    shutil.copy2(icns, contents / "Resources/launcher.icns")
    info = {"CFBundleExecutable": "SAO-Oath-Launcher", "CFBundleIdentifier": "com.saooath.launcher",
            "CFBundleName": PRODUCT, "CFBundleDisplayName": "刀剑神域·誓约",
            "CFBundleIconFile": "launcher.icns", "CFBundlePackageType": "APPL",
            "CFBundleShortVersionString": "0.1.0", "CFBundleVersion": "1",
            "LSMinimumSystemVersion": "11.0", "NSHighResolutionCapable": True,
            "NSHumanReadableCopyright": "Copyright 2013-2025 huangyuhui and HMCL contributors; SAO fork contributors"}
    (contents / "Info.plist").write_bytes(plistlib.dumps(info))
    notices(contents / "Resources", lock)
    sign_app(app, evidence / (target + "-codesign.txt"))
    check_space(work)
    image = dist / f"SAO-Oath-Launcher-0.1.0-{target}.dmg"
    if image.exists():
        image.unlink()
    # -srcfolder creates an image directly; no Finder, mount or .app execution occurs.
    run(["hdiutil", "create", "-format", "UDZO", "-volname", PRODUCT, "-srcfolder", root,
         "-ov", image], log=evidence / (target + "-dmg.log"))
    run(["hdiutil", "verify", image], log=evidence / (target + "-dmg-verify.txt"))
    return contents / "runtime", contents / "app/launcher.jar", app


def portable(target, host, home, dist, work, jar, ico, evidence, lock):
    """Assemble a native Windows executable or Linux shell entry point with a runtime."""
    root = work / "staging" / target / "SAO-Oath-Launcher"
    (root / "app").mkdir(parents=True, exist_ok=True)
    runtime(host, home, root / "runtime", evidence / (target + "-jlink.log"))
    linked_copy(jar, root / "app/launcher.jar")
    notices(root, lock)
    if target == "windows-x64":
        # This PE32+ launcher comes from the exact target JDK, not a 32-bit wrapper.
        with zipfile.ZipFile(home / "jmods/jdk.jpackage.jmod") as source:
            template = source.read("classes/jdk/jpackage/internal/resources/jpackageapplauncherw.exe")
        executable = root / (WINDOWS_NAME + ".exe")
        executable.write_bytes(template)
        replace_icon(executable, ico)
        (root / "app" / (WINDOWS_NAME + ".cfg")).write_text(
            "[Application]\napp.name=SAO Oath Launcher\napp.version=0.1.0\n"
            "app.mainjar=$APPDIR/launcher.jar\n\n[JavaOptions]\n"
            "java-options=--add-modules=" + ADD_MODULES + "\n", encoding="utf-8")
        # Remove the earlier ASCII development basename, when reusing staging.
        for obsolete in (root / "SAO-Oath-Launcher.exe", root / "app/SAO-Oath-Launcher.cfg"):
            if obsolete.exists():
                obsolete.unlink()
        archive = dist / "SAO-Oath-Launcher-0.1.0-windows-x64.zip"
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as output:
            for path in sorted(root.rglob("*")):
                if path.is_file():
                    output.write(path, path.relative_to(root.parent))
    else:
        script = root / "SAO-Oath-Launcher.sh"
        script.write_text("#!/bin/sh\nset -eu\nROOT=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\n"
                          "exec \"$ROOT/runtime/bin/java\" --add-modules=" + ADD_MODULES
                          + " -jar \"$ROOT/app/launcher.jar\" \"$@\"\n")
        script.chmod(0o755)
        shutil.copy2(HERE / "icon.png", root / "icon.png")
        with tarfile.open(dist / "SAO-Oath-Launcher-0.1.0-linux-x64.tar.gz", "w:gz", compresslevel=1) as output:
            output.add(root, arcname=root.name)
    return root / "runtime", root / "app/launcher.jar", root


def probe(host, paths, work, evidence):
    """Compile an isolated class-load probe and run only the two local macOS runtimes."""
    directory = work / "probe"
    classes = directory / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    annotations = list((Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".cache/sao-launcher-gradle")))
                        / "caches/modules-2/files-2.1/org.jetbrains/annotations").rglob("*.jar"))
    annotation = next(item for item in annotations if "26.1.0" in str(item))
    run([host / "bin/javac", "-J" + HEADLESS, "--release", "17", "-cp", annotation,
         "-d", classes, HERE / "RuntimeProbe.java"])
    run([host / "bin/jar", "-J" + HEADLESS, "--create", "--file", directory / "runtime-probe.jar", "-C", classes, "."])
    for target in ("macos-arm64", "macos-x64"):
        runtime_home, jar, _ = paths[target]
        run([runtime_home / "bin/java", HEADLESS, "-version"], log=evidence / (target + "-java-version.txt"))
        run([runtime_home / "bin/java", HEADLESS, "--list-modules"], log=evidence / (target + "-modules.txt"))
        run([runtime_home / "bin/java", HEADLESS, "--add-modules=" + ADD_MODULES, "-cp",
             str(jar) + os.pathsep + str(directory / "runtime-probe.jar"), "RuntimeProbe"],
            log=evidence / (target + "-classload.txt"))


def finalize(dist, output):
    """Bundle the committed corresponding source and hash every deliverable."""
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()
    (dist / "SOURCE-COMMIT.txt").write_text(commit + "\n")
    source_path = dist / "SAO-Oath-Launcher-source.tar.gz"
    with source_path.open("wb") as destination:
        source = subprocess.Popen(["git", "archive", "--format=tar", "--prefix=SAO-Oath-Launcher-source/", "HEAD"],
                                  cwd=REPO, stdout=subprocess.PIPE)
        with gzip.GzipFile(fileobj=destination, mode="wb", compresslevel=6, mtime=0) as compressed:
            shutil.copyfileobj(source.stdout, compressed)
        if source.wait() != 0:
            raise RuntimeError("Corresponding source archive failed")
    records = []
    for path in sorted(dist.iterdir()):
        if path.is_file() and path.name != "SHA256SUMS":
            records.append({"file": path.name, "size": path.stat().st_size, "sha256": digest(path)})
    (dist / "SHA256SUMS").write_text("".join(item["sha256"] + "  " + item["file"] + "\n" for item in records))
    (output / "artifacts.json").write_text(json.dumps({"commit": commit, "files": records}, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path.home() / "Developer/sao-launcher-runs/package1")
    parser.add_argument("--pack", type=Path, default=Path.home() / "Downloads/SAO OATH0.8.zip")
    parser.add_argument("--baseline", type=Path, default=Path.home() / "Developer/sao-launcher-runs/jarname1/input")
    parser.add_argument("--jar", type=Path, help="Reuse an already built JAR; performs no Gradle invocation")
    parser.add_argument("--finalize-only", action="store_true", help="Only refresh the committed source archive and checksums")
    args = parser.parse_args()
    if sys.platform != "darwin" or os.uname().machine != "arm64":
        raise RuntimeError("The all-target packaging host must be an Apple Silicon Mac")
    args.output = args.output.resolve()
    work, dist, evidence = (args.output / name for name in ("work", "dist", "evidence"))
    for directory in (work, dist, evidence):
        directory.mkdir(parents=True, exist_ok=True)
    check_space(work)
    if args.finalize_only:
        finalize(dist, args.output)
        return
    lock = json.loads((HERE / "runtimes.lock.json").read_text())
    homes = obtain_toolchains(work, lock)
    host = homes["macos-arm64"]
    jar = build_jar(args, work, host)
    ico, icns = make_icons(work)
    linked_copy(jar, dist / jar.name)
    paths = {}
    for target in TARGETS:
        check_space(work)
        if target.startswith("macos"):
            paths[target] = mac_app(target, host, homes[target], dist, work, jar, icns, evidence, lock)
        else:
            paths[target] = portable(target, host, homes[target], dist, work, jar, ico, evidence, lock)
        print("ARTIFACT_READY " + target, flush=True)
    probe(host, paths, work, evidence)
    notices(dist, lock)
    finalize(dist, args.output)
    print("ALL_TARGETS_COMPLETE " + str(dist), flush=True)


if __name__ == "__main__":
    main()
