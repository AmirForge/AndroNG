# AndroNG

Android proxy client derived from v2rayNG and PattNG, with Xray and Aether support.
This is not an official release of v2rayNG or PattNG.

This source snapshot retains the features of **1.5.0, build 774**: space-themed
interface and animations, Aether endpoint scanning and racing, refresh and enhanced
setup actions, `andro://` sharing, OutRule, managed configurations and Pro features.

## Downloads

Download the signed APK from this repository's **Releases** page. Build 774 is for
**arm64-v8a**, requires **Android 9 / API 28 or newer**, and has version name 1.5.0.
Other CPU architectures are not supported by this release.

APK and native input checksums are in [release-manifest.json](release-manifest.json).
A rebuild signed with another key cannot replace the maintainer-signed installation
without uninstalling it first.

## Build

The Gradle project is at the repository root. Install JDK 17 or newer compatible
with the checked-in Gradle and Android Gradle Plugin, Android SDK platform 36 and
Build Tools 36, and Python 3.9 or newer. The published build used JDK 25.0.2 and
Gradle 9.8.2. Dependency versions are in `gradle/libs.versions.toml`.

1. Clone with `git clone --recurse-submodules <repository-url>`.
2. Download `AndroNG-native-inputs-774.zip` from the matching release.
3. Restore native inputs on Windows:

   ```powershell
   python scripts/restore-native-inputs.py "C:/Downloads/AndroNG-native-inputs-774.zip"
   ```

4. Set `ANDROID_HOME` to your SDK, or create an ignored `local.properties` with its location.
5. Build an **unsigned** release:

   ```powershell
   .\gradlew.bat :app:assembleRelease
   ```

On Linux/macOS use `bash gradlew :app:assembleRelease`. For debug builds, create
your own local `debug.keystore` at the repository root with conventional debug
alias `androiddebugkey` and password `android`, then run `:app:assembleDebug`.
This debug key is not the release key.

Release signing keys and passwords are not included. Sign your own build locally
with your own key. See [native source provenance](docs/native-sources.md) for pinned
source revisions and native rebuild information.

The `src/test` loopback certificate is a public, test-only fixture with password
`test-only`. It is not a release signing key and is not packaged in the APK.

## Connected service

The public Worker origin is a client endpoint, not an administrator credential.
To use another service pass `-PcontrolPanelUrl=https://your-worker.example` at build
time. Pass `-PcontrolPanelUrl=` to disable the configured service.

The service provides managed configurations, activation codes, banner content,
update notices and first-connect installation registration. Registration uses a
generated local UUID rather than a hardware device identifier. Managed service
features require a compatible backend; access to the maintainer's admin panel or
private subscriptions is not included in this source release.

The `andro://` envelope is a sharing format, not a way to keep a profile secret
from its recipient. Its implementation is included with the rest of the app.

## License and acknowledgements

Application source is distributed under [GPL-3.0](LICENSE), subject to component
licenses. Preserve upstream notices and dependency licenses. The APK includes an
open-source notices page.

Thanks to **2dust, patterniha and CluvexStudio**, and the maintainers of Xray,
Aether, HEV, AndroidX, Kotlin and the other dependencies used by this project.
