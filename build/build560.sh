#!/bin/bash
# v560 test build: 在 v559（完整名单页「全部应用」行 +
# 中间档翻面/POP 例外值）之上只做一处文案/语义修正:
# 「全部应用」行自己的「平时」第一档改叫「弹出」——它
# 是最顶层默认, 没有谁可跟随 (存储值仍是 AppRules.
# DEFAULT, 选中态与回退语义不变)。主页卡与完整名单页
# 各加默认行专用标签数组 (弹出/收纳/永不弹), 只给
# KEY_DEFAULT 那一行用; 普通应用行的「跟随」与 v559
# 翻面逻辑 (默认行已排除翻面, 中间档恒为「收纳」)
# 不受影响。搜索索引「全部应用」同义词补「弹出」。
# versionCode 560, versionName 2.4.7。
# NOT committed/pushed; local test package only.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.4.7-v560-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb560-classes /tmp/bb560-dex && mkdir -p /tmp/bb560-classes /tmp/bb560-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb560-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb560-classes && $JDK/bin/jar --create --file /tmp/bb560-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb560-dex /tmp/bb560-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb560-apktool && cp -r $APKTOOL_DIR /tmp/bb560-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.4.7/' -e 's/versionCode: 538/versionCode: 560/' /tmp/bb560-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb560-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb560-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb560-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb560-base.apk")
zo = zipfile.ZipFile("/tmp/bb560-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb560-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb560-unsigned.apk /tmp/bb560-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb560-signed.apk /tmp/bb560-aligned.apk
$BT/apksigner verify --verbose /tmp/bb560-signed.apk
cp /tmp/bb560-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
