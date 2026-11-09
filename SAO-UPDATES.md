# Gitee version policy and Quark downloads

The launcher reads announcements and small version documents from Gitee. Players download ZIP packages manually from Quark in their default browser. The engine never downloads a package from Gitee or Quark. This backend change supplies frontend APIs; it does not change any source under `org/jackhuang/hmcl/ui/`.

Production startup checks metadata on a separate background executor. Installed-pack startup never waits for Gitee. A missing, invalid or unavailable version document keeps the last-good policy; with no valid policy, it does not impose a launcher gate. A valid cached mandatory minimum still applies during an outage. Pack updates are advisory: an installed pack remains launchable until the player chooses an update. An installed version newer than the advertised pack is never downgraded.

Default endpoints:

- News: `https://gitee.com/xwzho/sao-oath-launcher/raw/master/news.json`
- Versions: `https://gitee.com/xwzho/sao-oath-launcher/raw/master/version.json`

Override with `-Dsao.newsUrl=https://...` and `-Dsao.versionUrl=https://...`. The defaults take precedence over old pack-manifest news URLs. Endpoints require HTTPS without credentials, fragments or explicit ports. Each logical fetch has one three-second deadline, including body consumption and its permitted redirect. Only one redirect from `https://gitee.com/` to `https://raw.giteeusercontent.com/` is accepted. Every other redirect, including a second redirect, fails. News is capped at 262144 decoded bytes; version JSON at 16384 bytes. Successful validated documents atomically replace `online-news.json` / `online-version.json` under the launcher data root. Errors never overwrite those caches.

## Exact version.json schema

All fields below are required. Unknown fields, duplicate keys, comments, trailing input, nulls and primitive-type coercions are rejected. This is an illustrative document: replace the filename, size and digest with the hash tool's actual output before publishing.

```json
{
  "schema": 1,
  "pack": {
    "version": "0.8",
    "quark": "https://pan.quark.cn/s/1a81ea41915c",
    "file": "SAO OATH0.8.zip",
    "size": 123456,
    "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
  },
  "launcher": {
    "latest": "0.1.0",
    "min": "0.1.0",
    "quark": "https://pan.quark.cn/s/1a81ea41915c"
  }
}
```

- `schema` is the JSON integer `1`.
- Remote versions are strings with one to four numeric components, each one to nine digits, with no leading zeros except `0`. Examples: `0.8`, `0.10`, `0.1.0`. Numeric comparison makes `0.10 > 0.8` and `0.1 == 0.1.0`. `launcher.latest >= launcher.min`. Existing local legacy version labels use the launcher's established version comparator as a fallback.
- `file` is the exact portable basename ending in lowercase `.zip`, at most 240 characters. Paths, control characters, Windows reserved characters/device names and leading dots are rejected.
- `size` is a positive JSON integer fitting a signed 64-bit value. Strings, fractions and exponents are rejected.
- `sha256` is exactly 64 lowercase hexadecimal characters, hashing the complete compressed ZIP.
- Both Quark links require the exact `https://pan.quark.cn/` origin, without credentials, fragments or explicit ports. The engine revalidates before calling the existing system link opener. Tests replace that opener with an inert recorder.

## UI service contract

All values are immutable records in `SaoData`; observable properties are published through the engine's existing UI-thread mechanism. Filesystem and metadata operations run in the background. The explicit isolated/test engine constructors never request public metadata, including through `refreshOnline()`.

| SaoLauncherService API | UI use |
| --- | --- |
| `updateStatus()` | Observe `UpdateStatus(metadata, packUpdateAvailable, launcherBlocked, launcherUpdateAvailable, message)`. `metadata` is nullable until a valid policy exists. |
| `refreshUpdates()` | Refresh only announcements and version policy; no account refresh or Minecraft server ping. Startup calls it automatically in production. |
| `dismissLauncherUpdate()` | Hide the optional note for this `launcher.latest` during this session. Cannot dismiss a mandatory gate. |
| `openPackDownload()` / `openLauncherDownload()` | Open the corresponding validated Quark share in the default browser. No package bytes are fetched. |
| `watchPackDownload()` | Call when the pack update prompt opens. Poll known Downloads folders immediately and every three seconds. |
| `downloadPrompt()` | Observe `DownloadPrompt(watching, candidate, message)`. Nullable `candidate` means still waiting. A found file is not installed automatically. |
| `cancelPackDownload()` | Call on prompt close/cancel. Stops polling and clears the candidate. A new metadata identity also cancels the old prompt. |
| `applyDownloadedPack(Path)` | Use for a candidate or file-picker selection. Requires valid metadata, makes a private snapshot, verifies size/SHA-256 and manifest version, then installs through the existing preserving transaction. |
| `applyUpdateZip(Path)` | Existing drop API now uses exactly the same mandatory metadata verification path. |

`SaoData.VersionMetadata` contains `PackRelease(version, quark, file, size, sha256)` and `LauncherRelease(latest, min, quark)`; `schema` is always 1. `UpdateStatus` compares the installed receipt and the launcher build version independently.

Below `launcher.min`, all Link Start entry points are blocked with the Chinese compatibility message in `lastError()` and `updateStatus().message()`. Check `launcherBlocked` to show the launcher Quark button. The engine checks again at queued-operation and final launch boundaries. Between `min` and `latest`, the optional note is dismissible and does not block play. At or above `latest`, no launcher note is needed.

For the pack prompt, show `metadata.pack`'s filename and byte size, offer the pack Quark button, keep the prompt open while polling, enable confirmation when a candidate appears, and offer a file picker regardless. The finder checks exact name and size only. The selected manual filename may differ; exact bytes and manifest version must still match. The verification failure is `文件不完整或不是这个版本，请重新下载`. Verification and copying finish before runtime preparation or installer writes. Installation keeps the existing options, saves, screenshots and player-owned files through `SaoPackInstaller`.

Downloads resolution uses Windows `SHGetKnownFolderPath(FOLDERID_Downloads)` for localized/redirected folders, Linux `XDG_CONFIG_HOME/user-dirs.dirs` (or `~/.config/user-dirs.dirs`) with safe HOME substitution, and `~/Downloads` on macOS. Portable fallbacks remain available; the UI always permits manual selection. Browser temporary suffixes, wrong sizes and symlinks are ignored. Closing the engine cancels polling. Cancellation closes the waiting prompt; it does not interrupt an already confirmed installer transaction.

## Team hash command

```sh
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true java -Djava.awt.headless=true \
  -jar SAO-Oath-Launcher.jar --pack-hash '/path/to/SAO OATH0.8.zip'
```

This branch runs before launcher directory setup, log startup, AWT dialogs and JavaFX dependency patching. It forces headless mode internally too. It reads only the supplied ZIP's bounded version metadata and streams SHA-256, then prints `{ "pack": { ... } }` with the exact filename, compressed size and digest. It accepts `sao-manifest.json` or `modrinth.index.json`; the latter uses the same OATH-name version preference as the pack importer. For an unlabelled ZIP or a nonnumeric internal label, pass `--pack-version 0.8` after the path. That optional version is validated but does not alter the ZIP; the release's actual installer manifest version must still match the published version.

The hash command refuses a file whose size, modification time or filesystem identity changes while hashing. Wait for downloads/uploads to finish before preparing metadata. Paste the printed `pack` object into version.json and update the independent launcher policy deliberately. No live Quark access is required for this tool.
