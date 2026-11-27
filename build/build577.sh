#!/bin/bash
# v577 test build: 轻气泡第十二版 (versionName 2.5.0 /
# versionCode 577)。wei 改规矩：之前 v569 的「歌词常驻
# 主药丸、新通知在下面副药丸同时闪」作废——「来新通知
# 了之后歌词给通知让路」「通知闪烁完收纳后再显示歌
# 词」。本版：flashLightPill 摘掉副药丸分流，新通知直
# 接上主药丸闪（图标+应用名）；flashLightLyricPill 在
# 通知闪烁期间（lightFlashKey 非空）不抢位；通知闪完
# 的收尾 lightPulseEndRunnable 若发现刚才是通知在闪
# （wasArrival）且词跟随开着，立刻把当前歌词行再亮出
# 来续上。点开正在闪的通知提前停闪时（v575 路径）同
# 样会续歌词。副药丸实现保留但无调用方。本地测试包，
# 未提交未推送。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v577-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb577-classes /tmp/bb577-dex && mkdir -p /tmp/bb577-classes /tmp/bb577-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb577-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb577-classes && $JDK/bin/jar --create --file /tmp/bb577-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb577-dex /tmp/bb577-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb577-apktool && cp -r $APKTOOL_DIR /tmp/bb577-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 577/' /tmp/bb577-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb577-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb577-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb577-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb577-base.apk")
zo = zipfile.ZipFile("/tmp/bb577-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb577-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb577-unsigned.apk /tmp/bb577-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb577-signed.apk /tmp/bb577-aligned.apk
$BT/apksigner verify --verbose /tmp/bb577-signed.apk
cp /tmp/bb577-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
