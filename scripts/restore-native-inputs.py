"""Restore the hash-locked native inputs supplied with the matching release."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / "release-manifest.json").read_text(encoding="utf-8"))
    archive_hash = hashlib.sha256(args.archive.read_bytes()).hexdigest()
    if archive_hash != manifest["nativeInputArchive"]["sha256"]:
        raise SystemExit("Archive SHA-256 does not match release-manifest.json")
    verified = []
    with zipfile.ZipFile(args.archive) as archive:
        expected = {entry["path"]: entry for entry in manifest["nativeFiles"]}
        if len(archive.namelist()) != len(expected) or set(archive.namelist()) != set(expected):
            raise SystemExit("Unexpected archive entries")
        for name, entry in expected.items():
            target = (root / name).resolve()
            if not target.is_relative_to(root / "app" / "libs"):
                raise SystemExit("Invalid native input path")
            data = archive.read(name)
            if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
                raise SystemExit("Native input checksum mismatch: " + name)
            verified.append((target, data))
    if not args.verify_only:
        for target, data in verified:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    print(f"Verified {len(verified)} native inputs" + ("; no files written." if args.verify_only else "; restored under app/libs/."))


if __name__ == "__main__":
    main()
