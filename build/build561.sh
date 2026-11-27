#!/bin/bash
# v561 test build: 含 v560 全部内容（「全部应用」行
# 第一档文案改「弹出」), 另修一个 wei 实测 bug: 消息被
# 收纳后, 收纳面板点条目/「全部展开」展不开——重投打了
# recalled 标记走 popCapsule, 又被应用规则的 EDGE 落点
# 收回去 (收纳模式下 pileIntoFan 改道回收纳栈), 原地
# 打转。修法: popCapsule 应用规则段给 recalled==true
# 加 EDGE 落点豁免 (与 v542 去重豁免同思路), 按正常
# 弹出处理一次, 不改存储规则; NEVER 仍否决, 场景/
# 静默闸门照常, 关键词过滤的 pileIntoFan 落点不在
# recalled 路径上不受影响。日志 CollectExpand
# bypassEdge pkg=..。
# versionCode 561, versionName 2.4.7。
# NOT committed/pushed; local test package only.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.4.7-v561-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb561-classes /tmp/bb561-dex && mkdir -p /tmp/bb561-classes /tmp/bb561-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb561-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb561-classes && $JDK/bin/jar --create --file /tmp/bb561-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb561-dex /tmp/bb561-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb561-apktool && cp -r $APKTOOL_DIR /tmp/bb561-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.4.7/' -e 's/versionCode: 538/versionCode: 561/' /tmp/bb561-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb561-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb561-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb561-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb561-base.apk")
zo = zipfile.ZipFile("/tmp/bb561-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb561-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb561-unsigned.apk /tmp/bb561-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb561-signed.apk /tmp/bb561-aligned.apk
$BT/apksigner verify --verbose /tmp/bb561-signed.apk
cp /tmp/bb561-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
