# Gitee policy, Quark packs and launcher updates

Gitee supplies only bounded announcement/version documents. Players download the launcher, full pack or quick updates manually from **one Quark share**, whose folders are `启动器`, `模组` and `快速更新`. Every download button uses the top-level `quark` link. The engine never downloads these artifacts from Gitee or Quark. Production startup fetches metadata in the background; installed-pack startup never waits for Gitee. Explicit isolated engines and tests never request public metadata.

Missing, invalid or unavailable metadata retains the last valid policy. With no valid policy, no launcher minimum is imposed. A cached mandatory minimum still applies during an outage. Pack updates remain advisory and never downgrade a newer installed release. All Link Start entry points recheck the launcher minimum before account tickets, runtime preparation and final game launch.

Default endpoints:

- `https://gitee.com/xwzho/sao-oath-launcher/raw/master/news.json`
- `https://gitee.com/xwzho/sao-oath-launcher/raw/master/version.json`

Overrides are `-Dsao.newsUrl=https://...` and `-Dsao.versionUrl=https://...`. Each logical news/version fetch has one **10-second deadline**, including response-body consumption and its one permitted redirect from `https://gitee.com/` to `https://raw.giteeusercontent.com/`. Other redirects, extra hops, credentials, fragments and explicit ports are rejected. News is capped at 262144 decoded bytes; version JSON at 16384 bytes. Valid responses atomically replace `online-news.json` or `online-version.json` under the launcher data root; failures never overwrite last-good caches.

## version.json schema 2

Nothing has been published under schema 1; it is deliberately rejected. All mandatory fields are shown below. Filenames, sizes and hashes in this example are illustrative and **must be replaced** by verified release identities.

```json
{
  "schema": 2,
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

## Pack drops and transactions

A missing pack shows exactly `请把在夸克里下载的模组文件拖进来`. The UI also has `metadata.pack.version` for displaying which complete release to download. A valid full pack requires matching compressed size, SHA-256 and internal manifest version. Failure reports exactly `文件不完整或不是这个版本，请重新下载`.

An installed pack with a chain shows every destination version, in order:

- `请把 [0.8.1] 的快速更新包拖进来`
- `请把 [0.8.1] 和 [0.8.2] 的快速更新包拖进来`
- `请把 [0.8.1]、[0.8.2] 和 [0.8.3] 的快速更新包拖进来`

Drops may arrive together or separately, in any order and under any filename. Each good quick archive is copied privately and checked against the Gitee compressed identity and its own `from`/`to` manifest. Good inputs remain accumulated when another input is wrong. A valid full pack always supersedes pending quick updates, including when only some quick hops have arrived. Completion applies all hops automatically in version order; simply finding a file in Downloads never installs it without explicit acceptance.

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
| `updateStatus()` | `UpdateStatus(metadata, packUpdateAvailable, launcherBlocked, launcherUpdateAvailable, message)`; nullable metadata until a valid policy exists. |
| `downloadPrompt()` | `DownloadPrompt(watching, candidate, message, required, present, missing, fullPackRequired)`; version lists are ordered destination labels. `candidate` is nullable and still needs explicit acceptance. |
| `acceptDroppedFiles(List<Path>)` | One asynchronous `CompletableFuture<Void>` entry for complete packs, quick archives and launcher artifacts. Privately snapshots files, accumulates good quick hops, installs a complete chain, or stages a verified JAR. Completion and failure also update properties. A drop during initial filesystem discovery waits for that discovery. |
| `applyDownloadedPack(Path)` / `applyUpdateZip(Path)` | Compatibility wrappers around `acceptDroppedFiles(List.of(path))`; neither bypasses verification. |
| `openPackDownload()` / `openLauncherDownload()` | Both revalidate and open the same `metadata.quark` with the existing system browser opener. |
| `watchPackDownload()` | Poll platform Downloads folders immediately and every three seconds; finds full-pack or quick filenames by name/size as suggestions only. |
| `cancelPackDownload()` | Stop polling, clear candidate and discard pending private quick snapshots. Does not interrupt an accepted installer commit. |
| `launcherUpdate()` | `LauncherUpdate(version, restartRequired, message)` with the staged replacement or persisted success/rollback result. |
| `refreshUpdates()` | Background news/version fetch only; startup calls it automatically in production. |
| `dismissLauncherUpdate()` | Dismiss optional `latest` for this session; a minimum-version gate remains mandatory. |
| `lastError()` | Localized rejection/failure text, including a wrong member in an otherwise accepted batch. |

Optional JAR updates show exactly `请把 [NerveGear启动器 0.1.1] 的更新包拖进来` for that example version. Below the minimum, the message explicitly asks for the complete launcher, and all Link Start routes remain blocked. Screens should show `launcherUpdate().restartRequired()` and tell the player to close and reopen their normal native launcher; the backend does not force a restart or replace an application bundle.

Downloads resolution uses Windows `SHGetKnownFolderPath(FOLDERID_Downloads)`, Linux `user-dirs.dirs` with safe HOME substitution, and `~/Downloads` on macOS. Temporary browser suffixes, symlinks and wrong sizes are ignored. Manual selection remains available. A changed metadata identity invalidates the previous prompt and its pending snapshots. Explicit test engines allow inert download-folder and link-opener seams.

## Team build commands

Both commands dispatch before launcher directory setup, logs, AWT dialogs and JavaFX patching. They are local filesystem tools and force headless mode internally.

```sh
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true java -Djava.awt.headless=true \
  -jar NerveGear-Launcher.jar --pack-hash '/path/to/full.zip'

