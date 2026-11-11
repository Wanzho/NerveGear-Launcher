# Gitee policy, Quark packs and launcher updates

Gitee supplies bounded announcement/version documents and detached Ed25519 signatures. Gitee is a transport, not the release trust authority: only signatures from the launcher’s embedded key list authorize release policy or remote announcements. Players download the launcher, full pack or quick updates manually from **one Quark share**, whose folders are `启动器`, `模组` and `快速更新`. Every download button uses the top-level `quark` link. The engine never downloads these artifacts from Gitee or Quark. Production startup fetches metadata in the background; installed-pack startup never waits for Gitee. Explicit isolated engines and tests never request public metadata.

Missing, invalid or unavailable metadata retains the last valid policy. With no valid policy, no launcher minimum is imposed. A cached mandatory minimum still applies during an outage. Pack updates remain advisory and never downgrade a newer installed release. All Link Start entry points recheck the launcher minimum before account tickets, runtime preparation and final game launch.

Default endpoints:

- `https://gitee.com/xwzho/sao-oath-launcher/raw/master/news.json`
- `https://gitee.com/xwzho/sao-oath-launcher/raw/master/version.json`

Each document has a sibling `.sig` URL (`news.json.sig`, `version.json.sig`) under the same raw base URL. Its complete content is canonical base64 of the 64-byte Ed25519 signature over the **exact document bytes**, with no JSON wrapper. Changing whitespace, newlines or UTF-8 encoding after signing invalidates the signature.

Overrides are `-Dsao.newsUrl=https://...` and `-Dsao.versionUrl=https://...`; an override changes transport only and cannot replace trusted keys. Each logical document/signature pair has one **10-second deadline**, including both bodies and each resource’s one permitted redirect from `https://gitee.com/` to `https://raw.giteeusercontent.com/`. Other redirects, extra hops, credentials, fragments and explicit ports are rejected. News is capped at 262144 bytes; version JSON at 16384 bytes; signatures are capped at 128 bytes and must contain exactly 88 canonical base64 ASCII characters without whitespace. Raw bytes are verified before UTF-8 decoding and schema parsing.

Accepted document bytes, signatures and independent highest accepted news/version sequences are committed together to `online-release-state.json` in the launcher data root, under `online-release-state.lock`. Cache loading re-verifies signatures and schema; old unsigned `online-news.json` / `online-version.json` files do not authorize anything. A missing, malformed, incorrect or stale signature/document is unavailable: a log line only, no update prompt or error dialog, and the last-good policy/news remains. Damaged cache/state is rejected rather than trusted. A sequence below the corresponding persisted high-water mark is refused; equal is allowed for re-fetch. Use integers from 0 through 9223372036854775807 and increase each document’s sequence independently whenever its content changes.

The embedded trust-key list currently contains one Ed25519 X.509 SubjectPublicKeyInfo key: `MCowBQYDK2VwAyEA8cAcouYSyhc8kQ8yTsMaDWhnhpy1o5wz8WB5vtM3pAk=` (DER SHA-256 prefix `97999b340f359c7e`). There is no Gitee-controlled key override. A future launcher release can add a rotation key; its JAR identity must be authorized by a version document signed by an already trusted key. Keep the private signing key outside the repository and Gitee. Players must first install a trusted build containing this verifier; older unsigned launchers do not gain this protection from a metadata change alone. This protection assumes the launcher binary and local data root remain trusted; deleting local state resets remembered sequence history, and a compromised Gitee account can still withhold files.

## version.json schema 2

Nothing has been published under schema 1; it is deliberately rejected. All mandatory fields are shown below. Filenames, sizes and hashes in this example are illustrative and **must be replaced** by verified release identities.

