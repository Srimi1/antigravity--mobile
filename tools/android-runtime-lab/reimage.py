"""Assemble a consistent Java module image from pinned, native-free classes.

ModuleHashes describe one particular vendor image. Recompute image metadata
after combining the Android runtime's classes with the missing compiler tools.
Native Android libraries and Java implementation classes are not modified.
"""
import io
from pathlib import Path
import shutil
import struct
import subprocess
import zipfile


def without_vendor_hashes(data):
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Expected module-info.class")
    offset = 10
    strings = {}
    count = struct.unpack_from(">H", data, 8)[0]
    index = 1
    while index < count:
        tag = data[offset]; offset += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, offset)[0]; offset += 2
            strings[index] = data[offset:offset + length].decode("utf-8"); offset += length
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): offset += 4
        elif tag in (5, 6): offset += 8; index += 1
        elif tag in (7, 8, 16, 19, 20): offset += 2
        elif tag == 15: offset += 3
        else: raise ValueError(f"Unknown constant-pool tag {tag}")
        index += 1
    offset += 6  # access, this class, superclass
    interfaces = struct.unpack_from(">H", data, offset)[0]
    offset += 2 + interfaces * 2
    for _ in range(2):  # fields, methods
        members = struct.unpack_from(">H", data, offset)[0]; offset += 2
        for _ in range(members):
            attributes = struct.unpack_from(">H", data, offset + 6)[0]; offset += 8
            for _ in range(attributes):
                length = struct.unpack_from(">I", data, offset + 2)[0]; offset += 6 + length
    head = data[:offset]
    attributes = struct.unpack_from(">H", data, offset)[0]; offset += 2
    kept = []
    for _ in range(attributes):
        name, length = struct.unpack_from(">HI", data, offset)
        attribute = data[offset:offset + 6 + length]; offset += 6 + length
        if strings[name] != "ModuleHashes": kept.append(attribute)
    if offset != len(data): raise ValueError("Malformed module class")
    return head + struct.pack(">H", len(kept)) + b"".join(kept)


def rebuild(data_files, work, host_jdk):
    work = Path(work)
    if work.exists(): shutil.rmtree(work)
    work.mkdir(parents=True)
    original = work / "original.modules"
    original.write_bytes(data_files["jdk/lib/modules"])
    extracted = work / "extracted"
    subprocess.run([str(host_jdk / "bin/jimage"), "extract", "--dir", str(extracted), str(original)], check=True)
    jars = work / "jars"; jars.mkdir()
    modules = set()

    def write_module(module, entries):
        modules.add(module)
        with zipfile.ZipFile(jars / (module + ".jar"), "w", zipfile.ZIP_DEFLATED) as jar:
            for name, content in entries:
                if name == "module-info.class": content = without_vendor_hashes(content)
                # These classes are generated per image by jlink's system-modules plugin.
                if module == "java.base" and (name.startswith("jdk/internal/module/SystemModules$") or name == "jdk/internal/module/SystemModulesMap.class"):
                    continue
                info = zipfile.ZipInfo(name, (2026, 1, 20, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
                jar.writestr(info, content)
    for directory in extracted.iterdir():
        if directory.is_dir():
            write_module(directory.name, ((p.relative_to(directory).as_posix(), p.read_bytes()) for p in sorted(directory.rglob("*")) if p.is_file()))
    tool_names = [n for n in data_files if n.startswith("jdk/tool-modules/") and n.endswith(".jar")]
    for name in tool_names:
        with zipfile.ZipFile(io.BytesIO(data_files[name])) as jar:
            write_module(Path(name).stem, ((n, jar.read(n)) for n in jar.namelist() if not n.endswith("/")))
    output = work / "image"
    subprocess.run([str(host_jdk / "bin/jlink"), "--module-path", str(jars), "--add-modules", ",".join(sorted(modules)),
        "--output", str(output), "--no-header-files", "--no-man-pages", "--disable-plugin=generate-jli-classes"], check=True)
    data_files["jdk/lib/modules"] = (output / "lib/modules").read_bytes()
    for name in tool_names: del data_files[name]
    # Preserve the native Android VM's real version; record the assembly tool separately.
    release = data_files["jdk/release"].decode()
    release = "\n".join(('MODULES="' + " ".join(sorted(modules)) + '"') if line.startswith("MODULES=") else line for line in release.splitlines()) + "\n"
    data_files["jdk/release"] = release.encode()
    return {"modules": sorted(modules), "assembler": subprocess.check_output([str(host_jdk / "bin/jlink"), "--version"], text=True).strip()}
