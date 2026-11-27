#!/bin/bash
# v611 (2.5.0): 累积 v610 + AI 摘要边缘循环闪（wei 定：开启 AI 摘要后，摘要完成了就发出摘要通知一直闪；播放歌词时不显示）。
# 新摘要（与上一份不同）就绪 -> lightAiFlashText 挂待闪，药丸以「✦ AI 摘要：…」整行模式亮起，lightPulseEndRunnable 收尾时续闪，循环到打开面板（showLightPanel 里 stop）/关 AI/切回经典才停。优先级：到达闪 > 歌词 > 摘要——放歌且词跟随开着时 maybeFlashLightSummary 让路不闪（先挂着），音乐停（onLightMusicGone）或词关掉（onLightLyricToggle）后接着闪。面板开着时不启动。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v611-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb611-classes /tmp/bb611-dex && mkdir -p /tmp/bb611-classes /tmp/bb611-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb611-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb611-classes && $JDK/bin/jar --create --file /tmp/bb611-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb611-dex /tmp/bb611-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb611-apktool && cp -r $APKTOOL_DIR /tmp/bb611-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 611/' /tmp/bb611-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb611-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb611-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb611-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb611-base.apk")
zo = zipfile.ZipFile("/tmp/bb611-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb611-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb611-unsigned.apk /tmp/bb611-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb611-signed.apk /tmp/bb611-aligned.apk
$BT/apksigner verify --verbose /tmp/bb611-signed.apk
cp /tmp/bb611-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