```json
{
  "schema": 2,
  "sequence": 1,
  "quark": "https://pan.quark.cn/s/1a81ea41915c",
  "pack": {
    "version": "0.8.2",
    "file": "SAO OATH0.8.2.zip",
    "size": 123456,
    "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
  },
  "updates": [
    {
      "from": "0.8",
      "to": "0.8.1",
      "file": "SAO-0.8-to-0.8.1.zip",
      "size": 12345,
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
    },
    {
      "from": "0.8.1",
      "to": "0.8.2",
      "file": "SAO-0.8.1-to-0.8.2.zip",
      "size": 12346,
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
    }
  ],
  "launcher": {
    "latest": "0.1.1",
    "min": "0.1.0",
    "file": "NerveGear-Launcher-0.1.1.jar",
    "size": 12345678,
    "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
    "nativeRequired": false
  }
}
```

Strict validation rejects unknown fields, duplicate JSON keys, nulls, comments, trailing input and primitive coercions. Versions have one to four numeric components, each one to nine digits without leading zeroes except `0`. Comparison is numeric: `0.10 > 0.8` and `0.1 == 0.1.0`. `launcher.latest >= launcher.min`.

`pack` and each quick update have a portable lowercase `.zip` basename, positive integer byte size and exactly 64 lowercase hexadecimal SHA-256 characters for the **complete compressed download**. Paths, control characters, Windows reserved names/characters, leading dots, exponents, fractional sizes and strings used as sizes are rejected. Manual filenames may differ; acceptance uses size and hash. The Quark link must use the exact `https://pan.quark.cn/` origin without credentials, fragments or explicit ports.

`updates` is required, even when empty, and has at most 256 entries. Each `from` is numerically unique and each edge increases its version without passing `pack.version`. Every advertised edge must reach `pack.version`; cycles and gaps are rejected. An installed version absent from the valid chain requires the current complete pack.

`launcher.latest` and `launcher.min` are required. Its `file`, `size` and `sha256` are optional **as one complete group**. `file` may be a portable `.jar` basename or a `.zip` basename whose archive contains exactly one portable basename JAR, with no directories or other files. `nativeRequired` is an optional actual JSON boolean, default `false`; strings and numbers are rejected. Missing artifact identity, `nativeRequired: true`, or a running launcher below `min` requires the complete native launcher from Quark. A JAR shortcut never bypasses that gate.

## 正式服目标、账号 API 与维护通知（仍为 schema 2）

`version.json` 可增加以下三个可选顶层字段；老的 schema 2 签名文件继续有效。未知字段、重复字段、错误类型和 JSON null 仍然拒绝。这些设置与发布文件身份一起签名、校验并保存到同一个高水位缓存，不能由未签名公告或状态 ping 开启/关闭。

```json
{
  "server": "mc.saooath.com",
  "accountApi": "https://argus.example.com/",
  "maintenance": {
    "message": "正式服维护中，安装新的登录验证",
    "until": "今晚 20:00"
  }
}
```

上面只是要合并进完整 `version.json` 的字段示例，**不是独立可发布文件**；`argus.example.com` 是占位域名，不是真实生产接口。

