#!/bin/bash
# v606 (2.5.0): 累积 v605 + 同一张票识别（wei 定：要能区分同一张还是不同的票）。
# 票据指纹 fp：火车票有订单号认订单号（一张订单的购票/改签/提醒短信都是同一张票），没订单号退到 车次+日期+座位（同车次不同座位=不同票），再没座位用 车次+日期+区间 兜底；机票 = 航班+日期+座位（没座位退到起飞时间）；快递/账单沿用单号/账单 id。指纹相同 -> 新短信更新原卡（改签换时间/座位并进来、提醒重排）不出重复卡；退票/取消强信号词（退票成功/已退票/退款成功/已取消/取消成功）-> 撤掉对应票卡并取消提醒。fp 追加落盘（I 行第 12 字段，旧文件缺省 null）。诚实边界：改签若换了新订单号、无从关联，会出两张卡——宁可分开不误并。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v606-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb606-classes /tmp/bb606-dex && mkdir -p /tmp/bb606-classes /tmp/bb606-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb606-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb606-classes && $JDK/bin/jar --create --file /tmp/bb606-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb606-dex /tmp/bb606-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb606-apktool && cp -r $APKTOOL_DIR /tmp/bb606-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 606/' /tmp/bb606-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb606-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb606-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb606-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb606-base.apk")
zo = zipfile.ZipFile("/tmp/bb606-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb606-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb606-unsigned.apk /tmp/bb606-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb606-signed.apk /tmp/bb606-aligned.apk
$BT/apksigner verify --verbose /tmp/bb606-signed.apk
cp /tmp/bb606-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
