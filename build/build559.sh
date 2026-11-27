#!/bin/bash
# v559 test build: 在 v558（「全部应用」全局默认行 + 长按
# 菜单「这个应用以后收纳」+「只贴边」改名「收纳」）之上:
# (1) 完整名单页 QuietRulesActivity 同样加「全部应用」行
# (复用本页 segRow/setRuleMode/paint* 按 key 泛化逻辑,
# 常驻置顶不被搜索过滤, 底部计数排除默认条目, 场景里段
# 跟随 sceneMode 总开关置灰)。
# (2) 中间档翻面: AppRules 新增显式值 POP ("pop", 强制
# 弹出例外, 只绕应用规则层的默认收纳, 场景/静默闸门照
# 管); 单个应用中间档文字/写入跟着「全部应用」平时值
# 翻面 (默认收纳→显示「弹出」点写 POP; 默认非收纳→
# 显示「收纳」点写 EDGE), 默认变更时主页卡与完整名单
# 页全表中间档刷新; 长按菜单「以后收纳」显示条件放宽
# 到有效值 DEFAULT 或 POP。
# versionCode 559, versionName 2.4.7。
# NOT committed/pushed; local test package only.
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.4.7-v559-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb559-classes /tmp/bb559-dex && mkdir -p /tmp/bb559-classes /tmp/bb559-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb559-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb559-classes && $JDK/bin/jar --create --file /tmp/bb559-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb559-dex /tmp/bb559-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb559-apktool && cp -r $APKTOOL_DIR /tmp/bb559-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.4.7/' -e 's/versionCode: 538/versionCode: 559/' /tmp/bb559-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb559-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb559-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb559-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb559-base.apk")
zo = zipfile.ZipFile("/tmp/bb559-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb559-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb559-unsigned.apk /tmp/bb559-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb559-signed.apk /tmp/bb559-aligned.apk
$BT/apksigner verify --verbose /tmp/bb559-signed.apk
cp /tmp/bb559-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
