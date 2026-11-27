#!/bin/bash
# flymebobo 2.0 — build & package v550 (2.4.6 test build).
# v550: 息屏呼吸光「共存版」（wei 实测 v549 后改）。v549
# 光晕能看见，但万象息屏时钟被顶掉、且光晕有重影。改：
# ① 砍 overlay 路线（TYPE_SYSTEM_ERROR 全屏窗口被
# keyguard 当遮挡窗口 occlude 掉 AOD）——tryAodOverlay
# 代码保留但 refreshAodGlow 不再调用，日志打 route=
# overlay skipped (occludes AOD)；只留 keyguard-child
# 寄生路线（子 View 不构成新窗口、不 occlude）。② 光晕
# 全局只一份：寄生添加前按类型全树 dedupe 清旧，撤除时
# 全树清扫，日志打 AodGlow child count=；宿主状态留一
# 条 AodGlow shade vis/alpha/shown 日志。③ AodGlowView
# 显式 setBackgroundColor(TRANSPARENT)，onDraw 只描边
# 不 fill，时钟与光晕同屏共存。实验开关/亮屏即撤/无
# wakelock 不变；v543–v549 其余成果全保留。
# ——以下为 v549 原说明——
# v549: 两件事。① 排队呼吸点位置自定义（wei 定，预览已
# 过）：「通知气泡」卡在呼吸点行下加「呼吸点位置」行 + 卡
# 内展开 chips（贴边 左/右 × 高度 靠上/中间/靠下，默认
# L1=左+中=现状）；存字符串 queueDotPos 走文件镜像通道
# （fileOrPrefsString，v545 教训），设置页广播即时挪点；
# 小点本身可按住上下拖改高度（阈值内算点击瞄一眼），松
# 手落最近档位并广播 ACTION_SET_QUEUE_DOT_POS 给 App
# 落盘；瞄一眼面板跟随贴边/高度。顺带补 v546 漏掉的
# 长按菜单/排队呼吸点 Feature 条目（当时只有字段/联动/
# 搜索，设置里没行）。② 息屏呼吸光实验（B 档探路）：
# 开关 aodGlow 默认关；灭屏进 doze 时 BubbleWindow 两条
# 路线都试挂橙色呼吸光晕（route=keyguard-child 寄生锁
# 屏/通知栏窗口子 View + route=overlay 独立
# TYPE_SYSTEM_ERROR 窗口），各打 AodGlow 日志；亮屏立
# 刻撤；不拿 wakelock。AodGlowView 自绘多道描边柔光 +
# alpha 呼吸（约 3 秒一轮，挂上自动跑）。
# ——以下为 v548 原说明——
# v548: 修 v547 两点（wei 实测）。① 长按菜单第一行
# 「打开应用回复」文字竖排：buildMenuRow 的 label 没设
# singleLine，sub 长文案先占位后带权重的 label 被压到
# 一字宽逐字换行——label/sub 均改 singleLine +
# ellipsize=end，行宽改 MATCH_PARENT。② Telegram 频道
# 通知被判「不支持回复」：NotifActions 抽取在
# notification.actions 之外再翻 WearableExtender
# (extras android.wearable.EXTENSIONS 的 actions)；
# 两处都无回复动作时打 QuickReply diag 摘要日志。
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
OUT=$HOME/workspace/flymebobo-2.4.6-v550-test.apk

# 1. Compile (javac must use -source 17 -target 17; JDK 21 default is
# class version 65 which d8 cannot eat).
rm -rf /tmp/bb550-classes /tmp/bb550-dex && mkdir -p /tmp/bb550-classes /tmp/bb550-dex
$JDK/bin/javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar:$HOME/workspace/tools/android-build/libxposed-api.jar" \
  -d /tmp/bb550-classes $SRC/com/chenan/flymebobo/*.java

# 2. Dex (must use r8 8.5.35 D8; build-tools r34's own d8 8.2.2 NPEs on
# anonymous classes in field initializers).
cd /tmp/bb550-classes && $JDK/bin/jar --create --file /tmp/bb550-classes.jar .
$JDK/bin/java -cp $HOME/workspace/tools/android-build/r8.jar com.android.tools.r8.D8 --min-api 26 \
  --lib $HOME/workspace/tools/android-build/sdk/platforms/android-34/android.jar \
  --output /tmp/bb550-dex /tmp/bb550-classes.jar

# 2b. Temp apktool copy: bump versionName to 2.4.5 (versionCode stays
# 538) in the COPY only; the repo apktool.yml keeps 2.4.4.
rm -rf /tmp/bb550-apktool && cp -r $APKTOOL_DIR /tmp/bb550-apktool
sed -i -e 's/versionName: 2.4.4/versionName: 2.4.6/' -e 's/versionCode: 538/versionCode: 550/' /tmp/bb550-apktool/apktool.yml
grep -E "versionCode|versionName" /tmp/bb550-apktool/apktool.yml
APKTOOL_DIR=/tmp/bb550-apktool

# 3. Resources (apktool must use --use-aapt2, else it falls back to the
# system aapt1 and dies with "First type is not attr!").
$JDK/bin/java -jar $HOME/workspace/tools/apktool.jar b --use-aapt2 $APKTOOL_DIR -o /tmp/bb550-base.apk

# 4. Assemble: replace dex + add META-INF/xposed from project files.
# (zipfile append mode never replaces a same-name entry, so the whole
# zip must be rebuilt — otherwise the APK ends up with two classes.dex.)
python3 - <<'EOF'
import zipfile, os
zb = zipfile.ZipFile("/tmp/bb550-base.apk")
zo = zipfile.ZipFile("/tmp/bb550-unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for item in zb.infolist():
    if item.filename != "classes.dex":
        zo.writestr(item, zb.read(item.filename))
zo.write("/tmp/bb550-dex/classes.dex", "classes.dex")
xposed_dir = os.path.expanduser("~/workspace/flymebobo-next/build/apktool/original/META-INF/xposed")
for name in ["module.prop", "scope.list", "java_init.list"]:
    zo.write(os.path.join(xposed_dir, name), "META-INF/xposed/" + name)
zo.close()
EOF

# 5. Align + sign (debug keystore, password android) + verify.
$BT/zipalign -f 4 /tmp/bb550-unsigned.apk /tmp/bb550-aligned.apk
$BT/apksigner sign --ks $HOME/workspace/tools/android-build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out /tmp/bb550-signed.apk /tmp/bb550-aligned.apk
$BT/apksigner verify --verbose /tmp/bb550-signed.apk
cp /tmp/bb550-signed.apk "$OUT"
echo "DONE: $OUT"
$BT/aapt2 dump badging "$OUT" | grep -E "package:|application-label"
