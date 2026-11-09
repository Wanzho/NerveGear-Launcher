刀剑神域·誓约 / SAO Oath Launcher 0.1.0

Windows x64：完整解压 ZIP 后，双击“刀剑神域·誓约 启动器.exe”。请保留同目录的 app、runtime 文件夹。
macOS：按处理器选择 arm64（Apple 芯片）或 x64（Intel）。打开 DMG，将整个 .app 拖入“应用程序”。
Linux x64：解压 tar.gz，在桌面会话运行 ./SAO-Oath-Launcher.sh。需要系统提供 GTK3、X11/图形驱动和音频库。

每个平台均自带 Java 21 和 JavaFX，无需另装启动器运行环境。
启动器不内置模组。首次安装请打开启动器提供的夸克链接，在“模组”文件夹下载最新完整包，再拖入启动器。
同一链接内的“快速更新”文件夹供已安装的玩家使用；按提示下载所有需要的版本，支持一起或分次拖入。
Minecraft/NeoForge 基础文件仍由现有安装引擎下载，首次安装需要网络。
启动器使用自己的数据目录。不要删改 runtime、app 或 .app 的内容。

macOS 提示无法验证开发者：
1. 先尝试正常打开已复制到“应用程序”的 .app，再关闭提示。
2. 打开“系统设置”→“隐私与安全性”，在安全性区域找到该应用的阻止提示。
3. 点击“仍要打开”，按系统要求认证，再在确认框点击“打开”。
本包仅作 ad-hoc 临时签名，没有 Apple Developer ID，也未公证。企业策略可能禁止上述操作。
Apple 官方说明：https://support.apple.com/zh-cn/102445

启动器基于 HMCL，Copyright (C) 2013-2025 huangyuhui and contributors。
GPLv3 与附加条款见 licenses；运行环境的许可位于 runtime/legal（macOS 位于 .app/Contents/runtime/legal）。
本次生成的源代码归档与验证报告和安装包一同交付；未经实机运行验证的平台详见 REPORT.md。
