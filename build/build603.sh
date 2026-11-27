#!/bin/bash
# v603 (2.5.0): AI 助手独立成卡 + Key 本地加密储存（wei 三连定调）。
# 1. 「ai功能单独出来」：AI 助手从轻气泡卡里搬出，轻气泡组里单独一张可展开卡（卡头 AI/标题/副题，搜索 expandKey "ai"）。
# 2. 「只是去掉自带 Key 字样，Key 还是自己填」：标题改「AI 助手」，副题「收纳摘要 · 语义打标 · Key 本地加密储存」。
# 3. 「点击开启就提示为本地加密储存」：拨开总开关弹 Toast 提示；且话要算数——新增 AiKey.java（AndroidKeyStore AES-GCM）真加密落盘，NS 读取时解密，旧版明文兼容、下次保存自动升级密文。v602 内容（航班 \b 修复/fullText 全文识别/打标提速/InfoParse 加固）全量包含。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v603-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb603-classes /tmp/bb603-dex && mkdir -p /tmp/bb603-classes /tmp/bb603-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb603-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb603-classes && $JDK/bin/jar --create --file /tmp/bb603-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb603-dex /tmp/bb603-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb603-apktool && cp -r $APKTOOL_DIR /tmp/bb603-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 603/' /tmp/bb603-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb603-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb603-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb603-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb603-base.apk")
zo = zipfile.ZipFile("/tmp/bb603-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb603-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb603-unsigned.apk /tmp/bb603-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb603-signed.apk /tmp/bb603-aligned.apk
$BT/apksigner verify --verbose /tmp/bb603-signed.apk
cp /tmp/bb603-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
