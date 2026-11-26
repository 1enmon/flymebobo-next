#!/bin/bash
# flymebobo 2.0 — build & package v541 (2.4.5 test build).
# v541: 场景模式动画 30 分钟内最多播一次（wei 定）——开启与
# 退出合并计次，共用 SceneAnimCooldown 的最近播放时间；触发
# 点三处（SystemUI 进/出场景、App 设置拨场景开关）跨进程共
# 享：多路径小文件 + 进程内静态值 + App 播放后广播同步。
# 冷却只跳动画，不影响场景识别/排队/补弹；息屏/锁屏等既有
# 规则跳过的不消耗机会；静默动画不受影响。基线 v540（验证
# 码大码/自动复制开关，叠加 v539 回看胶囊，HEAD ec70eae 未
# 提交工作区）。下面的历史注释是 build545.sh 的原文（v538
# 提示音语义），保留备查。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.4.5-v541test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb541-classes /tmp/bb541-dex && mkdir -p /tmp/bb541-classes /tmp/bb541-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb541-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb541-classes && $JDK/bin/jar --create --file /tmp/bb541-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb541-dex /tmp/bb541-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb541-apktool && cp -r $APKTOOL_DIR /tmp/bb541-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.4.5/' -e 's/versionCode: 538/versionCode: 541/' /tmp/bb541-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb541-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb541-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb541-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb541-base.apk")
zo = zipfile.ZipFile("/tmp/bb541-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb541-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb541-unsigned.apk /tmp/bb541-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb541-signed.apk /tmp/bb541-aligned.apk
$BT/apksigner verify --verbose /tmp/bb541-signed.apk
cp /tmp/bb541-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
