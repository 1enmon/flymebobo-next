#!/bin/bash
# v565 test build: 轻气泡第二版 (versionName 2.5.0 /
# versionCode 565)。只动音乐卡与四处补漏：① 控制行
# 三键重做为圆形毛玻璃自绘键（去 emoji 瓦片），去掉
# 「音乐 · 置顶」文字；② 加收藏爱心键（走经典 favorite
# 会话通道，tick 真值对齐，无状态本地翻转+日志）；
# ③ 加「词」键：歌词跟随显示，新行按到达药丸亮一次；
# ④ 控制中心系统音乐卡在轻气泡下隐藏（MediaDataManager
# 数据层拦截+候选类探针日志）；⑤ 轻气泡下经典回看边缘
# 签门控隐藏（时光机只从面板进）；⑥ 小签/面板锁屏可
# 显示可打开（去掉解锁守卫，窗口本就是 SYSTEM_ERROR)。
# 经典路径零改动。本地测试包，NOT committed/pushed.
# 改动 (全部新行为在 lightMode 门后)。本地测试包,
# NOT committed/pushed.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v565-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb565-classes /tmp/bb565-dex && mkdir -p /tmp/bb565-classes /tmp/bb565-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb565-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb565-classes && $JDK/bin/jar --create --file /tmp/bb565-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb565-dex /tmp/bb565-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb565-apktool && cp -r $APKTOOL_DIR /tmp/bb565-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 565/' /tmp/bb565-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb565-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb565-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb565-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb565-base.apk")
zo = zipfile.ZipFile("/tmp/bb565-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb565-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb565-unsigned.apk /tmp/bb565-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb565-signed.apk /tmp/bb565-aligned.apk
$BT/apksigner verify --verbose /tmp/bb565-signed.apk
cp /tmp/bb565-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
