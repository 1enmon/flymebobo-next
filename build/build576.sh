#!/bin/bash
# v576 test build: 轻气泡第十一版 (versionName 2.5.0 /
# versionCode 576)。wei 两条：①「这个收藏图标再小一
# 点」——音乐卡爱心路径在 24 网格里高约 16.7、其他字
# 形约 10.8，绕中心缩 0.78 齐平。②「下载的加到轻气泡
# 展开里面」+「轻气泡模式切换后不能显示经典模式内
# 容」——截图里经典绿色下载胶囊在轻气泡下照样弹。本
# 版：popCapsule 轻气泡分支拦下载（dl 开关开时）走
# lightDownloadIn 进面板当卡片（音乐卡之下、收纳列表
# 之上，图标/标题/状态/细进度条/百分比，点卡走下载自
# 己的打开通道）；进度广播 updateDownloadProgress 在
# 轻气泡下改刷面板卡（lightDownloadProgress 原地刷、
# 完成亮 3 秒撤卡）；通知移除与关模式清下载表；小签
# 在只有下载时也亮、面板也能开。实况/导航/公交同样不
# 再弹经典胶囊（不接入、系统通知留通知栏）；来电按
# wei 既定口径不碰。日志 Light download in/done
# remove/removed。本地测试包，未提交未推送。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v576-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb576-classes /tmp/bb576-dex && mkdir -p /tmp/bb576-classes /tmp/bb576-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb576-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb576-classes && $JDK/bin/jar --create --file /tmp/bb576-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb576-dex /tmp/bb576-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb576-apktool && cp -r $APKTOOL_DIR /tmp/bb576-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 576/' /tmp/bb576-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb576-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb576-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb576-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb576-base.apk")
zo = zipfile.ZipFile("/tmp/bb576-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb576-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb576-unsigned.apk /tmp/bb576-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb576-signed.apk /tmp/bb576-aligned.apk
$BT/apksigner verify --verbose /tmp/bb576-signed.apk
cp /tmp/bb576-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