- `server` 是 1–253 字符的小写 DNS 主机名，可附带 `:1`–`:65535`，总长最多 259。每个 DNS 标签 1–63 字符，只允许小写字母、数字和内部连字符；不接受协议、路径、空白、下划线或末尾点。选择顺序为：**非空整合包清单 server → 已验证签名 server → mc.saooath.com**。保留清单优先，避免把明确指定测试服/专用服的测试包、安装记录引向正式服。屏幕地址、状态 ping、QuickPlay 与 ARGUS ticket 使用同一目标；离线启动仍无 QuickPlay。原有非 ARGUS 无清单/签名目标时的普通启动行为保留。
- `accountApi` 是不超过 2048 字符的 HTTPS URL，必须有主机、可选的 1–65535 端口，以及以 `/` 开头和结尾的路径；不接受用户信息、query、fragment。账号选择顺序为：显式 `sao.account.api` / `SAO_ACCOUNT_API` / 本地 `accountApi` 设置（及隔离账号测试 seam）→ 签名 `accountApi` → 非空整合包 `accountApi` → 内置默认。内置默认仍是 `https://auth.saooath.com:61784/`，不是已经可用的生产接口承诺。
- `accountApi` 在启动时从重新验签的缓存应用；后台首次/后续验签成功时，仅在账号已退出且没有排队/进行中的账号操作时应用。已登录会话保持原 API，变更会记录日志，并在退出后的下一次成功刷新或重启时应用；缓存可先保存新 API。移除该字段同样受会话保护。正式切换 API 后应提示当前已登录玩家重新打开启动器。
- `maintenance` **存在即开启维护通知**，删除该字段即关闭。`message` 是 1–200 个 Unicode 字符；`until` 可省略，最多 40 个 Unicode 字符（可为空）；两者均禁止 C0/C1 控制字符，包括换行、制表符。`until` 仅是显示的预计结束时间，不会定时自动结束维护。维护通知不会改变最低启动器版本，也不会单独禁止 Link Start；入服权限仍由服务器控制。

服务器窗口在打开/每 60 秒调用 `refreshOnline()`：新闻和状态保持原节奏，版本策略最多每 **180 秒**尝试一次。刷新按钮应调用新接口 `refreshOnline(true)`，版本策略最多每 **30 秒**尝试一次。`refreshUpdates()` 也按按钮限额执行。两种节奏共用上一次尝试时间，失败也计入限额；正在执行的刷新仍合并。生产启动后台请求共享同一计时器，不等待网络。每个 JSON/签名对的十秒 deadline、签名、sequence、高水位、防回滚和失败保留上次有效数据规则均不变。

### 团队操作：开始维护、切换正式 API、结束维护

1. **开始维护**：从当前完整 `version.json` 修改，将它自己的 `sequence` 加 1，设置 `server: "mc.saooath.com"`（或保留字段缺省），加入 `maintenance.message` 和可选 `until`。保留真实 `pack` / `updates` / `launcher` 身份。`news.json` 可同步写玩家公告；若修改新闻内容，将新闻自己的 `sequence` 也加 1。
2. **部署生产账号 API**：为 ARGUS HTTPS 服务配置实际 DNS 名称、实际监听/反向代理端口和受玩家 Java 信任且域名匹配的 TLS 证书。例如最终选择标准 443 端口时可用 `https://auth.saooath.com/`；只有部署确认后才能填。2026-11-10 的已提供 DNS 事实中 `auth.saooath.com` 无记录，61784 是游戏 SRV 端口，不能直接当成 HTTPS API 端口。不要填写测试接口。将真实 API 写入 `accountApi`，再把版本 `sequence` 加 1。这样已经发出的新启动器能在验签后采用接口，无需再发启动器。
3. 在正式服维护窗口安装并配置服务端 `sao_auth` / ARGUS 验证，确保客户端 launch ticket 的验证流程与该正式 API 配套。在部署后的实际环境另行验证登录和入服；本后端修改不部署服务端，也不能证明入服成功。
4. **签名与校验**：UI 合并并完成最终重建后，使用包含本修改的最终可信 JAR 执行下面四个命令。私钥保留在本地受保护位置；JSON 若签名后改任何一个字节，必须重新签名。已经存在 `.sig` 时，确认 JSON/sequence 后显式加 `--force`。

```sh
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true java -Djava.awt.headless=true \
  -jar NerveGear-Launcher.jar --sign-release version.json --key /secure/release-ed25519-private.pem
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true java -Djava.awt.headless=true \
  -jar NerveGear-Launcher.jar --verify-release version.json
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true java -Djava.awt.headless=true \
  -jar NerveGear-Launcher.jar --sign-release news.json --key /secure/release-ed25519-private.pem
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true java -Djava.awt.headless=true \
  -jar NerveGear-Launcher.jar --verify-release news.json
```

