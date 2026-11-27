#!/bin/bash
# v575 test build: 轻气泡第十版 (versionName 2.5.0 /
# versionCode 575)。wei 实测三连：App 里点开新通知后，
# 通知还留在收纳里、边缘药丸还在闪、时光机里也没有它。
# 根因：App 的收纳/时光机两页只是镜像，点行是 App 自
# 己傻启动应用，SystemUI 的收纳栈全然不知。本版打通：
# ① 镜像 C/R 行带上通知 key；② App 点行改发广播
# LIGHT_OPEN / LIGHT_RECALL_OPEN（XposedEntry 转交
# BubbleWindow.lightOpenByKey/lightRecallOpenByKey），
# 与面板点行完全同路：出栈、进时光机、按打开方式开；
# ③ 点开的正是正在闪的那条时立即停闪——新增
# lightFlashKey/lightSubFlashKey 记账，lightOpenItem
# 先 stopLightFlashFor 再出栈（wei：点开后立即收纳、
# 不要再继续闪烁）。日志 Light flash stop on open /
# Light openByKey miss。本地测试包，未提交未推送。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v575-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb575-classes /tmp/bb575-dex && mkdir -p /tmp/bb575-classes /tmp/bb575-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb575-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb575-classes && $JDK/bin/jar --create --file /tmp/bb575-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb575-dex /tmp/bb575-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb575-apktool && cp -r $APKTOOL_DIR /tmp/bb575-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 575/' /tmp/bb575-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb575-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb575-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb575-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb575-base.apk")
zo = zipfile.ZipFile("/tmp/bb575-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb575-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb575-unsigned.apk /tmp/bb575-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb575-signed.apk /tmp/bb575-aligned.apk
$BT/apksigner verify --verbose /tmp/bb575-signed.apk
cp /tmp/bb575-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
