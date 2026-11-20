"""Fetch the pinned baseline and stage native inputs without signing material."""
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")


def fetch(url, checksum, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        with urllib.request.urlopen(url, timeout=120) as response, target.open("wb") as output:
            import shutil
            shutil.copyfileobj(response, output)
    if hashlib.sha256(target.read_bytes()).hexdigest() != checksum:
        raise RuntimeError("Download checksum mismatch: " + target.name)
    return target


def main():
    lock = json.loads((ROOT / "docs/releases/774-manifest.json").read_text())
    baseline = lock["upstreamNativeBuild"]
    upstream = fetch(
        f"{baseline['repository']}/releases/download/{baseline['tag']}/{baseline['referenceAsset']}",
        baseline["referenceSha256"], ROOT / "build/native-baseline.apk")
    with zipfile.ZipFile(upstream) as archive:
        for abi in ABIS:
            names = ["libhev-socks5-tunnel.so", "libhevsockstun.so"]
            if abi != "x86":
                names.append("libaether.so")
            for name in names:
                target = ROOT / "app/libs" / abi / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(f"lib/{abi}/{name}"))
        for entry in archive.namelist():
            if entry.startswith("assets/") and entry.endswith(".dat"):
                target = ROOT / "native/AndroidLibXrayLite" / entry
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(entry))
    print("Pinned HEV/Aether baselines and original geo assets staged; Xray must be rebuilt for API 28.")


if __name__ == "__main__":
    main()
