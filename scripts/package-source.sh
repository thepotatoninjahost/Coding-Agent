#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT="${1:-$ROOT/../Coding-Agent-source.zip}"
OUTPUT="$(readlink -m "$OUTPUT")"

if [[ "$OUTPUT" == "$ROOT"/* ]]; then
  printf 'Refusing to write the archive inside the source tree: %s\n' "$OUTPUT" >&2
  exit 2
fi

python3 - "$ROOT" "$OUTPUT" <<'PY'
from pathlib import Path
import hashlib
import os
import stat
import sys
import zipfile

root = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2]).resolve()
silent_dirs = {".git", ".coding-agent"}
forbidden_dirs = {".gradle", ".gradle-cache", ".idea", "build"}
excluded_names = {"local.properties"}
excluded_suffixes = {".apk", ".aab", ".class"}

forbidden = []
files = []
skipped = []
for path in root.rglob("*"):
    rel = path.relative_to(root)
    parts = rel.parts
    has_silent_dir = any(part in silent_dirs for part in parts)
    has_forbidden_dir = any(part in forbidden_dirs for part in parts) or "build" in parts
    has_forbidden_file = path.name in excluded_names or path.suffix in excluded_suffixes
    if has_silent_dir:
        skipped.append((rel.as_posix(), "private/generated directory"))
        continue
    if has_forbidden_dir or has_forbidden_file:
        skipped.append((rel.as_posix(), "generated or machine-local file"))
        continue
    if not path.is_file():
        continue
    if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode):
        raise SystemExit(f"Refusing non-regular file: {rel}")
    files.append((rel.as_posix(), path))

if forbidden:
    raise SystemExit("Forbidden generated or machine-local files present:\n" + "\n".join(sorted(forbidden)))

files.sort(key=lambda item: item[0].encode("utf-8"))
if not files:
    raise SystemExit("No source files found")

for rel, _ in files:
    if rel.startswith(".git/") or rel.startswith(".gradle/") or rel == "local.properties":
        raise SystemExit(f"Excluded file leaked into package: {rel}")

output.parent.mkdir(parents=True, exist_ok=True)
tmp = output.with_suffix(output.suffix + ".tmp")
if tmp.exists():
    tmp.unlink()

with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for rel, path in files:
        info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.create_system = 3
        mode = stat.S_IMODE(path.stat().st_mode)
        info.external_attr = ((0o100000 | mode) << 16)
        archive.writestr(info, path.read_bytes())
os.replace(tmp, output)

sha = hashlib.sha256(output.read_bytes()).hexdigest()
print(f"Packaged {len(files)} source files into {output}")
print(f"Skipped {len(skipped)} generated or private paths")
print(f"SHA-256 {sha}")
PY
