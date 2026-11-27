#!/bin/bash
# v602 (2.5.0): 三件实测修复 + 规则加固（wei 实测 v601 报）。
# 1. 航班识别不了：Java 的 \b 对中文不算边界（中文算字母），JD5712 两端不成界——改负向环视 + 数字头航司（9C/3U/8L）逐候选验字母。
# 2. 取件码/顺丰单号识别不了：模块历来只读短正文 android.text，全文在 android.bigText 没人读——测试 App 与真实应用都把全文放 bigText（顺丰短正文把单号截到 11 位、菜鸟短正文只到「取件码：8」）。新增 NotifData.bigText + fullText()（取更长者），识别类功能（验证码/取件码、信息卡片、AI 组包）全改读 fullText，显示仍用 text 不变。
# 3. 打标提速（wei 问）：AI 防抖 12 秒 -> 4 秒；打开面板时有挂起请求立即发。
# 4. InfoParse 规则加固（wei 令「再仔细优化」）：日期多格式（ISO/月日号）、时间多格式（H时mm分）、车次：兜底、站名/地名黏连清洗（分从重庆北/至深圳北/往北京首都）、座位「车厢」写法、检票口/登机口冒号、航班时间优先取「起飞」附近、航线箭头缺失用机场名兜底、快递补极兔 JT 与纯数字单号边界守卫+公司名文本提示、账单补「xx.xx元」金额与 M/D 到期日。JVM 14 例自测全过（含手机号/聊天防误报）。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v602-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb602-classes /tmp/bb602-dex && mkdir -p /tmp/bb602-classes /tmp/bb602-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb602-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb602-classes && $JDK/bin/jar --create --file /tmp/bb602-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb602-dex /tmp/bb602-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb602-apktool && cp -r $APKTOOL_DIR /tmp/bb602-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 602/' /tmp/bb602-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb602-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb602-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb602-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb602-base.apk")
zo = zipfile.ZipFile("/tmp/bb602-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb602-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb602-unsigned.apk /tmp/bb602-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb602-signed.apk /tmp/bb602-aligned.apk
$BT/apksigner verify --verbose /tmp/bb602-signed.apk
cp /tmp/bb602-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
