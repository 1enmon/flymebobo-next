#!/bin/bash
# v613 (2.5.0): 累积 v612 + 夸克式下载识别（wei 实测报：夸克下载没识别、状态栏也没拦截）。
# 根因：夸克通知 extras 进度 0/0、无百分号，进度只写在标题里（「下载中 73.92M/318.35M」），而三条识别链都以 extras 进度为门。修法：新增文字大小对解析（带单位的「大小/大小」->百分比，两边必须带单位防日期误报）——Hook 侧 parseTextSizePairPct 放宽 onPosted 下载分支+百分比来源、常驻丢弃门豁免 isDownload、isDownloadNotif 同步放宽（轻气泡藏栏恢复）；NS 侧 readDownloadPct 加 parseSizePairPct 兜底、parseDownloadedTotal 加裸大小对形状+标题回退（字节数也能显示）。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v613-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb613-classes /tmp/bb613-dex && mkdir -p /tmp/bb613-classes /tmp/bb613-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb613-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb613-classes && $JDK/bin/jar --create --file /tmp/bb613-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb613-dex /tmp/bb613-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb613-apktool && cp -r $APKTOOL_DIR /tmp/bb613-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 613/' /tmp/bb613-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb613-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb613-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb613-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb613-base.apk")
zo = zipfile.ZipFile("/tmp/bb613-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb613-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb613-unsigned.apk /tmp/bb613-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb613-signed.apk /tmp/bb613-aligned.apk
$BT/apksigner verify --verbose /tmp/bb613-signed.apk
cp /tmp/bb613-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
