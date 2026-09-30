#!/usr/bin/env python3
"""Prepare pinned Android-native runtime inputs. Never execute downloaded installers.

Generated binaries/data stay outside iCloud and Git. Desktop JDK files contribute
only architecture-independent compiler/tool classes; its executables and libraries
are explicitly excluded. All executable code in the APK must be Android ELF ARM64.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import urllib.request
import zipfile
import sys
sys.dont_write_bytecode = True
from reimage import rebuild

ROOT = Path(__file__).resolve().parents[2]
CACHE = Path.home() / ".cache/antigravity-mobile-runtime"
SOURCES = {
    "mojo-jre17.zip": (
        "https://github.com/MojoLauncher/android-openjdk-build-multiarch/releases/download/rolling/jre17-pojav.zip",
        "c0f1cf02a567b07ea1e3bba2114eec1657b6e51513c9af7958cae27e4f614440"),
    "temurin17.0.18-linux-arm64.tar.gz": (
        "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.18%2B8/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.18_8.tar.gz",
        "592a6702b3a07a0e0b82cb38aaab149bfce1b0c24d6b57ddb410bd9009333095"),
    "android-sdk-tools-static-aarch64.zip": (
        "https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip",
        "db1cea2c4454d5f9c5a802646b2d1cf560b4ee7badbe23e51ab8e1881bb50fc2"),
}
TOOL_MODULES = {"jdk.compiler", "jdk.jartool", "jdk.javadoc", "jdk.jdeps", "jdk.jlink", "jdk.internal.opt"}


def download(name, url, digest):
    path = CACHE / "research" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        temporary = path.with_suffix(path.suffix + ".partial")
        with urllib.request.urlopen(url, timeout=60) as src, temporary.open("wb") as dst:
            shutil.copyfileobj(src, dst, 1024 * 1024)
        temporary.replace(path)
    actual = hashlib.file_digest(path.open("rb"), "sha256").hexdigest()
    if actual != digest:
        raise ValueError(f"SHA-256 mismatch for {name}: {actual}. Do not silently accept rolling updates.")
    return path


def safe_name(name):
    p = PurePosixPath(name)
    if p.is_absolute() or ".." in p.parts:
        raise ValueError(f"Unsafe archive path: {name}")
    return str(p)


def elf_arm64(data):
    return data[:4] == b"\x7fELF" and data[4:6] == b"\x02\x01" and int.from_bytes(data[18:20], "little") == 183


def prepare(sdk, gradle, host_jdk):
    inputs = {name: download(name, *source) for name, source in SOURCES.items()}
    output = CACHE / "lab-generated"
    output.mkdir(parents=True, exist_ok=True)
    native_dir = output / "jniLibs/arm64-v8a"
    assets = output / "assets"
    native_dir.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    native_map = {}
    data_files = {}
    links = {}

    def native(path, data):
        if not elf_arm64(data):
            raise ValueError(f"Not an Android ARM64 ELF: {path}")
        original = PurePosixPath(path).name
        name = original if original.endswith(".so") else f"libag_{'original_java' if original == 'java' else original}.so"
        dest = native_dir / name
        if dest.exists() and dest.read_bytes() != data:
            raise ValueError(f"Conflicting native library: {name}")
        dest.write_bytes(data)
        native_map[path] = name

    with zipfile.ZipFile(inputs["mojo-jre17.zip"]) as outer:
        for archive in ("universal.tar.xz", "bin-arm64.tar.xz"):
            with tarfile.open(fileobj=io.BytesIO(outer.read(archive))) as ar:
                for entry in ar.getmembers():
                    name = "jdk/" + safe_name(entry.name)
                    if entry.isfile():
                        data = ar.extractfile(entry).read()
                        if data.startswith(b"\x7fELF"):
                            native(name, data)
                        else:
                            data_files[name] = data
                    elif entry.issym():
                        target = PurePosixPath(name).parent / entry.linkname
                        # The release only uses relative licence links. Normalize
                        # in memory, then require resolution inside the JDK data.
                        normalized = os.path.normpath(str(target))
                        if not normalized.startswith("jdk/"):
                            raise ValueError(f"External symlink: {entry.name}")
                        links[name] = normalized
                    elif not entry.isdir():
                        raise ValueError(f"Unsupported archive entry: {entry.name}")
        builder_commit = outer.read("version").decode().strip()
    while links:
        pending = {}
        for name, target in links.items():
            if target in data_files:
                data_files[name] = data_files[target]
            else:
                pending[name] = target
        if len(pending) == len(links):
            raise ValueError(f"Unresolved licence links: {pending}")
        links = pending

    # The Android runtime has java.compiler interfaces but omits javac/jlink.
    # Supply only the matching Java 17.0.18 tool modules from Temurin's JMODs.
    # Linux-aarch64 module metadata matches the Android port's module image.
    # No executable/native file from the desktop JDK is copied into the APK.
    found = set()
    with tarfile.open(inputs["temurin17.0.18-linux-arm64.tar.gz"], "r:gz") as ar:
        for entry in ar:
            module = PurePosixPath(entry.name).stem
            if entry.isfile() and "/jmods/" in entry.name and module in TOOL_MODULES:
                classes = io.BytesIO()
                with zipfile.ZipFile(io.BytesIO(ar.extractfile(entry).read())) as jmod, \
                        zipfile.ZipFile(classes, "w", zipfile.ZIP_DEFLATED) as jar:
                    for e in jmod.infolist():
                        if e.filename.startswith("classes/") and not e.is_dir():
                            name = safe_name(e.filename.removeprefix("classes/"))
                            jar.writestr(name, jmod.read(e))
                data_files[f"jdk/tool-modules/{module}.jar"] = classes.getvalue()
                found.add(module)
            elif entry.isfile() and "/legal/" in entry.name:
                name = "jdk/tool-legal/" + safe_name(entry.name.split("/legal/", 1)[1])
                data_files[name] = ar.extractfile(entry).read()
    if found != TOOL_MODULES:
        raise ValueError(f"Missing tool modules: {TOOL_MODULES - found}")
    image = rebuild(data_files, CACHE / "image-assembly", host_jdk)
    for tool in ("java", "javac", "jar", "jlink", "javadoc"):
        native_map[f"jdk/bin/{tool}"] = "libag_java.so"

    with zipfile.ZipFile(inputs["android-sdk-tools-static-aarch64.zip"]) as ar:
        for name in ("aapt", "aapt2", "aidl", "dexdump", "split-select", "zipalign"):
            native(f"sdk/build-tools/36.0.0/{name}", ar.read(f"build-tools/{name}"))
    # These SDK jars/resources are platform data. None of the desktop tools run.
    for directory in (sdk / "platforms/android-36", sdk / "build-tools/36.0.0"):
        for path in directory.rglob("*"):
            if path.is_file() and (path.suffix in {".jar", ".xml", ".properties", ".prop", ".aidl", ".txt"} or path.name == "NOTICE.txt"):
                data_files["sdk/" + path.relative_to(sdk).as_posix()] = path.read_bytes()
    for path in gradle.rglob("*"):
        if path.is_file() and (path.is_relative_to(gradle / "lib") or path.name in {"LICENSE", "NOTICE", "README"}):
            data_files["gradle/" + path.relative_to(gradle).as_posix()] = path.read_bytes()
    sample = io.BytesIO()
    with zipfile.ZipFile(sample, "w", zipfile.ZIP_DEFLATED) as ar:
        for path in (ROOT / "samples/HelloPhone").rglob("*"):
            relative = path.relative_to(ROOT / "samples/HelloPhone")
            if path.is_file() and not any(p in {"build", ".gradle", ".kotlin", ".git"} for p in relative.parts) and path.name != "local.properties":
                ar.writestr(relative.as_posix(), path.read_bytes())
    (assets / "hello-phone.zip").write_bytes(sample.getvalue())
    # Add the D8/apksigner front-door aliases so SDK inspection sees complete tools.
    # AGP invokes their jar implementations rather than these aliases.
    native_map["sdk/build-tools/36.0.0/d8"] = "libag_java.so"
    native_map["sdk/build-tools/36.0.0/apksigner"] = "libag_java.so"
    with zipfile.ZipFile(assets / "runtime-data.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=6) as ar:
        for name, data in sorted(data_files.items()):
            ar.writestr(name, data)
    release = data_files["jdk/release"].decode()
    manifest = {
        "profile": "Android-Bionic-JRE17-with-Java-tool-modules",
        "javaVersion": "17.0.18",
        "release": release,
        "builderCommit": builder_commit,
        "toolModules": sorted(TOOL_MODULES),
        "moduleImage": image,
        "nativeSdkToolsVersion": "35.0.2",
        "sdkPlatformVersion": "36",
        "sources": {n: {"url": s[0], "sha256": s[1]} for n, s in SOURCES.items()},
        "nativeLinks": native_map,
        "dataSha256": hashlib.file_digest((assets / "runtime-data.zip").open("rb"), "sha256").hexdigest(),
    }
    (assets / "runtime-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"output": str(output), "dataBytes": (assets / "runtime-data.zip").stat().st_size,
        "nativeBytes": sum(p.stat().st_size for p in native_dir.iterdir()), "nativeFiles": len(list(native_dir.iterdir()))}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--gradle", type=Path, required=True, help="An extracted official Gradle 8.13 distribution")
    parser.add_argument("--host-jdk", type=Path, required=True, help="Development JDK used only to assemble the initial APK's module image")
    options = parser.parse_args()
    prepare(options.sdk.resolve(), options.gradle.resolve(), options.host_jdk.resolve())
