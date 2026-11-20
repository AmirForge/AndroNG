# Building AndroNG from source

The source contains the app's full features. Native sources are pinned as Git
submodules, and all four ABIs plus Universal target Android API 28 or newer.
Release signing keys and administrator credentials are not included.

## Requirements

- Git, Python 3.9+, JDK 25 and the checked-in Gradle wrapper.
- Android SDK platform 36, Build Tools 36.1.0 and NDK 29.0.14206865.
- Go 1.27.2 and Rust 1.100.0 for the pinned native sources.
- Linux/macOS for the native build shell script, or the GitHub Actions workflow.

Build 775 was assembled in CI with Temurin JDK 25.0.5 and signed locally with
JDK 25.0.2. Compiler versions and source revisions are recorded in
[the source manifest](../release-manifest.json) and
[native source notes](native-sources.md).

## Build native inputs

Clone with `git clone --recurse-submodules <repository-url>`. From its root,
configure `ANDROID_HOME` and install the SDK packages:

```bash
sdkmanager 'platforms;android-36' 'build-tools;36.1.0' 'ndk;29.0.14206865'
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
export NDK_HOME="$ANDROID_NDK_HOME"
python3 scripts/prepare-multiabi-inputs.py
```

The preparation script verifies the pinned upstream PattNG APK checksum, then
extracts the unchanged HEV/Aether components and geo assets from that baseline.
It does not depend on a separately distributed AndroNG native archive.

Build the Xray binding for all four Android architectures:

```bash
export PATH="$(go env GOPATH)/bin:$PATH"
cd native/AndroidLibXrayLite
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20261011034528-197770444867
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20261011034528-197770444867
gomobile init
gomobile bind -target=android -androidapi 28 -trimpath -ldflags='-s -w -buildid= -checklinkname=0' -o ../../app/libs/libv2ray.aar ./
cd ../..
```

Build the additional x86 Aether executable:

```bash
rustup toolchain install 1.100.0 --profile minimal
RUSTUP_TOOLCHAIN=1.100.0 bash scripts/build-aether.sh
```

## Build APKs

```bash
bash gradlew --no-daemon :app:assembleRelease
```

Windows can assemble the Android project with `gradlew.bat` after native inputs
have been built and placed under `app/libs/`. APK outputs are in
`app/build/outputs/apk/release/`. To limit architectures, pass
`-PreleaseAbis=arm64-v8a` or another comma-separated subset.

If a primary Maven endpoint is unavailable, add
`-I scripts/repository-fallbacks.gradle`.

For debug builds create your own local `debug.keystore`, alias `androiddebugkey`,
password `android`, then run `:app:assembleDebug`. This disposable debug key is
not the release signing key. Release outputs are unsigned until you sign them
locally with your own key.

The manual **Prepare multi-ABI release** workflow also builds from source and
saves unsigned APKs as CI artifacts. It does not publish technical archives or
metadata files to Releases.

## Connected service

The public Worker origin is a client endpoint, not an admin credential.
Override it with `-PcontrolPanelUrl=https://your-worker.example` or disable it
with `-PcontrolPanelUrl=`. Managed configuration features require a compatible
backend; private subscriptions and administrator access are not included.

The public `src/test` loopback certificate has password `test-only`; it is not
used for release signing and is not packaged in the app.

## License

Application source follows [GPL-3.0](../LICENSE), subject to component-specific
licenses. Preserve upstream notices and dependency licenses.
