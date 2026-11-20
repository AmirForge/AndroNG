#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
mkdir -p build/native-probe/classes build/native-probe/dex
unzip -p app/libs/libv2ray.aar classes.jar > build/native-probe/classes.jar
javac -source 8 -target 8 -cp build/native-probe/classes.jar -d build/native-probe/classes scripts/AndroNativeProbe.java
"$ANDROID_HOME/build-tools/36.1.0/d8" --min-api 28 --lib "$ANDROID_HOME/platforms/android-36/android.jar" \
  --output build/native-probe/dex build/native-probe/classes.jar build/native-probe/classes/AndroNativeProbe.class
for abi in x86 x86_64; do
  folder="/data/local/tmp/androng-native-probe/$abi"
  adb shell mkdir -p "$folder"
  unzip -p app/libs/libv2ray.aar "jni/$abi/libgojni.so" > "build/native-probe/libgojni-$abi.so"
  adb push "build/native-probe/libgojni-$abi.so" "$folder/libgojni.so"
  adb push build/native-probe/dex/classes.dex "$folder/classes.dex"
  adb push "app/libs/$abi/libaether.so" "$folder/aether"
  adb shell chmod 755 "$folder/aether"
  adb shell "$folder/aether --help" > "build/native-probe/aether-$abi.txt"
  runner=app_process32
  [[ "$abi" == x86_64 ]] && runner=app_process64
  adb shell "CLASSPATH=$folder/classes.dex LD_LIBRARY_PATH=$folder /system/bin/$runner -Djava.library.path=$folder /system/bin AndroNativeProbe" \
    | tee "build/native-probe/xray-$abi.txt"
  grep -q EXIT_OPEN_CLOSE_OK "build/native-probe/xray-$abi.txt"
done
apk=app/build/outputs/apk/debug/app-universal-debug.apk
adb install -r "$apk"
adb logcat -c
adb shell monkey -p com.v2meda.android -c android.intent.category.LAUNCHER 1
sleep 3
adb shell pidof com.v2meda.android
adb logcat -d -b crash > build/native-probe/crash.txt
if grep -q 'FATAL EXCEPTION' build/native-probe/crash.txt; then cat build/native-probe/crash.txt; exit 1; fi
echo 'API 28 x86/x86_64 JNI and Aether startup checks passed; universal debug app launched.'
