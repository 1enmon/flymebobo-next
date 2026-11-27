#!/bin/bash
# v569 test build: 轻气泡第六版 (versionName 2.5.0 /
# versionCode 569)。wei 定新场景口径：播放音乐（开词）
# 时来新通知——新通知先在歌词下面闪烁，然后再收纳。
# 实现：小签窗口里主药丸下方加一条副药丸（药丸列竖
# 排，主上副下，贴边侧对齐）；flashLightPill 在
# lightLyricFollow 开时改走副药丸：图标+应用名、透
# 明度 1↔0.3 一明一暗地闪（0.8s 半周期），约 11.2s
# 后收回=收纳完成（通知本体到达时已入栈、角标已计
# 数）；歌词行继续走主药丸不被打断；再来一条副药丸
# 换脸重置计时（永远只显示最新）；关词/拖动/关模式
# 时副药丸一并收，主药丸落回时不抢签。没开词时到达
# 显示与从前完全一致。v568 内容滚动、v567 外观与
# 纯字形按钮不变。经典路径零改动。本地测试包，
# NOT committed/pushed.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v569-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb569-classes /tmp/bb569-dex && mkdir -p /tmp/bb569-classes /tmp/bb569-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb569-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb569-classes && $JDK/bin/jar --create --file /tmp/bb569-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb569-dex /tmp/bb569-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb569-apktool && cp -r $APKTOOL_DIR /tmp/bb569-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 569/' /tmp/bb569-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb569-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb569-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb569-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb569-base.apk")
zo = zipfile.ZipFile("/tmp/bb569-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb569-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb569-unsigned.apk /tmp/bb569-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb569-signed.apk /tmp/bb569-aligned.apk
$BT/apksigner verify --verbose /tmp/bb569-signed.apk
cp /tmp/bb569-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
