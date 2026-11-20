"""Reassemble byte-identical locally signed APKs; this does not perform signing."""
import argparse
import base64
import gzip
import hashlib
import json
from pathlib import Path
import re

NAME = re.compile(r"AndroNG-1\.5\.0-775-(armeabi-v7a|arm64-v8a|x86|x86_64|universal)-release\.apk")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("unsigned", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    with gzip.open(args.bundle, "rb") as stream:
        payload = stream.read(16 * 1024 * 1024 + 1)
    if len(payload) > 16 * 1024 * 1024:
        raise ValueError("Transfer metadata is too large")
    bundle = json.loads(payload)
    assert bundle["format"] == 1 and len(bundle["files"]) == 5
    assert len({entry["target"] for entry in bundle["files"]}) == 5
    args.output.mkdir(parents=True, exist_ok=True)
    for entry in bundle["files"]:
        assert NAME.fullmatch(entry["target"])
        assert entry["source"] == entry["target"].replace("-release.apk", "-unsigned.apk")
        source = args.unsigned / entry["source"]
        target = args.output / entry["target"]
        source_bytes = source.stat().st_size
        with source.open("rb") as stream:
            assert hashlib.file_digest(stream, "sha256").hexdigest() == entry["sourceSha256"]
        assert type(entry["bytes"]) is int and 0 < entry["bytes"] <= 256 * 1024 * 1024
        temporary = target.with_name(target.name + ".assembling")
        if not args.verify_only and (target.exists() or temporary.exists()):
            raise FileExistsError(target.name)
        output = None if args.verify_only else temporary.open("xb")
        digest = hashlib.sha256()
        written = 0
        try:
            with source.open("rb") as stream:
                for part in entry["parts"]:
                    if "literal" in part:
                        data = base64.b64decode(part["literal"], validate=True)
                        written += len(data)
                        assert written <= entry["bytes"]
                        digest.update(data)
                        if output:
                            output.write(data)
                    else:
                        offset, length = part["offset"], part["length"]
                        assert type(offset) is int and type(length) is int
                        assert offset >= 0 and length > 0 and offset + length <= source_bytes
                        assert written + length <= entry["bytes"]
                        stream.seek(offset)
                        remaining = length
                        while remaining:
                            data = stream.read(min(remaining, 1024 * 1024))
                            if not data:
                                raise EOFError(source.name)
                            digest.update(data)
                            if output:
                                output.write(data)
                            written += len(data)
                            remaining -= len(data)
            assert written == entry["bytes"] and digest.hexdigest() == entry["sha256"]
        finally:
            if output:
                output.close()
        if not args.verify_only:
            temporary.rename(target)
        print("Verified locally signed bytes: " + target.name)


if __name__ == "__main__":
    main()