5. 两个工具对版本策略打印一行 `OK sequence=… maintenance=on/off server=… accountApi=…`，缺省值显示内置目标；显式整合包/本地覆盖仍可能改变运行时最终选择。新闻文件打印 `maintenance=n/a server=n/a accountApi=n/a`，因为新闻没有修改目标/维护策略的权限。
6. **逐字节原样上传** `version.json`、`version.json.sig`、`news.json`、`news.json.sig` 四个文件到 Gitee 发布分支根目录；每个 JSON 必须和自己的签名同时更新，最好同一提交。不要用网页编辑器重新排版、改 BOM、换行或编码。未修改的新闻可以保持原序列与原签名。若上传过程短暂不成对，客户端会拒绝本次刷新并保留之前的有效缓存。
7. **结束维护**：在服务端部署验证完成后，删除整个 `maintenance` 字段（不要设 null），保留真实 `server` 与 `accountApi`，将版本 `sequence` 再加 1。若改新闻也增加其独立 sequence。再次签名、校验并原样上传四个文件。在线启动器会在下次允许的刷新中去掉通知；缓存中的维护状态在无网络/验签失败时继续显示，不能保证所有玩家同时看到切换。

### UI 接线（由 UI 负责人完成）

监听现有稳定 `service.updateStatus()`，读取 `status.maintenance()`（`SaoData.Maintenance`，可为 null）。存在时在右侧“服务器”窗口以纯文本显示 `message()` 和非空 `until()`；null 时移除维护说明。维护提示独立于 `serverStatus().online()`，即使 ping 失败也应显示。每次 updateStatus 替换时重新读取 `service.serverAddress()`；现有 serverStatus 监听仍负责刷新人数/ping。刷新按钮改为 `refreshOnline(true)`，打开/60 秒 timer 保留 `refreshOnline()`。

`SaoFakeService` 已有稳定的 `updates` (`SimpleObjectProperty<UpdateStatus>`) 与 `forceUpdates(...)`；复用它们，提供带 `VersionMetadata(..., server, accountApi, maintenance)` 的维护演示数据，并通过替换 UpdateStatus 演示 on→off；其无维护构造器继续可用。假服务需使 `serverAddress()` 与演示 metadata.server 一致，必要时实现 `refreshOnline(boolean)` 委托已有模拟刷新。本次没有修改任何 `org/jackhuang/hmcl/ui/` 文件。

## news.json schema 2

Remote news also requires `schema: 2` and its own integer `sequence`. Entries remain plain text with `kind` (`NOTICE` or `UPDATE`), required `title` (1–200 characters), optional `date` (up to 40 characters), and optional `body` (up to 16000 characters); at most 100 entries. Unknown/duplicate fields, coercions and trailing input are rejected.

```json
{
  "schema": 2,
  "sequence": 1,
  "news": [
    {"kind": "NOTICE", "title": "维护通知", "date": "2026-11-10", "body": "维护结束后可正常进入。"}
  ]
}
```

Sign this file separately as `news.json.sig`. A signature for another file cannot authorize this document. Embedded pack changelog entries remain local and independent of remote news.

## Pack drops and transactions

A missing pack shows exactly `请把在夸克里下载的模组文件拖进来`. The UI also has `metadata.pack.version` for displaying which complete release to download. A valid full pack requires matching compressed size, SHA-256 and internal manifest version. Failure reports exactly `文件不完整或不是这个版本，请重新下载`.

An installed pack with a chain shows every destination version, in order:

- `请把 [0.8.1] 的快速更新包拖进来`
- `请把 [0.8.1] 和 [0.8.2] 的快速更新包拖进来`
- `请把 [0.8.1]、[0.8.2] 和 [0.8.3] 的快速更新包拖进来`

Drops may arrive together or separately, in any order and under any filename. Each good quick archive is copied privately and checked against the signed release policy’s compressed identity and its own `from`/`to` manifest. Good inputs remain accumulated when another input is wrong. A valid full pack always supersedes pending quick updates, including when only some quick hops have arrived. Completion applies all hops automatically in version order; simply finding a file in Downloads never installs it without explicit acceptance.

