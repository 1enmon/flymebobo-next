#!/bin/bash
# v584 test build: 轻气泡第十九版 (versionName 2.5.0 /
# versionCode 584)。含 v582（两个应用搜索框浅灰底 +
# 细线描边）+ v583（静默时长药丸浅色口径）+ 本版：
# 分段控件在轻气泡下轨道与行底同色糊掉（wei：「按
# 钮背后的背景」）——SegControl 轻气泡轨道从 L_TRACK
# 换成深一号的灰 (226,232,240)，选中白药丸与未选文
# 字都立得住。本地测试包，未提交未推送。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v584-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb584-classes /tmp/bb584-dex && mkdir -p /tmp/bb584-classes /tmp/bb584-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb584-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb584-classes && $JDK/bin/jar --create --file /tmp/bb584-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb584-dex /tmp/bb584-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb584-apktool && cp -r $APKTOOL_DIR /tmp/bb584-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 584/' /tmp/bb584-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb584-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb584-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb584-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb584-base.apk")
zo = zipfile.ZipFile("/tmp/bb584-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb584-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb584-unsigned.apk /tmp/bb584-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb584-signed.apk /tmp/bb584-aligned.apk
$BT/apksigner verify --verbose /tmp/bb584-signed.apk
cp /tmp/bb584-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
