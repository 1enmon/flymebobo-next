#!/bin/bash
# v610 (2.5.0): 累积 v609 + 歌词多源级联（wei 定：每首歌尽量有歌词，AI 歌词排最后兜底）。
# fetchNetworkLyrics 改级联：本地缓存 -> LRCLIB（带 duration 参数）-> 网易云（搜索按歌名+时长选曲取 LRC）-> QQ 音乐（同口径，Referer+实体还原）-> AI 默写（要 flymebobo_ai 里有 Key；只要原文不要段落名，回不知道就放弃；无时间轴按时长均分估时间，前奏留 3%）。命中统一走 applySyncedLrc（v208 时长验版>10s 拒收并继续下一源、v228 滤制作名单、繁转简、存本地缓存）。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v610-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb610-classes /tmp/bb610-dex && mkdir -p /tmp/bb610-classes /tmp/bb610-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb610-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb610-classes && $JDK/bin/jar --create --file /tmp/bb610-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb610-dex /tmp/bb610-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb610-apktool && cp -r $APKTOOL_DIR /tmp/bb610-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 610/' /tmp/bb610-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb610-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb610-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb610-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb610-base.apk")
zo = zipfile.ZipFile("/tmp/bb610-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb610-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb610-unsigned.apk /tmp/bb610-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb610-signed.apk /tmp/bb610-aligned.apk
$BT/apksigner verify --verbose /tmp/bb610-signed.apk
cp /tmp/bb610-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
