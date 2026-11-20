"""Check ABI contents and package metadata, then stage unsigned release files."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = {"armeabi-v7a": 40, "arm64-v8a": 183, "x86": 3, "x86_64": 62}


def main():
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    aapt = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.1.0/aapt"
    apks = list((ROOT / "app/build/outputs/apk/release").glob("*.apk"))
    assert len(apks) == 5, [p.name for p in apks]
    results = []
    for apk in apks:
        abi = next((name for name in ABIS if f"-{name}-" in apk.name), "universal")
        wanted = set(ABIS) if abi == "universal" else {abi}
        badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
        assert "versionCode='775'" in badging and "versionName='1.5.0'" in badging
        assert "sdkVersion:'28'" in badging
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            actual = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')}
            assert actual == wanted, (apk.name, actual, wanted)
            for cpu in wanted:
                for library in ["libgojni.so", "libaether.so", "libhev-socks5-tunnel.so", "libhevsockstun.so"]:
                    data = archive.read(f"lib/{cpu}/{library}")
                    assert data[:4] == b'\x7fELF'
                    assert struct.unpack_from('<H', data, 18)[0] == ABIS[cpu], (cpu, library)
            assert not any(name.endswith((".p12", ".jks", ".keystore")) for name in names)
        target = dist / f"AndroNG-1.5.0-775-{abi}-unsigned.apk"
        shutil.copy2(apk, target)
        results.append({"abi": abi, "unsignedFile": target.name, "bytes": target.stat().st_size})
    files = [ROOT / "app/libs/libv2ray.aar"]
    files += [ROOT / "app/libs" / abi / name for abi in ABIS
              for name in ["libaether.so", "libhev-socks5-tunnel.so", "libhevsockstun.so"]]
    metadata = []
    bundle = dist / "AndroNG-native-inputs-775.zip"
    with zipfile.ZipFile(bundle, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in files:
            data = path.read_bytes()
            relative = path.relative_to(ROOT).as_posix()
            archive.writestr(relative, data)
            metadata.append({"path": relative, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    manifest = json.loads((ROOT / "docs/releases/774-manifest.json").read_text())
    manifest["release"] = {"versionName": "1.5.0", "versionCode": 775, "minSdk": 28,
                           "abis": list(ABIS), "apkFile": "AndroNG-1.5.0-775-universal-release.apk"}
    manifest["nativeFiles"] = metadata
    manifest["nativeInputArchive"] = {"file": bundle.name, "bytes": bundle.stat().st_size,
                                       "sha256": hashlib.sha256(bundle.read_bytes()).hexdigest()}
    manifest["build"] = {"sourceCommit": os.environ.get("GITHUB_SHA"), "xrayApi": 28,
                         "xrayAbisRebuilt": list(ABIS), "aetherAdditionalAbisBuilt": ["x86"]}
    manifest["apkOutputs"] = results
    (dist / "release-manifest-775.json").write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
