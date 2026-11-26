#!/bin/bash
# flymebobo 2.0 — build & package v536 (2.4.4 test build).
# Adapted from build/build20.sh with the current toolchain paths
# (~/workspace/tools/android-build: Temurin JDK 17, SDK
# platform/build-tools 34, R8 8.5.35, libxposed api 102).
# v536: bubble-sound sub-page becomes a home-page expandable card
# (wei: same as quiet rules) — SoundSettingsActivity deleted and
# unregistered; the 气泡提示音 card sits right under 通知气泡 with
# master switch in the head, global picker + custom file + per-app
# list inside (SoundRules/BubbleSound behaviour unchanged from
# v535). Also fixes v535's broken in-app preview: wav assets are
# stored compressed, so openFd playback failed — assets are now
# copied to a cache file and played by path (BubbleSound in
# SystemUI got the same fix), failures FileLog the sound id +
# exception, and apktool.yml adds wav to doNotCompress + bumps
# versionCode to 536. On top of v535.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.4.4-v536test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb536-classes /tmp/bb536-dex && mkdir -p /tmp/bb536-classes /tmp/bb536-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb536-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb536-classes && $JDK/bin/jar --create --file /tmp/bb536-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb536-dex /tmp/bb536-classes.jar

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb536-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb536-base.apk")
zo = zipfile.ZipFile("/tmp/bb536-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb536-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb536-unsigned.apk /tmp/bb536-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb536-signed.apk /tmp/bb536-aligned.apk
$BT/apksigner verify --verbose /tmp/bb536-signed.apk
cp /tmp/bb536-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