JAVA_TOOL_OPTIONS=-Djava.awt.headless=true java -Djava.awt.headless=true \
  -jar NerveGear-Launcher.jar --make-update '/path/to/old.zip' '/path/to/new.zip' \
  '/path/to/SAO-0.8-to-0.8.1.zip' --baseline '/path/to/baseline.json'
```

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

`SaoSelfUpdate.markHealthy()` is called only after the real initialized primary stage is shown. It atomically writes the matching token marker; earlier or wrong markers cannot acknowledge another attempt. The shell waits up to 60 seconds for this marker or child exit. Failure/timeout terminates the uninitialized replacement, restores the verified previous selection and starts that selection. A later launch also resolves an abandoned attempt, including a shell crash after the marker. Success and rollback messages persist through `launcherUpdate()`.

The child uses the same `java.home` runtime. JVM module flags, headless settings, `sao.*` storage options and environment overrides are forwarded; macOS also gets `-Xdock:name=NerveGear启动器`. Windows prefers `javaw.exe` for graphical children and `java.exe` for headless checks. `--self-update-status` prints persisted state through the bootstrap without starting any child, JavaFX or browser. Hash/builder tools do not claim UI health.

## 每次发布的操作

1. 在同一个夸克分享中保留“启动器”“模组”“快速更新”三个文件夹，所有按钮都使用这一个分享链接。
2. 制作内部版本号正确的最新完整模组包，用 `--pack-hash` 取得文件名、字节数和 SHA-256，填入 `pack`。
3. 用上一个完整包和新完整包运行 `--make-update`；外置依赖提供已校验的本地 baseline。将输出的一跳条目放入 `updates`，保留需要支持的连续历史跳，每条链必须通向最新完整包。
4. 将最新完整包放入“模组”，每跳快速更新 ZIP 放入“快速更新”。不要把模组嵌入正常启动器；`--embed-pack` 仅供明确的离线测试构建。
5. 普通启动器 Java 代码升级可发布经过校验的薄 JAR 或仅含一个 JAR 的 ZIP，填入 `launcher.file/size/sha256`。更新 `latest`，按实际兼容性设置 `min`。运行时或原生外壳变化时设置 `nativeRequired: true`，发布完整平台启动器。
6. 将薄启动器分别放入“启动器”文件夹；发布前在隔离目录检查无包首次导入、乱序两跳更新、损坏回滚、重启后修复及玩家文件保留。JAR 更新检查健康标记成功和失败回退。
7. 校验 schema 2 和所有真实文件身份后再发布 Gitee `version.json` 与公告。不要发布示例零哈希、虚构历史版本或验证用合成更新包。
