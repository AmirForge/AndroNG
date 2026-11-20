# Native source provenance for build 774

The Android source and resources match the local source used for release 774.
This does not assert that a separately compiled and signed APK will be
byte-for-byte identical. Toolchains, generated files and signatures affect output.

## Pinned sources

The repository records native source gitlinks under `native/`. Run
`git submodule update --init --recursive` after cloning to obtain their sources
and transitive submodules. Do not replace the pinned revisions with latest HEAD.

- AndroidLibXrayLite: `patterniha/AndroidLibXrayLite`, commit
  `1546ca75217fc6e2ca6ec6c532871fd8555b9f2f`.
- Aether: `patterniha/Aether`, commit
  `d58f9d568083129ce2c767c85054021eb3ee6a38`.
- HEV: `heiher/hev-socks5-tunnel`, commit
  `75dbc1e9ace6c70d3d1b94df7c283efe1de7effc`.

The Aether and HEV ARM64 binaries are unchanged from PattNG **2.4.0-P72**, commit
`22927868a4f8aca2422db229148b24a6dd35d20e`. They were checked against the universal
APK asset of that release. `release-manifest.json` records that reference APK's
SHA-256 and the hashes of all four local native build inputs.

Source build recipes for those unchanged binaries are `compile-aether.sh`,
`compile-hevtun.sh`, and `.github/workflows/build.yml` at:

https://github.com/patterniha/PattNG/tree/22927868a4f8aca2422db229148b24a6dd35d20e

Those recipes use their upstream repository layout. The AndroNG Gradle project
uses `app/libs/` instead of upstream's `V2rayNG/app/libs/`.

## Xray Android 9 compatibility input

The ARM64 `libgojni.so` was rebuilt from the pinned AndroidLibXrayLite source with
Go **1.27.2** and Android NDK **r29 / 29.0.14206865**, targeting Android API **28**.
The pinned `go.mod` includes the exact Xray replacement and Go dependency versions.
Use its pinned `golang.org/x/mobile` version rather than installing `latest`.
The source checkout used for that rebuild is clean at the recorded commit.

The binding build uses `gomobile bind` with ARM64 Android targeting, API 28,
trimpath and the upstream linker options `-s -w -buildid= -checklinkname=0`.
The AAR retains upstream entries for other architectures; only ARM64 is packaged
by this application's ABI filter. The supplied input archive preserves the exact
AAR used for release 774, including generated Java bindings and geo data.

For an application rebuild using the tested inputs, restore the matching native
input archive as described in README. Every file is checked before restoration.

This preparation checked provenance and hashes. A fresh native compilation and a
clean-machine Android build of this public checkout have not yet been run.
