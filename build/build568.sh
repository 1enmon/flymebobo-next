#!/bin/bash
# v568 test build: 轻气泡第五版 (versionName 2.5.0 /
# versionCode 568)。wei 指出内容滚动没搬到轻气泡这
# 边、并定调它也是通用元素——本版补上：面板行（收纳
# + 时光机）的标题/正文、音乐卡的歌名/歌手，全部同
# 经典 CapsuleView 口径（ellipsize 恒 MARQUEE、由同
# 一个 scroll 开关切换 selected：开则长内容滚动、关
# 则省略号；文字定宽 MATCH_PARENT 保跑马灯起得来，
# 经典 v355 教训）；音乐卡歌词行同经典钉住胶囊的歌
# 词口径恒滚动（repeat -1、恒 selected、换行重选起
# 滚），不跟 scroll 开关。药丸维持 v566 的整行折行
# 口径不动。v567 的纯字形按钮与面板外观五项不变。
# 经典路径零改动。本地测试包，NOT committed/pushed.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v568-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb568-classes /tmp/bb568-dex && mkdir -p /tmp/bb568-classes /tmp/bb568-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb568-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb568-classes && $JDK/bin/jar --create --file /tmp/bb568-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb568-dex /tmp/bb568-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb568-apktool && cp -r $APKTOOL_DIR /tmp/bb568-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 568/' /tmp/bb568-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb568-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb568-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb568-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb568-base.apk")
zo = zipfile.ZipFile("/tmp/bb568-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb568-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb568-unsigned.apk /tmp/bb568-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb568-signed.apk /tmp/bb568-aligned.apk
$BT/apksigner verify --verbose /tmp/bb568-signed.apk
cp /tmp/bb568-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