The installer holds its instance OS lock throughout base verification, complete-chain composition and the one final preserving commit. The installed receipt must match the first hop's complete `from` inventory. Before writes, every strict managed file must match both the old SHA-1 inventory and its quick manifest SHA-256. This includes every `.jar`, wherever located (even filenames containing `keybind`), scripts, assets and authored game data. Unknown files at newly managed strict destinations prevent quick application. A modified/missing strict base reports `当前文件不适合快速更新，请下载完整模组包。` followed by the full-pack drag instruction. Missing chains also use the full-pack prompt.

Normal play may rewrite the following managed settings. Their content or absence does not invalidate the base:

- Under `config/`, `defaultconfigs/`, or `kubejs/config/`: `.toml`, `.ini`, `.properties`, `.cfg`, `.conf`, `.yaml`, `.yml`.
- Under `defaultconfigs/`: `.snbt`; also exactly `config/ftbxmodcompat.snbt` and `config/ftbqoptimizer.snbt`.
- JSON: exactly `config/dangomusic/config.json`, `config/dangomusic/update_check.json`, `config/embeddium-fingerprint.json`, `config/embeddium-options.json`, and `config/imblocker.json`.
- JSON5: exactly `config/artifacts/client.json5`, `config/artifacts/common.json5`, `config/bettercombat/client.json5`, `config/bettercombat/server.json5`, `config/bc_particle/config.json5`, `config/bosses_of_mass_destruction.json5`, `config/chat_heads.json5`, `config/graveyard/the-graveyard-3.0-config.json5`, `config/hundredyearswar/hyw_client.json5`, `config/hundredyearswar/hyw_main.json5`, `config/hundredyearswar/hyw_global_modifier.json5`, `config/jea.json5`, and `config/yacl.json5`.
- Text settings: exactly `config/badoptimizations.txt`, `config/dangomusic/netease_cookie.txt`, `config/fancymenu/options.txt`, `config/fancymenu/custom_gui_screens.txt`, `config/fancymenu/customizablemenus.txt`, `config/fancymenu/legacy_checklist.txt`, and `config/aether/aether_customizations.txt`.
- TACZ generated state: exactly `tacz/.export-state.json` and `tacz/tacz-pre.toml`. TACZ gun assets, animations and data remain strict.

This policy covers all 117 rewritten paths in the night1 played-instance audit: 115 under `config/` and the two TACZ state files. It deliberately keeps `kubejs/assets/`, `kubejs/data/`, all scripts, authored JSON under `kubejs/config/`, FTB quest definitions, Hundred Years War target lists/client data, README files, FancyMenu layouts/slideshows, and `config/.entrance-render-defaults` strict. File extensions alone never exempt a file outside the named roots or exact paths. Player-owned paths below retain their existing priority, except that managed JARs are always strict. Symbolic links and non-file settings destinations remain invalid.

An untouched setting retains its current bytes or absence. If any hop adds or changes a setting, the final author version wins, including when a later hop returns to the starting pack bytes. A removed setting is deleted. An existing setting first added to the managed inventory may also be replaced. Replaced and removed settings retain their previous local bytes after success at `<instance>/.sao-config-backups/update-*/<original relative path>`. These launcher-owned transaction backups are outside the managed inventory. No backup is needed when the local file was absent or already equals the final author content. Staging retains independent rollback copies until the receipt commits; backup creation failures roll back installed mutations too.

Each quick archive contains `sao-update.json` with `from`, `to`, complete `before`/`after` manifests, complete SHA-256 `base`/`expected` maps, and exact `added`, `changed`, `removed` path arrays. Physical payloads exist only for added or changed paths under `overrides/`. Unchanged player seeds are omitted. Every staged hop is checked before any installed file changes. Removals are constrained to the previous managed inventory; unknown player files cannot be removed.

