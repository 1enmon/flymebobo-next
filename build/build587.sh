#!/bin/bash
# v587 test build: 轻气泡第二十二版 (versionName
# 2.5.0 / versionCode 587)。四件事：① 下载时面板
# 旁呼吸点一直闪 → 面板打开即收起小签、关面板再请
# 回（wei 录屏定位）；② 状态栏下载通知没去掉 →
# 下载同音乐口径，钩子层只藏不撤（不进集合、数据
# 照走、tick 不受影响），notif+dl 开着才藏；③ 临时
# 静默「开启」按钮白药丸没底 → 轻气泡下浅蓝底；
# ④ 验证码自动复制搬进轻气泡（wei 定）：轻分支里
# 补同款识别，新条目收纳时复制验证码一次（取件码
# 不复制、开关照旧）。本地测试包，未提交未推送。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v587-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb587-classes /tmp/bb587-dex && mkdir -p /tmp/bb587-classes /tmp/bb587-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb587-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb587-classes && $JDK/bin/jar --create --file /tmp/bb587-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb587-dex /tmp/bb587-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb587-apktool && cp -r $APKTOOL_DIR /tmp/bb587-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 587/' /tmp/bb587-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb587-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb587-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb587-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb587-base.apk")
zo = zipfile.ZipFile("/tmp/bb587-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb587-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb587-unsigned.apk /tmp/bb587-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb587-signed.apk /tmp/bb587-aligned.apk
$BT/apksigner verify --verbose /tmp/bb587-signed.apk
cp /tmp/bb587-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
