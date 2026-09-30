#!/usr/bin/env python3
"""Package reproducible source without caches, APKs, device records or private keys."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parent.parent
destination = root / "dist" / "antigravity-mobile-source.zip"
excluded = {"build", ".gradle", ".kotlin", ".signing", "dist", ".git", ".idea", "__pycache__"}
destination.parent.mkdir(exist_ok=True)
with ZipFile(destination, "w", ZIP_DEFLATED) as archive:
    for file in sorted(root.rglob("*")):
        relative = file.relative_to(root)
        if not file.is_file() or excluded.intersection(relative.parts):
            continue
        if file.name in {"local.properties", ".DS_Store"} or file.suffix in {".p12", ".jks", ".keystore", ".apk", ".zip"}:
            continue
        archive.write(file, relative.as_posix())
print(destination)
