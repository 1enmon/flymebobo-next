#!/bin/bash
# v572 test build: 轻气泡第七版 (versionName 2.5.0 /
# versionCode 572)。wei 点头定稿的 App 布局重设计落地：
# 轻气泡 App 换三栏外壳（收纳 / 时光机 / 设置），不用
# 经典气泡式样——浅底、白卡、1px 细线、蓝只做点缀、
# 圆角统一 12。收纳/时光机两栏的数据走镜像通道：
# SystemUI 每次栈/音乐变化写 /sdcard/flymebobo/
# light_mirror.txt 并广播，App 读镜像渲染（音乐置顶卡
# + 列表 + 场景状态）。打开方式单独设计成两张示意图
# 卡（小窗=浮窗图 / 全局=全屏图）放进轻气泡卡，与经典
# 共用 miniwindow（v571 行为不变，经典打开方式卡在
# 轻气泡下不再出现）。通用卡（功能卡/安静规则/提示音/
# 模式卡）漏斗级浅色化：白卡、深字、蓝点缀、开关蓝轨。
# 切换/切回动画重做：五条彩色胶囊褪色压扁成细线铺平
# （切回时细线立起上色弹回），底色同步漫换，72% 处
# 换页 + 新页底色淡入衔接，总时长约 1.35 秒。
# 另加 wei 定的黑/白双模式：面板颜色 seg（黑色模式=
# 现行深色；白色模式=白底+黑色描边、文字深色），小签/
# 面板/音乐卡/内嵌菜单整套换皮，立即生效（键
# lightPanelWhite，文件镜像同 lightMode 口径）。
# 经典模式外观与行为不变。本地测试包，未提交未推送。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v572-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb572-classes /tmp/bb572-dex && mkdir -p /tmp/bb572-classes /tmp/bb572-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb572-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb572-classes && $JDK/bin/jar --create --file /tmp/bb572-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb572-dex /tmp/bb572-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb572-apktool && cp -r $APKTOOL_DIR /tmp/bb572-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 572/' /tmp/bb572-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb572-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb572-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb572-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb572-base.apk")
zo = zipfile.ZipFile("/tmp/bb572-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb572-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb572-unsigned.apk /tmp/bb572-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb572-signed.apk /tmp/bb572-aligned.apk
$BT/apksigner verify --verbose /tmp/bb572-signed.apk
cp /tmp/bb572-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
