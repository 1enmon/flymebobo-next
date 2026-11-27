#!/bin/bash
# v604 (2.5.0): 累积 v602+v603，再给经典侧补全文识别（wei 定「取件码识别给经典那边也优化一下」）。
# - v602: 航班 \b 修复/fullText 全文识别（轻气泡侧）/打标提速/InfoParse 加固（JVM 14 例全过）。
# - v603: AI 助手独立成卡（expandKey "ai"）、去「自带 Key」字样、开启 Toast 提示、AiKey 系统密钥库 AES-GCM 真加密落盘（旧明文兼容）。
# - v604 新增: NS 广播路（ACTION_NOTIFY）也带完整正文——NS 抽取点存 bigTextRaw、广播加 extra、XposedEntry 接收端填 NotifData.bigText；Hook 路 v602 已带。经典侧验证码/取件码识别（v602 已改 fullText）至此两条路都吃全文；关键词过滤扫描面同步改 fullText（促销词常只在 bigText 里）。显示仍用 text 不变。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v604-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb604-classes /tmp/bb604-dex && mkdir -p /tmp/bb604-classes /tmp/bb604-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb604-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb604-classes && $JDK/bin/jar --create --file /tmp/bb604-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb604-dex /tmp/bb604-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb604-apktool && cp -r $APKTOOL_DIR /tmp/bb604-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 604/' /tmp/bb604-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb604-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb604-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb604-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb604-base.apk")
zo = zipfile.ZipFile("/tmp/bb604-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb604-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb604-unsigned.apk /tmp/bb604-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb604-signed.apk /tmp/bb604-aligned.apk
$BT/apksigner verify --verbose /tmp/bb604-signed.apk
cp /tmp/bb604-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
