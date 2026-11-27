#!/bin/bash
# v566 test build: 轻气泡第三版 (versionName 2.5.0 /
# versionCode 566)。按 wei v565 实测反馈四改：① 控制
# 行五键被裁——面板 264→292dp、侧键 44→40dp、播放键
# 56→52dp、间距 12→8dp，五键完整不裁；② 歌词药丸整
# 行显示（wei 定「不用省略」）：歌词模式放开宽度到屏
# 宽减边距、最多折两行、无省略，到达药丸保持紧凑；
# ③ 歌词播放到一半不显示（日志定位：18:25:13–18:26:23
# 行行都亮、之后 NS 侧断供）——根因是歌词监听归经典
# 路径 syncLyricListen 管（只认钉住/开卡），轻气泡没
# 人保活；v566 轻气泡自带监听开关：开词直发
# LYRIC_LISTEN 并对齐 key、音乐通知每次进来重武装、
# 关词/音乐结束/切回经典发关、经典同步不许插手，另
# 加 keymiss 诊断日志；④ 控制中心音乐卡拦截摘除
# （wei：「状态栏的音乐不管他了」）。
# 经典路径零改动。本地测试包，NOT committed/pushed.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v566-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb566-classes /tmp/bb566-dex && mkdir -p /tmp/bb566-classes /tmp/bb566-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb566-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb566-classes && $JDK/bin/jar --create --file /tmp/bb566-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb566-dex /tmp/bb566-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb566-apktool && cp -r $APKTOOL_DIR /tmp/bb566-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 566/' /tmp/bb566-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb566-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb566-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb566-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb566-base.apk")
zo = zipfile.ZipFile("/tmp/bb566-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb566-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb566-unsigned.apk /tmp/bb566-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb566-signed.apk /tmp/bb566-aligned.apk
$BT/apksigner verify --verbose /tmp/bb566-signed.apk
cp /tmp/bb566-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
