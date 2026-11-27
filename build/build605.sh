#!/bin/bash
# v605 (2.5.0): 累积 v604 + 票证 AI 兜底核对 + 面板排序三件（wei 连定）。
# 1. 票证 AI 兜底+同时核对（wei 定「开了ai就启用，本地加ai同时核对」）：收纳时本地解析照旧即时成卡；AI 总开关开着时，条目另排 3 秒防抖批发给 App 进程核对（infoBlob -> NS handleAiInfoCheck -> ACTION_AI_INFO_RESULT -> BubbleWindow.onAiInfoResult）。本地没认出而 AI 认出 -> 补卡（卡头标「AI 识别」，火车/机票照样排提醒）；本地已认出 -> AI 只补本地缺的事件时间（提醒跟着补排），种类对不上只记日志不翻本地卡。红线同前：带码条目与含验证码字样的永不外送；同 key@postTime 只核对一次防重复烧钱。
# 2. 面板卡片顺序（wei 定）：AI 开且有摘要时 AI 摘要卡置顶、音乐第二、码卡/信息卡/下载卡第三，收纳列表照旧在最后。
# 3. 普通通知按时间排列（wei 定）：收纳行按 postTime 从新到旧渲染（栈到达序不动）。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v605-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb605-classes /tmp/bb605-dex && mkdir -p /tmp/bb605-classes /tmp/bb605-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb605-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb605-classes && $JDK/bin/jar --create --file /tmp/bb605-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb605-dex /tmp/bb605-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb605-apktool && cp -r $APKTOOL_DIR /tmp/bb605-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 605/' /tmp/bb605-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb605-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb605-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb605-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb605-base.apk")
zo = zipfile.ZipFile("/tmp/bb605-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb605-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb605-unsigned.apk /tmp/bb605-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb605-signed.apk /tmp/bb605-aligned.apk
$BT/apksigner verify --verbose /tmp/bb605-signed.apk
cp /tmp/bb605-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
