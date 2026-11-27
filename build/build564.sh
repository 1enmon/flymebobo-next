#!/bin/bash
# v564 test build: 轻气泡大版本第一版 (versionName
# 2.5.0 / versionCode 564)。新「设计模式」开关
# FeatureFlags.lightMode (默认关, 文件镜像通道):
# 开启后普通通知静默入收纳栈 (安静规则照常: 弹出=
# 放行系统通知/永不弹照旧), 边缘小签=简洁模式形态
# (栈空隐藏/到达药丸只显示最新/位置可设可拖), 面板
# 音乐卡置顶 (现有音乐引擎), 左滑进时光机 (回看栈),
# 音乐通知在集合层隐藏、播放时呼吸变色, 场景并入
# 收纳且面板只读; App 主页换轻气泡装。经典路径零
# 改动 (全部新行为在 lightMode 门后)。本地测试包,
# NOT committed/pushed.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v564-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb564-classes /tmp/bb564-dex && mkdir -p /tmp/bb564-classes /tmp/bb564-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb564-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb564-classes && $JDK/bin/jar --create --file /tmp/bb564-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb564-dex /tmp/bb564-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb564-apktool && cp -r $APKTOOL_DIR /tmp/bb564-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 564/' /tmp/bb564-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb564-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb564-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb564-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb564-base.apk")
zo = zipfile.ZipFile("/tmp/bb564-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb564-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb564-unsigned.apk /tmp/bb564-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb564-signed.apk /tmp/bb564-aligned.apk
$BT/apksigner verify --verbose /tmp/bb564-signed.apk
cp /tmp/bb564-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