Options, saves, screenshots, shader packs, keybinds, server lists and other designated player paths are preserved. Actual player files and game-written settings are kept independently from their pristine pack seeds. A local file that still matches its original seed may supply canonical bytes, including external shader ZIPs absent from the retained archive. Rewritten/missing player and setting seeds are reconstructed from the retained full source; changed seeds come from the delta. Missing required pristine source data requires a full pack. Every staged hop still verifies its complete canonical SHA-1/SHA-256 inventory, so local settings never become the hash baseline for the next hop. The composed final archive contains the complete canonical inventory and all pristine payloads, including formerly external dependencies, and is cached before the single install commit. Repair after restart therefore needs neither old Downloads files nor old quick archives. Explicit repair restores managed settings and other managed damage from this source while existing player files remain unchanged. If the needed cached source is missing, the engine asks for the complete pack using the exact drag instruction.

A failed hop makes no instance changes. Commit failures restore prior replaced/removed files and leave the old receipt. Backups are retained when rollback itself fails. A receipt committed just before an `active.json` write failure is recoverable through the deterministic cached manifest identity.

## UI service API

Observable values are immutable `SaoData` records, published through the existing UI-thread mechanism. Filesystem work runs on the engine worker; news/version fetches have their separate background worker.

| `SaoLauncherService` API | Contract |
| --- | --- |
| `releaseVerification()` | Stable read-only `ReleaseVerification(versionSequence, newsSequence)` property; nullable sequence means no accepted signed document of that kind. Successful cache load/fetch updates it; rejected refreshes preserve it. This state is informational and must not be used by screens to bypass service verification. |
| `updateStatus()` | `UpdateStatus(metadata, packUpdateAvailable, launcherBlocked, launcherUpdateAvailable, message)`; nullable metadata until a valid policy exists. |
| `downloadPrompt()` | `DownloadPrompt(watching, candidate, message, required, present, missing, fullPackRequired)`; version lists are ordered destination labels. `candidate` is nullable and still needs explicit acceptance. |
| `acceptDroppedFiles(List<Path>)` | One asynchronous `CompletableFuture<Void>` entry for complete packs, quick archives and launcher artifacts. Privately snapshots files, accumulates good quick hops, installs a complete chain, or stages a verified JAR. Completion and failure also update properties. A drop during initial filesystem discovery waits for that discovery. |
| `applyDownloadedPack(Path)` / `applyUpdateZip(Path)` | Compatibility wrappers around `acceptDroppedFiles(List.of(path))`; neither bypasses verification. |
| `openPackDownload()` / `openLauncherDownload()` | Both revalidate and open the same `metadata.quark` with the existing system browser opener. |
| `watchPackDownload()` | Poll platform Downloads folders immediately and every three seconds; finds full-pack or quick filenames by name/size as suggestions only. |
| `cancelPackDownload()` | Stop polling, clear candidate and discard pending private quick snapshots. Does not interrupt an accepted installer commit. |
| `launcherUpdate()` | `LauncherUpdate(version, restartRequired, message)` with the staged replacement or persisted success/rollback result. |
| `refreshUpdates()` | Background news refresh and policy attempt (30 s limit); startup uses the shared 180 s cadence. |
| `dismissLauncherUpdate()` | Dismiss optional `latest` for this session; a minimum-version gate remains mandatory. |
| `lastError()` | Localized rejection/failure text, including a wrong member in an otherwise accepted batch. |

Optional JAR updates show exactly `请把 [NerveGear启动器 0.1.1] 的更新包拖进来` for that example version. Below the minimum, the message explicitly asks for the complete launcher, and all Link Start routes remain blocked. Screens should show `launcherUpdate().restartRequired()` and tell the player to close and reopen their normal native launcher; the backend does not force a restart or replace an application bundle.

