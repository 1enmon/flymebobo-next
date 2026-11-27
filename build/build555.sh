#!/bin/bash
# flymebobo 2.0 — build & package v555 (2.4.6 test build).
# v555: 安静规则三件套（wei 定：整合进安静规则卡、应用
# 名单下方，风格全复用卡内现成构建）——
# 1) 连发突破（burstBreak，默认开）：同一发送者（pkg +
#    会话标题）5 分钟内连发到第 3 条，场景/静默本要排队
#    时改为直弹，并把该发送者已排队的旧条目一并补弹；
#    计数在 BubbleWindow（滚动窗 + 按通知 key 去重）。
# 2) @我突破（mentionBreak，默认开）：标题+正文命中
#    @我/有人@我/@了你 等模式（含全角＠）时突破排队。
# 3) 关键词过滤（keywordFilter，默认关）：普通通知命中
#    词表任一词 → 不弹，走 AppRules「只贴边」同款落点
#    pileIntoFan 收进扇子；词表 filterWords 字符串存
#    （换行分隔，默认促销/秒杀/领券/限时抢购/返现/优惠券），
#    走文件镜像通道 + SettingsStore 恢复白名单，改词走
#    FEATURE_TOGGLE 广播带 words extra 即时生效。
# 设置页三段全走 buildSubSwitch（同场景模式行），词表
# 药丸复用回看时长 chips 画法，添加行复用其自定义输入
# 行；搜索索引加三条；通知气泡总开关关时三行置灰联动。
# v543–v554 其余成果全保留。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.4.6-v555-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb555-classes /tmp/bb555-dex && mkdir -p /tmp/bb555-classes /tmp/bb555-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb555-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb555-classes && $JDK/bin/jar --create --file /tmp/bb555-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb555-dex /tmp/bb555-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb555-apktool && cp -r $APKTOOL_DIR /tmp/bb555-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.4.6/' -e 's/versionCode: 538/versionCode: 555/' /tmp/bb555-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb555-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb555-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb555-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb555-base.apk")
zo = zipfile.ZipFile("/tmp/bb555-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb555-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb555-unsigned.apk /tmp/bb555-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb555-signed.apk /tmp/bb555-aligned.apk
$BT/apksigner verify --verbose /tmp/bb555-signed.apk
cp /tmp/bb555-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
