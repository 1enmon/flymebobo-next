#!/bin/bash
# flymebobo 2.0 — build & package (first version, versionCode 1).
# Adapted from the verified 1.x flow (~/workspace/bubblenotify/BUILD.md).
# Differences from 1.x: no original APK to copy META-INF/xposed from —
# module.prop / scope.list / java_init.list are written from the project
# files under build/apktool/original/META-INF/xposed/.
set -e
export JDK=$HOME/workspace/tools/android/jdk-21
export BT=$HOME/workspace/tools/android/build-tools-34
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo2/src
APKTOOL_DIR=$HOME/workspace/flymebobo2/build/apktool
OUT=flymebobo-2.0-v310.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb20-classes /tmp/bb20-dex && mkdir -p /tmp/bb20-classes /tmp/bb20-dex
$JDK/bin/javac -nowarn -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android/android-34.jar:$HOME/workspace/tools/android/libxposed-api.jar" \
  -d /tmp/bb20-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb20-classes && $JDK/bin/jar --create --file /tmp/bb20-classes.jar .
$JDK/bin/java -cp /tmp/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android/android-34.jar \
  --output /tmp/bb20-dex /tmp/bb20-classes.jar

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb20-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb20-base.apk")
zo = zipfile.ZipFile("/tmp/bb20-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb20-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo2/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb20-unsigned.apk /tmp/bb20-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb20-signed.apk /tmp/bb20-aligned.apk
$BT/apksigner verify --verbose /tmp/bb20-signed.apk
cp /tmp/bb20-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
