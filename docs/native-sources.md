# Native source provenance — build 775

Build 775 supplies `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` and a Universal APK.
All packages require Android API 28 or newer. Source revisions remain pinned by
`.gitmodules`; clone with `--recurse-submodules` to obtain the native sources.

## Sources and toolchains

- AndroidLibXrayLite: `1546ca75217fc6e2ca6ec6c532871fd8555b9f2f`.
- Aether: `d58f9d568083129ce2c767c85054021eb3ee6a38`.
- HEV: `75dbc1e9ace6c70d3d1b94df7c283efe1de7effc`.

Xray JNI was rebuilt for **all four architectures**, using Go **1.27.2**,
NDK **29.0.14206865**, the source's pinned gomobile version, and `-androidapi 28`.
The AAR retains the original baseline geo assets. Dependency revisions are pinned
in the source's `go.mod` and `go.sum`.

The additional **x86 Aether** executable was compiled from the pinned source using
Rust **1.100.0**, the same NDK, Android API 28 and the upstream `tor` build feature.
Its source build recipe is `scripts/build-aether.sh`; for the recorded compiler,
set `RUSTUP_TOOLCHAIN=1.100.0` when rebuilding it.

Aether on ARM32, ARM64 and x86_64, and HEV on all four architectures, retain the
baseline PattNG **2.4.0-P72** binaries. Their upstream source build recipes are at:

https://github.com/patterniha/PattNG/tree/22927868a4f8aca2422db229148b24a6dd35d20e

The baseline universal APK was verified against its published SHA-256 before
extraction. `docs/releases/774-manifest.json` preserves its source and checksum
records. The build-774 native notes are in `docs/releases/774-native-sources.md`.

## Build inputs and verification

Native build inputs are built from the pinned sources. They are not distributed
as a separate download. See `docs/BUILD.md` for the source build steps.
`release-manifest.json` in the repository records native input and signed APK
checksums; the release's `SHA256SUMS` lists only the five installable APKs.

The cloud build and Android 9 emulator checks are recorded at:

https://github.com/AmirForge/AndroNG/actions/runs/44808827874

Passed checks:

- Debug and release assembly for four ABIs and Universal.
- Version, API 28 minimum, ABI inventory and native ELF architecture checks for all five APKs.
- x86 and x86_64 Xray JNI load, initialization and local exit open/close on API 28.
- x86 and x86_64 Aether executable startup via `--help` on API 28.
- Universal debug APK installation and cold launch, with a live process and no fatal Java crash.

**Not run:** physical ARM32/ARM64 device checks for this new build; no device was connected.
**Not run:** end-to-end VPN, proxy-only and root-mode connectivity on each ABI;
these startup checks do not exercise an external tunnel or real WARP registration.
**Not run:** installation on a device with a 16 KiB page-size kernel; ZIP alignment
checks alone are not a device compatibility test.

The private release signing key stays on the maintainer's Windows computer and
is not supplied to GitHub Actions. Debug keys created by CI are disposable.
This documentation does not claim a separate rebuild is byte-for-byte identical.
