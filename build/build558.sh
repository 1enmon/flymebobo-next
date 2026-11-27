#!/bin/bash
# v558 test build: (1) 安静规则加「全部应用」行——全局默认
# 条目 (__default__) 走 AppRules/SceneExceptions 同一套存储
# 与下发通道, 回退顺序 显式 per-app > 默认 > 内置缺省。
# (2) 长按菜单加「这个应用以后收纳」行 (有效规则为跟随时
# 才显示, 广播 mode=edge 落盘, 当前条目走 pileIntoFan 落地)。
# (3) 改名: 显示文案「只贴边」统一改「收纳」(内部 EDGE/
# 存储值不动; 搜索同义词保留「贴边」; 扇子形态名不动)。
# versionCode 558, versionName 2.4.7 (新一摞开发版)。
# NOT committed/pushed; local test package only.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.4.7-v558-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb558-classes /tmp/bb558-dex && mkdir -p /tmp/bb558-classes /tmp/bb558-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb558-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb558-classes && $JDK/bin/jar --create --file /tmp/bb558-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb558-dex /tmp/bb558-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb558-apktool && cp -r $APKTOOL_DIR /tmp/bb558-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.4.7/' -e 's/versionCode: 538/versionCode: 558/' /tmp/bb558-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb558-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb558-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb558-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb558-base.apk")
zo = zipfile.ZipFile("/tmp/bb558-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb558-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb558-unsigned.apk /tmp/bb558-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb558-signed.apk /tmp/bb558-aligned.apk
$BT/apksigner verify --verbose /tmp/bb558-signed.apk
cp /tmp/bb558-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