Downloads resolution uses Windows `SHGetKnownFolderPath(FOLDERID_Downloads)`, Linux `user-dirs.dirs` with safe HOME substitution, and `~/Downloads` on macOS. Temporary browser suffixes, symlinks and wrong sizes are ignored. Manual selection remains available. A changed metadata identity invalidates the previous prompt and its pending snapshots. Explicit test engines allow inert download-folder and link-opener seams.

## Team build commands

All tools below dispatch before launcher directory setup, logs, AWT dialogs and JavaFX patching. They are local filesystem tools and force headless mode internally.

```sh
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true java -Djava.awt.headless=true \
  -jar NerveGear-Launcher.jar --pack-hash '/path/to/full.zip'

JAVA_TOOL_OPTIONS=-Djava.awt.headless=true java -Djava.awt.headless=true \
  -jar NerveGear-Launcher.jar --make-update '/path/to/old.zip' '/path/to/new.zip' \
  '/path/to/SAO-0.8-to-0.8.1.zip' --baseline '/path/to/baseline.json'
```

签名和验证工具不联网，也不初始化启动器目录或图形界面：

```sh
export JAVA_TOOL_OPTIONS=-Djava.awt.headless=true
java -Djava.awt.headless=true -jar NerveGear-Launcher.jar \
  --sign-release /path/to/version.json --key /secure/path/ed25519-private.pem
java -Djava.awt.headless=true -jar NerveGear-Launcher.jar \
  --sign-release /path/to/news.json --key /secure/path/ed25519-private.pem
java -Djava.awt.headless=true -jar NerveGear-Launcher.jar --verify-release /path/to/version.json
java -Djava.awt.headless=true -jar NerveGear-Launcher.jar --verify-release /path/to/news.json
```

`--sign-release` writes the adjacent `<file>.sig`, refuses to overwrite an existing signature unless `--force` is provided, and verifies the result against the embedded trust keys. A private key whose public key is absent from that list is refused. `--verify-release` prints `OK` / `FAIL` and the sequence when available; failure has a nonzero exit status. Both validate the document schema. CLI verification authenticates the local pair; it does not mutate a player’s anti-rollback history.

`--pack-hash` reads only bounded `sao-manifest.json` or `modrinth.index.json` version metadata and streams the whole ZIP hash. It prints a pasteable `pack` block. `--pack-version 0.8` may explicitly label an otherwise unlabelled archive without modifying it; the installed manifest must still match the release policy.

`--make-update` supports both formats and prints one pasteable `updates` entry. It verifies all source-file SHA-1/size values while deriving complete SHA-256 maps, then reopens and verifies generated payloads before moving output into place. Existing output paths are refused. External dependency bytes must be supplied by the content-addressed local baseline receipt; the builder never downloads them. `--baseline` is unnecessary for fully embedded fixtures. Source archives remain read-only and their filesystem identity is checked for mutations during the run.

Optional `--from-version` and `--to-version` overrides relabel generated manifests only. These support explicit local validation when two source archives have identical labels; they do not prove any historical release. Never publish a synthetic test edge. A successful production edge must correspond to the real installed receipt version.

## Per-user JAR replacement and native bootstrap

Every packaged shell uses its bundled runtime with:

```text
java [platform JVM/module options] -cp BUNDLED.jar org.jackhuang.hmcl.sao.SaoLauncherBootstrap [forwarded arguments]
```

The bootstrap loads from the immutable bundled JAR. It resolves the same per-user home as `Metadata.HMCL_USER_HOME`, including `-Dsao.home` / `SAO_OATH_USER_HOME`, and stores updates only in its `self-update/` directory. It never writes into `.app`, Program Files, the launcher executable, a mounted DMG/AppImage or a read-only application directory. Ordinary storage and all existing data-directory literals remain unchanged.

