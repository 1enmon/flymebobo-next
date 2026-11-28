#!/bin/bash
# v644: AI 管线提速（wei 06:57 定：防抖缩短 + 打标小模型、摘要大模型）+ 模型自动识别（wei 06:58 提）。
# 1) 防抖 4s -> 1.2s（BubbleWindow.maybeRequestAi）。
# 2) handleAiAnalyze 拆两路并行：打标走 aiModelFast（小模型，留空跟随摘要模型）只回 tagsBlob；
#    摘要走 aiModel 只回 summaryText；共用新助手 aiChatOnce。信息核对仍走 aiModel 不变。
# 3) AI 卡加「打标模型」输入框（flymebobo_ai.aiModelFast）+「选摘要模型/选打标模型」按钮：
#    fetchAiModels 拉 /models 列表，showAiModelPicker 点选填入，aiModelRecommended 按名标推荐。
# 累积 v564-v613 全部内容（含 v613 夸克下载识别）。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v644-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb644-classes /tmp/bb644-dex && mkdir -p /tmp/bb644-classes /tmp/bb644-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb644-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb644-classes && $JDK/bin/jar --create --file /tmp/bb644-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb644-dex /tmp/bb644-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb644-apktool && cp -r $APKTOOL_DIR /tmp/bb644-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 644/' /tmp/bb644-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb644-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb644-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb644-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb644-base.apk")
zo = zipfile.ZipFile("/tmp/bb644-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb644-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb644-unsigned.apk /tmp/bb644-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb644-signed.apk /tmp/bb644-aligned.apk
$BT/apksigner verify --verbose /tmp/bb644-signed.apk
cp /tmp/bb644-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
