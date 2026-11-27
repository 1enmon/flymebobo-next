#!/bin/bash
# v567 test build: 轻气泡第四版 (versionName 2.5.0 /
# versionCode 567)。按 wei v566 实测反馈两改：① 音乐
# 卡按钮去毛玻璃（wei：「不要这个蒙版玻璃」「按钮就
# 单单是按钮」）——LightMusicBtn 圆盘填充+描边环全
# 摘，只剩字形，按下只剩 0.9 缩放；② 收纳面板外观
# 可调（wei：面板要能调整大小，含圆角/长宽/透明度/
# 比例）——五项打包字符串键 lightPanelStyle（宽dp,
# 可见行数,圆角dp,不透明%,比例%），走 prefs+文件镜
# 像+SettingsStore 备份白名单+FEATURE_TOGGLE style
# extra 同步 SystemUI；设置页轻气泡卡新增「面板外
# 观」五条滑杆（宽 240–360 / 高 3–8 行 / 圆角 0–36 /
# 透明度 30–100 / 比例 80–120），下次开面板生效；
# 面板宽/圆角/底色透明度/入场终点缩放/列表可见行数
# 全部改读该设置。v566 的歌词保活与整行显示不变。
# 经典路径零改动。本地测试包，NOT committed/pushed.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v567-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb567-classes /tmp/bb567-dex && mkdir -p /tmp/bb567-classes /tmp/bb567-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb567-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb567-classes && $JDK/bin/jar --create --file /tmp/bb567-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb567-dex /tmp/bb567-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb567-apktool && cp -r $APKTOOL_DIR /tmp/bb567-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 567/' /tmp/bb567-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb567-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb567-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb567-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb567-base.apk")
zo = zipfile.ZipFile("/tmp/bb567-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb567-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb567-unsigned.apk /tmp/bb567-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb567-signed.apk /tmp/bb567-aligned.apk
$BT/apksigner verify --verbose /tmp/bb567-signed.apk
cp /tmp/bb567-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
