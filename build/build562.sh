#!/bin/bash
# v562 test build: 含 v558-v561 全部内容, 另补全安静
# 规则分段控件的镜像规则最后一块 (wei 拍板): 单个
# 应用「平时」三档里与「全部应用」默认重复的那档换成
# 「弹出」——默认=收纳时中间档变弹出 (v559 已做);
# 默认=永不弹时本次新增第三档变弹出, 点它写 POP,
# 中间档仍是收纳写 EDGE。存量行按存量显示并选中
# (POP 的显示档位随默认走: 默认永不弹在第三档,
# 其余在中间档); 默认行被改动时两页全表重算。
# versionCode 562, versionName 2.4.7。
# NOT committed/pushed; local test package only.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.4.7-v562-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb562-classes /tmp/bb562-dex && mkdir -p /tmp/bb562-classes /tmp/bb562-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb562-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb562-classes && $JDK/bin/jar --create --file /tmp/bb562-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb562-dex /tmp/bb562-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb562-apktool && cp -r $APKTOOL_DIR /tmp/bb562-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.4.7/' -e 's/versionCode: 538/versionCode: 562/' /tmp/bb562-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb562-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb562-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb562-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb562-base.apk")
zo = zipfile.ZipFile("/tmp/bb562-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb562-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb562-unsigned.apk /tmp/bb562-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb562-signed.apk /tmp/bb562-aligned.apk
$BT/apksigner verify --verbose /tmp/bb562-signed.apk
cp /tmp/bb562-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
