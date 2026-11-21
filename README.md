# flymebobo 2.0

LSPosed 模块，把通知变成贴在屏幕左边的悬浮气泡（胶囊）——音乐、下载、验证码 / 取件码、来电、实况都有。

包名 `com.chenan.flymebobo`，versionName `2.0`。在 Flyme（MEIZU 20 Pro）上验证过；核心是通用 Android，但小窗打开用 Flyme WindowManagerExt，有些 hook 针对 SystemUI 内部。

## 功能

- 每条通知都弹成一个 180x44dp 的胶囊，按应用图标主色着色，贴在屏幕左边缘。通知永不自动展开。
- 点胶囊展开成卡片；点卡片打开对应的 App（默认小窗打开，开关关了全屏打开）。
- 每条通知可单独钉住；钉住的胶囊和卡片排在最上面。
- 音乐胶囊：实时歌词、专辑图、进度条和播放控制。
- 下载胶囊：实时进度、速度和剩余时间；应用商店的安装进度也包含。
- 验证码 / 取件码自动识别、放大、自动钉住，验证码自动复制。
- 来电胶囊：通过系统来电通知自带的按钮接听 / 挂断 / 扬声器。
- 设置 App（MainActivity），功能开关实时生效，拨动后不用重启 SystemUI。
- 设置 App 底部的链接：源码仓库、酷安主页、"请我喝咖啡"微信收款码。

当前版本：v310（versionCode 310）。

## 构建

需要 JDK 17+、Android SDK（android-34）、apktool、D8（r8）和 Xposed API jar。见 `build/build20.sh`。

## 许可证

GPL-3.0-only。部分实现参考了 Flyme Island（灵动岛）。
