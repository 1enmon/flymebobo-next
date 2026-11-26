#!/bin/bash
# flymebobo 2.0 — build & package v539 (2.4.5 test build).
# v539: 边缘回看胶囊——手动左滑消散的普通气泡进回看栈
# （最多 5 条 / 3 分钟），左边缘把手点逐个经 onNotification
# 重弹（照样过场景/静默门）；开关 recallCapsule 默认关
# （主动开关）。基线 2.4.5 (ec70eae)。下面的历史注释是
# build545.sh 的原文（v538 提示音语义），保留备查。
# Adapted from build/build20.sh with the current toolchain paths
# (~/workspace/tools/android-build: Temurin JDK 17, SDK
# platform/build-tools 34, R8 8.5.35, libxposed api 102).
# v538: 提示音按应用默认语义反转 (wei 拍板: 应用默认不响,
# 设了才响) — no stored rule now resolves to 无 (silent)
# instead of following the global sound. New explicit stored
# values: "follow" (跟随全局) and "none" (explicit 无, only
# used to override the default-follow list); legacy "mute"
# parses and resolves as 无. Default-follow list (no rule ->
# follow, shown in the per-app list, overridable by explicit
# none): 微信/QQ/钉钉/TIM/企业微信 + the runtime default SMS
# package (Telephony.Sms.getDefaultSmsPackage, fallback
# com.android.mms) — SoundRules.isDefaultFollow /
# effectiveValue; BubbleSound.play resolves through it.
# Chip cycle: 无 -> 跟随全局 -> 轻木 -> 气泡啵 -> 清脆叮 ->
# 叮咚 -> 水滴 -> (custom if chosen) -> 无. Copy synced
# (没设的应用默认不响 etc.). Everything else from v537 kept;
# apktool.yml bumps versionCode to 538. On top of v537.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.4.5-v539test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb539-classes /tmp/bb539-dex && mkdir -p /tmp/bb539-classes /tmp/bb539-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb539-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb539-classes && $JDK/bin/jar --create --file /tmp/bb539-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb539-dex /tmp/bb539-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb539-apktool && cp -r $APKTOOL_DIR /tmp/bb539-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.4.5/' -e 's/versionCode: 538/versionCode: 539/' /tmp/bb539-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb539-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb539-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb539-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb539-base.apk")
zo = zipfile.ZipFile("/tmp/bb539-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb539-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb539-unsigned.apk /tmp/bb539-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb539-signed.apk /tmp/bb539-aligned.apk
$BT/apksigner verify --verbose /tmp/bb539-signed.apk
cp /tmp/bb539-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
