#!/bin/bash
# v578 test build: 轻气泡第十三版 (versionName 2.5.0 /
# versionCode 578)。wei 三件事：① 轻气泡设置里关着的
# 子行被整行 45% 透明度洗得发白看不清（经典的置灰手
# 法搬到浅色卡上翻车）——「换成偏灰黑色算了」：轻气
# 泡下所有 0.45 置灰一律取消，文字按本来的偏灰黑正
# 常显示，状态看开关本身（chip 的 active 标记不动）；
# ② 轻气泡设置去掉经典「音乐胶囊」卡，这边改叫「音
# 乐卡片」：轻气泡卡里新增一节，总开关 lightMusicCard
# 默认开——开了音乐显示在收纳面板的卡片里，没开不
# 显示、音乐按普通通知收纳（同 key 重投只更新不重
# 闪）；关开关立即清面板音乐卡与歌词跟随。③ 经典音
# 乐卡的子设置除「模拟音乐」外三个都办过来（视频
# App/反方向的钟/QQ音乐免费听，同键同值两边共用，
# 总开关关时联动置灰不可点）；搜索条目同步改指向。
# 本地测试包，未提交未推送。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.5.0-v578-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb578-classes /tmp/bb578-dex && mkdir -p /tmp/bb578-classes /tmp/bb578-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb578-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb578-classes && $JDK/bin/jar --create --file /tmp/bb578-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb578-dex /tmp/bb578-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb578-apktool && cp -r $APKTOOL_DIR /tmp/bb578-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.5.0/' -e 's/versionCode: 538/versionCode: 578/' /tmp/bb578-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb578-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb578-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb578-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb578-base.apk")
zo = zipfile.ZipFile("/tmp/bb578-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb578-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb578-unsigned.apk /tmp/bb578-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb578-signed.apk /tmp/bb578-aligned.apk
$BT/apksigner verify --verbose /tmp/bb578-signed.apk
cp /tmp/bb578-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
