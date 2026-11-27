#!/bin/bash
# v591 test build: 轻气泡第二十六版 (versionName
# 2.5.0 / versionCode 591)。两件新功能（wei 点
# 名）：① 码卡片落地（预览已点头）：带码通知在面
# 板里成置顶卡——音乐卡之下、普通行之上；大字码
# （打码显圆点）、种类片（验证码蓝/取件绿/取餐
# 橙）、柜号、已自动复制状态、复制键；取件/取餐
# 带「出示大码」全屏白页（亮度拉满、点任意处关、
# 不画假条码）；到达药丸直接闪码（打码只闪来
# 源）；行列表跳过带码条目，时光机仍是行。② 呼
# 吸灯边缘发光（默认开，轻气泡卡里有开关）：独立
# 不触摸小窗的径向光晕贴边缘、跟呼吸同拍、放歌
# 跟签流转颜色。本地测试包，未提交未推送。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v591-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb591-classes /tmp/bb591-dex && mkdir -p /tmp/bb591-classes /tmp/bb591-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb591-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb591-classes && $JDK/bin/jar --create --file /tmp/bb591-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb591-dex /tmp/bb591-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb591-apktool && cp -r $APKTOOL_DIR /tmp/bb591-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 591/' /tmp/bb591-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb591-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb591-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb591-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb591-base.apk")
zo = zipfile.ZipFile("/tmp/bb591-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb591-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb591-unsigned.apk /tmp/bb591-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb591-signed.apk /tmp/bb591-aligned.apk
$BT/apksigner verify --verbose /tmp/bb591-signed.apk
cp /tmp/bb591-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
