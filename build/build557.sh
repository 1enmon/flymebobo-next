#!/bin/bash
# v557 test build: (1) 全屏浮层漏撤回归修复——三个全屏浮层
# (长按菜单/排队面板/收纳面板) dismiss 统一 removeViewImmediate
# 且摘成功才清句柄、失败留句柄打日志; 息屏菜单改硬摘;
# FEATURE_TOGGLE 补 longPressMenu 分支 + 总开关关统一
# closeOverlays; 场景守卫顶层变桌面/概览时收浮层; 两面板根
# ACTION_DOWN 盒外即收; 整组消散/flush 尾校验菜单锚在场。
# (2) 设置搬家: 胶囊自动取色/胶囊滚动/扇子自动变淡三行从
# 外观卡迁入「通知气泡」卡 (parent notif, 联动挂总开关,
# 搜索索引同步)。versionCode 557, versionName stays 2.4.6.
# NOT committed/pushed; local test package only.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.4.6-v557-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb557-classes /tmp/bb557-dex && mkdir -p /tmp/bb557-classes /tmp/bb557-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb557-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb557-classes && $JDK/bin/jar --create --file /tmp/bb557-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb557-dex /tmp/bb557-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb557-apktool && cp -r $APKTOOL_DIR /tmp/bb557-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.4.6/' -e 's/versionCode: 538/versionCode: 557/' /tmp/bb557-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb557-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb557-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb557-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb557-base.apk")
zo = zipfile.ZipFile("/tmp/bb557-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb557-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb557-unsigned.apk /tmp/bb557-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb557-signed.apk /tmp/bb557-aligned.apk
$BT/apksigner verify --verbose /tmp/bb557-signed.apk
cp /tmp/bb557-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
