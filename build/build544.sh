#!/bin/bash
# flymebobo 2.0 — build & package v544 (2.4.5 test build).
# v544: 回看保留时长选择框换自家风格（wei 审预览批准）——删
# v543 的系统 AlertDialog，改「通知气泡」卡内就地展开选择区：
# 胶囊药丸 chips（1/3/5/10 分钟 + 自定义）+ 自定义输入行，
# 行尾统一「›」箭头旋转；存储/广播/夹紧逻辑全沿用 v543。
# v543: 回看保留时长可自定义（wei「回看能不能加个时间」
# 「自定义时间」）——v539 写死的 3 分钟改为设置项 recallTtlMin
# （分钟，默认 3；预设 1/3/5/10 + 自定义 1–120 超范围夹紧），
# 存字符串 prefs，走 bubbleSoundId 同路（SettingsStore 字符串
# 白名单恢复 + FEATURE_TOGGLE 广播带 minutes extra +
# FeatureFlags 安全读/夹紧）；BubbleWindow 定时器读新值，
# 改值广播到达时对栈内条目按新值重排。「通知气泡」卡回看
# 开关行下加「回看保留时长」行，开关关时置灰联动；搜索
# 索引加一条。基线 v542（HEAD 53b74de 已提交）。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.4.5-v544test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb544-classes /tmp/bb544-dex && mkdir -p /tmp/bb544-classes /tmp/bb544-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb544-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb544-classes && $JDK/bin/jar --create --file /tmp/bb544-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb544-dex /tmp/bb544-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb544-apktool && cp -r $APKTOOL_DIR /tmp/bb544-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.4.5/' -e 's/versionCode: 538/versionCode: 544/' /tmp/bb544-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb544-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb544-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb544-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb544-base.apk")
zo = zipfile.ZipFile("/tmp/bb544-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb544-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb544-unsigned.apk /tmp/bb544-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb544-signed.apk /tmp/bb544-aligned.apk
$BT/apksigner verify --verbose /tmp/bb544-signed.apk
cp /tmp/bb544-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
