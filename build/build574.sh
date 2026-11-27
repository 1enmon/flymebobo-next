#!/bin/bash
# v574 test build: 轻气泡第九版 (versionName 2.5.0 /
# versionCode 574)。wei 实测反馈：只有一条通知时长按，
# 面板可视高只有一行，内嵌菜单被裁得只剩第一项——
# 「空间不够也自动把面板拓展开」。本版 applyLightScrollCap：
# 内嵌菜单展开时滚动区高度撑到「N 行 + 菜单整高」（含
# 菜单上下边距），上限屏高 72%，超了才滚；菜单收起 /
# 回复框收起时缩回 N 行；高度变化带 180ms 动画像被
# 菜单撑开。新增字段 lightPanelScroll/lightPanelRows/
# lightCapAnim，重建与关面板时清引用。日志 Light panel
# grow。本地测试包，未提交未推送。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v574-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb574-classes /tmp/bb574-dex && mkdir -p /tmp/bb574-classes /tmp/bb574-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb574-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb574-classes && $JDK/bin/jar --create --file /tmp/bb574-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb574-dex /tmp/bb574-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb574-apktool && cp -r $APKTOOL_DIR /tmp/bb574-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 574/' /tmp/bb574-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb574-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb574-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb574-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb574-base.apk")
zo = zipfile.ZipFile("/tmp/bb574-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb574-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb574-unsigned.apk /tmp/bb574-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb574-signed.apk /tmp/bb574-aligned.apk
$BT/apksigner verify --verbose /tmp/bb574-signed.apk
cp /tmp/bb574-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
