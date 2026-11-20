#!/usr/bin/env bash
# Builds, signs and installs the launcher without Gradle.
#   ./build.sh                  build + install (+ grant storage read permission + set as home)
#   ./build.sh --no-install     build only
#   ./build.sh --art DIR        also push every image in DIR to /sdcard/Pictures/LauncherArt/
#
# Environment:
#   DEVICE        adb serial, e.g. 192.168.1.50:5555 (default: adb's only connected device)
#   ANDROID_HOME  Android SDK with build-tools 34.0.0 and platforms/android-34 (default: ~/Library/Android/sdk)
#   JAVA_HOME     a JDK 17 or 21 (default: /usr/libexec/java_home -v 21). d8 34 can't read newer class files.
set -euo pipefail
cd "$(dirname "$0")"

INSTALL=1
ART_SRC=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-install) INSTALL=0 ;;
    --art) ART_SRC="$2"; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)"
fi
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"  # d8/apksigner run on this JDK too
ADB=(adb)
[ -n "${DEVICE:-}" ] && ADB=(adb -s "$DEVICE")
KEYSTORE="debug.keystore"
OUT="build"
APK="$OUT/projector-launcher.apk"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

if [ ! -f "$KEYSTORE" ]; then
  echo "== generating $KEYSTORE"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias launcher -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Projector Launcher Debug" >/dev/null
fi

echo "== aapt2 compile + link"
mkdir -p "$OUT/gen"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  --java "$OUT/gen" "$OUT/res.zip" \
  --min-sdk-version 21 --target-sdk-version 30 --version-code 1 --version-name 1.0

echo "== javac"
"$JAVA_HOME/bin/javac" --release 8 -Xlint:-options -classpath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

echo "== d8"
"$BT/d8" --release --min-api 21 --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q -u ../unsigned.apk classes.dex)

echo "== zipalign + sign"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias launcher --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify "$APK"
echo "== built $APK"

if [ "$INSTALL" = 1 ]; then
  echo "== installing"
  "${ADB[@]}" install --no-incremental -r "$APK"
  # Art lives in /sdcard/Pictures/LauncherArt (see README.md). With targetSdk 30 on Android 11,
  # READ_EXTERNAL_STORAGE lets the app read media files there by path; grant it (it's a runtime
  # permission and there's no UI to ask for it). The app-specific folder
  # /sdcard/Android/data/com.domdomegg.launcher/files/art/ is also scanned and needs no permission.
  "${ADB[@]}" shell pm grant com.domdomegg.launcher android.permission.READ_EXTERNAL_STORAGE
  "${ADB[@]}" shell cmd package set-home-activity com.domdomegg.launcher/.MainActivity >/dev/null
fi

if [ -n "$ART_SRC" ]; then
  echo "== pushing art from $ART_SRC"
  "${ADB[@]}" shell mkdir -p /sdcard/Pictures/LauncherArt
  "${ADB[@]}" push "$ART_SRC"/. /sdcard/Pictures/LauncherArt/
fi
