#!/bin/bash
# flymebobo 2.0 — build & package v540 (2.4.5 test build).
# v540: ① 验证码胶囊排版升级（wei 审过的修正版预览）——带码
# 胶囊改两行：第一行小字「应用名 · 标签」，第二行 23sp 粗体
# 大码（纯数字 6 位 3+3 分段，取件码柜号同行小字），胶囊高
# 52→60dp（仅带码）；打码时第二行分段圆点。② 「自动复制
# 验证码」开关（autoCopyCode，默认开 = 老行为）进「验证码
# 与隐私」卡，code 总开关关时置灰联动。基线 v539（回看
# 胶囊，HEAD ec70eae 未提交工作区）。下面的历史注释是
# build545.sh 的原文（v538 提示音语义），保留备查。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.4.5-v540test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb540-classes /tmp/bb540-dex && mkdir -p /tmp/bb540-classes /tmp/bb540-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb540-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb540-classes && $JDK/bin/jar --create --file /tmp/bb540-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb540-dex /tmp/bb540-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb540-apktool && cp -r $APKTOOL_DIR /tmp/bb540-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.4.5/' -e 's/versionCode: 538/versionCode: 540/' /tmp/bb540-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb540-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb540-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb540-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb540-base.apk")
zo = zipfile.ZipFile("/tmp/bb540-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb540-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb540-unsigned.apk /tmp/bb540-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb540-signed.apk /tmp/bb540-aligned.apk
$BT/apksigner verify --verbose /tmp/bb540-signed.apk
cp /tmp/bb540-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
