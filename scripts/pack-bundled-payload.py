#!/usr/bin/env python3
"""把本地 runtime/payload 打成 APK 内嵌包。绝不打包密钥。"""
from __future__ import annotations

import hashlib
import os
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "runtime" / "payload"
DEFAULT_DEST = ROOT / "app" / "build" / "generated" / "bundled-assets" / "payload.zip"

SKIP_NAMES = {
    ".credentials.yaml",
    ".ds_store",
    "engine.log",
    "thumbs.db",
}
SKIP_DIR_PARTS = {
    "prebuilds",
    "third_party",
    ".git",
    "__pycache__",
    "test",
    "tests",
    ".github",
}
# 只砍带架构后缀的宿主原生包，保留 dsh-win32-process 这种纯 JS
NATIVE_DIR = re.compile(
    r".+-(darwin|win32|windows|linux)-(arm64|x64|ia32|arm)(?:$|[-.])",
    re.I,
)


def skip_dir(rel: Path) -> bool:
    parts = {p.lower() for p in rel.parts}
    if parts & SKIP_DIR_PARTS:
        return True
    return any(NATIVE_DIR.search(p) for p in rel.parts)


def skip_file(rel: Path) -> bool:
    name = rel.name.lower()
    if name in SKIP_NAMES:
        return True
    if name.endswith((".md", ".map", ".ts", ".tsx")):
        return True
    return NATIVE_DIR.search(rel.name) is not None


def md5_file(path: Path) -> str:
    h = hashlib.md5()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def collect(src: Path) -> tuple[list[tuple[Path, str]], list[tuple[str, str]]]:
    files: list[tuple[Path, str]] = []
    links: list[tuple[str, str]] = []
    seen_lib: dict[str, str] = {}
    for path in src.rglob("*"):
        if not path.is_file() or path.is_symlink():
            continue
        rel = path.relative_to(src)
        if any(skip_dir(rel.parents[i]) for i in range(len(rel.parts) - 1)):
            continue
        if skip_file(rel):
            continue
        zip_name = rel.as_posix()
        lib_dir = False
        if len(rel.parts) >= 4 and rel.parts[0] == "runtime" and rel.parts[2] == "lib":
            lib_dir = True
        if lib_dir:
            digest = md5_file(path)
            if digest in seen_lib:
                links.append((zip_name, seen_lib[digest]))
                continue
            seen_lib[digest] = zip_name
        files.append((path, zip_name))
    files.sort(key=lambda item: item[1])
    return files, links


def pack(dest: Path) -> None:
    if not (SRC / "dshroot").is_dir():
        print(f"skip: missing {SRC / 'dshroot'}", file=sys.stderr)
        sys.exit(0)
    dest.parent.mkdir(parents=True, exist_ok=True)
    files, links = collect(SRC)
    if not files:
        print("skip: nothing to pack", file=sys.stderr)
        sys.exit(1)
    tmp = dest.with_suffix(".zip.tmp")
    if tmp.exists():
        tmp.unlink()
    manifest = [
        "version=1",
        f"entries={len(files)}",
        f"links={len(links)}",
    ]
    print(f"packing {len(files)} files, {len(links)} lib links -> {dest}")
    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        zf.writestr("MANIFEST.txt", "\n".join(manifest) + "\n")
        if links:
            zf.writestr("LINKS.txt", "\n".join(f"{src}|{dst}" for src, dst in links) + "\n")
        for i, (path, name) in enumerate(files, start=1):
            zf.write(path, name)
            if i % 2000 == 0 or i == len(files):
                print(f"  {i}/{len(files)} {name}")
    tmp.replace(dest)
    src_bytes = sum(p.stat().st_size for p, _ in files)
    print(f"source {src_bytes / 1024 / 1024:.1f} MB -> zip {dest.stat().st_size / 1024 / 1024:.1f} MB")


if __name__ == "__main__":
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_DEST
    pack(out)
