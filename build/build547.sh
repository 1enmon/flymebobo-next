#!/bin/bash
# flymebobo 2.0 — build & package v547 (2.4.6 test build).
# v547: 修 v546 长按菜单三问题（wei 实测截图 4441 + 追加）。
# ① 层级：菜单/呼吸点面板窗口类型 TYPE_APPLICATION_OVERLAY
# → TYPE_SYSTEM_ERROR（与胶囊 dock/pile 同型；OVERLAY 在
# Flyme 上被 SYSTEM_ERROR 胶囊压住，菜单上半截被盖）。
# ② 定位：面板先藏，量完尺寸由 placeMenuPanel 按锚点放——
# 优先胶囊下方，放不下整块翻到胶囊上方，整块必须可见
# （旧 translation 回拉废弃）；回复框展开/收起重算位置。
# ③ 动画与单浮层：面板入场从胶囊边轻放大+淡入 200ms、
# 关闭淡出 150ms 后彻底拆窗；呼吸点面板轻淡入 180ms；
# 新增 closeOverlays() 统一浮层管理点，两 show 入口打开
# 前先立即拆已开浮层（含淡出中的），全局只留一个；
# 息屏时菜单也联动收起。v546 两功能其余一律不动。
# ——以下为 v546 原说明——
# v546: 两个新功能（wei 2026-11-26 晚拍板，预览已定稿）。
# ① 长按胶囊菜单（核心=快捷回复）：胶囊长按 400ms（CapsuleView
# 触摸计时）在胶囊下方弹深色菜单；NotifCollectionHook 在
# SystemUI 读到真实 Notification 时用 NotifActions 抽取回复
# （RemoteInput）/已读动作，按 key 暂存进 BubbleWindow（只在
# SystemUI 进程内，不进广播）；菜单项按能力出现——回复 /
# 标为已读 / 打开应用回复（兜底）/ 稍后提醒（10 分钟后
# recalled 式重投）/ 收进回看（回看开着才出现）/ 这个应用
# 不再弹（广播 ACTION_SET_APP_RULE 给 App 进程走 AppRules
# 落盘，单一数据源）。开关 longPressMenu 默认开。
# ② 排队呼吸点：场景/静默共用的 suppressedQueue 非空时左边缘
# 46% 高出 10×26dp 呼吸小点（场景橙/静默紫，混合优先橙，
# 3.2s 呼吸周期），点开瞄一眼面板（最多 5 条 + 还有 N 条，
# 只看不动队列）。守卫同回看把手（亮屏+已解锁）。开关
# queueDot 默认开。两开关都在「通知气泡」卡，随总开关联动
# 置灰，搜索索引已加。v543–v545（回看保留时长）成果全保留。
# 基线 v542（HEAD 53b74de 已提交）+ 未提交的 v543–v545。
set -e
export JDK=$HOME/workspace/tools/android-build/jdk
export BT=$HOME/workspace/tools/android-build/sdk/build-tools/34.0.0
export PATH=$JDK/bin:$BT:$PATH
SRC=$HOME/workspace/flymebobo-next/src
APKTOOL_DIR=$HOME/workspace/flymebobo-next/build/apktool
OUT=$HOME/workspace/flymebobo-2.4.6-v547-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb547-classes /tmp/bb547-dex && mkdir -p /tmp/bb547-classes /tmp/bb547-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb547-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb547-classes && $JDK/bin/jar --create --file /tmp/bb547-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb547-dex /tmp/bb547-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb547-apktool && cp -r $APKTOOL_DIR /tmp/bb547-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.4.6/' -e 's/versionCode: 538/versionCode: 547/' /tmp/bb547-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb547-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb547-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb547-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb547-base.apk")
zo = zipfile.ZipFile("/tmp/bb547-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb547-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb547-unsigned.apk /tmp/bb547-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb547-signed.apk /tmp/bb547-aligned.apk
$BT/apksigner verify --verbose /tmp/bb547-signed.apk
cp /tmp/bb547-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