Accepted JARs must have the expected `Main-Class`, exact `hmcl.version` in bounded `assets/hmcl.properties`, matching `Implementation-Version` when present, and the required Main/Launcher/bootstrap/service entries. Embedded pack payloads, duplicate members and oversized JARs are rejected. A wrapped JAR has a 256 MiB expansion cap. Before each shell selection the entire retained JAR size/hash and version/entry metadata are checked again.

Payloads are content-addressed as `<sha256>.jar`. `state.json` atomically records active, previous and staged identities; no in-use JAR is overwritten, including on Windows. A staged replacement becomes active only for a new startup attempt. Its unpredictable UUID token is passed as `-Dsao.selfUpdateAttempt=...` with `-Dsao.selfUpdateRoot=...`. The bootstrap records the child PID and a 75-second attempt deadline. Concurrent fresh attempts are refused; abandoned or expired attempts restore the previous verified JAR, or the bundle when none exists.

`SaoSelfUpdate.markHealthy()` is called only after the real initialized primary stage is shown. It atomically writes the matching token marker; earlier or wrong markers cannot acknowledge another attempt. The shell waits up to 300 seconds for this marker or child exit; the marker write retries a briefly held state lock. Failure/timeout terminates the uninitialized replacement, restores the verified previous selection and starts that selection. A later launch also resolves an abandoned attempt, including a shell crash after the marker. Success and rollback messages persist through `launcherUpdate()`.

The child uses the same `java.home` runtime. JVM module flags, headless settings, `sao.*` storage options and environment overrides are forwarded; macOS also gets `-Xdock:name=NerveGear启动器`. Windows prefers `javaw.exe` for graphical children and `java.exe` for headless checks. `--self-update-status` prints persisted state through the bootstrap without starting any child, JavaFX or browser. Hash/builder tools do not claim UI health.

## 每次发布的操作

1. 在同一个夸克分享中保留“启动器”“模组”“快速更新”三个文件夹，所有按钮都使用这一个分享链接。
2. 制作内部版本号正确的最新完整模组包，用 `--pack-hash` 取得文件名、字节数和 SHA-256，填入 `pack`。
3. 用上一个完整包和新完整包运行 `--make-update`；外置依赖提供已校验的本地 baseline。将输出的一跳条目放入 `updates`，保留需要支持的连续历史跳，每条链必须通向最新完整包。
4. 将最新完整包放入“模组”，每跳快速更新 ZIP 放入“快速更新”。不要把模组嵌入正常启动器；`--embed-pack` 仅供明确的离线测试构建。
5. 普通启动器 Java 代码升级可发布经过校验的薄 JAR 或仅含一个 JAR 的 ZIP，填入 `launcher.file/size/sha256`。更新 `latest`，按实际兼容性设置 `min`。运行时或原生外壳变化时设置 `nativeRequired: true`，发布完整平台启动器。
6. 将薄启动器分别放入“启动器”文件夹；发布前在隔离目录检查无包首次导入、乱序两跳更新、损坏回滚、重启后修复及玩家文件保留。JAR 更新检查健康标记成功和失败回退。
7. 编辑 `version.json` 与 `news.json`（均使用 schema 2），填入真实文件身份；每次改动都增加对应文件的 `sequence`，不要回退。两个文件各自计数，可以使用不同的序号。
8. 在保存最终 JSON 后，用上面的 `--sign-release <file> --key <private.pem>` 分别签名两个文件，生成 `version.json.sig` 与 `news.json.sig`。已有签名需要重签时显式加 `--force`。私钥始终留在本地安全目录，绝不能上传 Gitee 或写入源码、日志、公告。
9. 对两个文件分别运行 `--verify-release`，确认输出 `OK` 和预期序号。签名后不要再格式化或编辑 JSON；如需修改，增加序号并重新签名。
10. 把 JSON 和各自的 `.sig` 一起上传至同一个 Gitee raw 目录，共四个文件。上传过程中的不匹配会被客户端忽略，旧的有效缓存继续使用。不要发布示例零哈希、虚构历史版本或验证用合成更新包。
