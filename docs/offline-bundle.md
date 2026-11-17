# NerveGear 离线包

玩家下载一个 ZIP，通过拖入或“选择文件”导入。启动器按根目录的 `bundle-index.json` 识别，解压到自己的 data root 下的 `dependencies/offline-bundle/`，不使用系统临时目录。导入显示文件计数和百分比，随后使用正常的安装流程。成功后提供删除解压副本的选项；下载的原始 ZIP 和已安装文件保留。

构建工具不下载文件，要求已有完整的 Minecraft 1.20.1 / NeoForge 47.1.106 安装，以及 Windows x64 的 Mojang `java-runtime-gamma`。同一安装还需补齐 vanilla manifest 列出的跨平台 libraries。构建会拒绝缺失或错误的输入，不会生成一个看似完整的半成品。

```sh
java -jar NerveGear-Launcher.jar --make-offline-bundle output.zip \
  --pack 'SAO OATH0.8.1.zip' --root /path/to/launcher-data \
  --policy /path/to/version.json
```

`--policy` 的同目录必须存在 `version.json.sig`；省略此选项时读取 `<root>/offline-policy/version.json`。它必须是现有 release 的真实签名文件，且 pack 的大小和 SHA-256 必须匹配。工具不签名，不上传。`--root` 省略时使用当前目录的 `.sao-oath-launcher`。

归档包含 `versions/`、`assets/`、`libraries/`、`installer/`、`java/`、`mods-cache/<sha1>`、`pack/` 和 `policy/`。`bundle-index.json` 格式为 `format: 1`，`pack` 指定整合包相对路径，`files` 每项包含 `path`、`size`、`sha1`、`sha256` 和 `source`。Modrinth 原始 SHA-512 保留在 pack index 中，实际安装同时校验 SHA-1、SHA-512（若有）与 size。

信任分两层：bundle 的自报 hashes 仅检测传输损坏。Minecraft/Java/NeoForge 的可执行内容必须匹配启动器随附的 `assets/sao-launcher/offline/trusted-files.json`。该 catalog 的 SHA-1 来自 Mojang manifests、NeoForge installer manifests，或固定输入安装生成的中间产物；生成的安装 manifest 和无上游独立 hash 的中间产物额外固定在 launcher release 中。catalog 的 SHA-256 固定已核验字节。任意 bundle 不能自行更换 launch manifest、Java 或 libraries。Asset objects 还由 Mojang asset index 的 SHA-1 校验。完整 pack 通过现有 Ed25519 signed version policy 和 snapshot 流程，沿用 anti-rollback。

catalog 专用于固定 game/loader/runtime 组合；升级此组合时需要重新核验并更新 catalog。随附的 `provenance.json` 记录 Mojang metadata 来源；NeoForge installer 自身包含 `install_profile.json` 和 `version.json`。只拷贝依赖和受管 pack 文件，不收集账户、存档或本机设置。

损坏或缺失的 ZIP 条目会被跳过并列出；安装可以按原有机制尝试其他本地缓存或网络补齐。完整且有效的 bundle 在验收测试中不需要任何网络请求。签名 policy 或 pack 损坏时会拒绝该 pack，不会降低签名校验要求。失败的安装保留解压副本供重试。
