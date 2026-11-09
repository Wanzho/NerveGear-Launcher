整合包远程依赖固定先请求 MCIM，再尝试原始 CDN；每个候选文件均按索引 SHA-1 和长度校验。安装最多并发六个文件，所有远程文件先进入事务暂存区，全部成功后才替换游戏目录。MCIM 当前可能只重定向回原站，因此经过 MCIM 的请求不能直接记作镜像命中。

## URL mapping and current service state

The pack-specific `HMCLDownloadProvider.getPackDownloadCandidates(urls)` maps:

| Original host | Mirror prefix | Path handling |
| --- | --- | --- |
| `mediafilez.forgecdn.net` | `https://mod.mcimirror.top` | Preserve `/files/{id1}/{id2}/{filename}` |
| `edge.forgecdn.net` | `https://mod.mcimirror.top` | Preserve the same `/files/...` path |
| `cdn.modrinth.com` | `https://mod.mcimirror.top` | Preserve `/data/{project}/versions/{version}/{filename}` |

Original URLs remain immediately after their mirror candidate; duplicate mirror URLs shared by the CurseForge hosts are removed. Pack dependencies use this ordering regardless of locale and Minecraft source preference. Other Minecraft/runtime source preferences keep their existing behavior. API mirror rules remain separate.

The [archived integration guide](https://github.com/mcmod-info-mirror/mcim-api#使用) documents both CurseForge CDN hosts and Modrinth host replacement. The [live OpenAPI schema](https://mod.mcimirror.top/openapi.json), linked from [MCIM docs](https://mod.mcimirror.top/docs), exposes the `/files/...` and `/data/...` routes, currently documented as 302 redirects. The [current Rust API README](https://github.com/mcmod-info-mirror/mcim-rust-api) says file downloads are temporarily unavailable. Actual delivery host must be measured; adding the mapping alone cannot establish mainland acceleration.

Every pack HTTP request and redirect identifies itself as `NerveGear-Launcher/<Metadata.VERSION>`. At most two MCIM requests start per second across downloader instances, with no initial burst. MCIM 429/503 responses impose a shared, interruptible `Retry-After` cooldown (delta seconds or HTTP date; five seconds when absent/invalid). File candidates get up to three attempts, with 1/2-second exponential backoff plus up to 250 ms jitter. A SHA-1 mismatch immediately switches candidate instead of downloading corrupt mirror content repeatedly. Ordinary non-throttling 4xx responses immediately switch candidate.

The inspected public docs do not specify a numeric current file-download quota; the two-per-second cap is our conservative client policy, not a claimed MCIM limit. The archived guide mentions possible UA registration. Successful requests do not establish registration or future whitelist approval; no external registration or message is submitted by this code.

## Transfer and transaction guarantees

`SaoGameBridge.download` delegates to `SaoModDownloads`, which creates a checksum-aware `FileDownloadTask` with the provider's candidate snapshot. `SaoPackInstaller.Downloader.downloadAll` stages missing remote entries with six workers, waits for all terminal results, and reports every failed path and the actual hosts tried. The production engine supplies the richer bulk implementation; generic fixture downloaders share the same concurrency limit.

HMCL's in-memory transfer context resumes within an active candidate when the response provides byte ranges, length, and ETag/Last-Modified validators. A resume sends `Range` and `If-Range`; the complete file digest still covers every byte. Switching candidate discards that partial context. Restarting the launcher does not resume a previous process's temporary file. Unsupported range responses restart safely.

The transfer writes into HMCL's temporary file, verifies SHA-1 and index size, then moves to a private `.part` sibling. A final size/SHA-1 check precedes the sibling's atomic publication into the installer's private stage. Stream writes are bounded by index size. The installer verifies the batch again before its existing rollback-capable commit. An unsuccessful transfer preserves any existing destination jar and removes its temporary siblings. URL/ETag cache hits cannot bypass index verification. Shutdown interrupts active workers and disconnects their HTTP connections; the bulk owner drains them before removing transaction staging.

## Service API for the UI

Read or observe the stable production property `SaoLauncherService.installProgress()` (`ReadOnlyObjectProperty<SaoInstallProgress>`). Production property changes run on the JavaFX application thread. Headless fixtures use the existing synchronous fallback. No UI source changes are needed to consume it. The existing `stepLabel()` and `progress()` remain available; mod transfer fractions represent verified file count, not estimated bytes or whole-install completion.

| Snapshot field | Meaning |
| --- | --- |
| `stage` | Stable enum: `IDLE`, `CHECKING`, `PREPARING`, `DOWNLOAD_MODS`, `DOWNLOAD_MINECRAFT`, `INSTALL_NEOFORGE`, `DOWNLOAD_JAVA`, `CHECK_DEPENDENCIES`, `APPLY_PACK`, `COMPLETE`, `FAILED` |
| `label` | Display text such as `下载模组 37/178`, `下载 Minecraft 1.20.1`, `安装 NeoForge 47.1.106` |
| `completedFiles` / `totalFiles` | Verified successful remote files / files needed in this batch; a repair can need fewer than the complete index |
| `bytesDone` / `totalBytes` | Verified bytes plus current partial bytes / sum of needed index sizes; done can decrease when corrupt partial content is discarded |
| `networkBytes` | Measured transferred payload bytes, including discarded/retransferred data; excludes HTTP/TLS headers |
| `bytesPerSecond` | Measured payload throughput between publications, coalesced to 200 ms; zero at batch end |
| `failures` | Immutable terminal failure list. Each item exposes `path`, actual `attempts` (`url`, `host`, HTTP `status`, or 0 when no response), and bounded `reason` |

Minecraft/NeoForge stage names follow real HMCL task stages, not a timer. Stage transitions may repeat because the install graph can download the client after loader work. For those phases HMCL does not expose an aggregate byte denominator here: `totalBytes=-1`, file counts are 0, and speed is 0. Display an indeterminate bar instead of fabricating a percentage. `COMPLETE` means the pack receipt has committed, not that a game has launched. At a terminal error, the service retains the final counters and failed-file list until a new operation begins.

For `DOWNLOAD_MODS`, use `label` directly; render `bytesDone / totalBytes` and `bytesPerSecond` as bytes/s (or format MiB and MiB/s). For `FAILED`, list every failure and deduplicate its attempted hosts for a compact line; full ordered attempts preserve redirects and retries. A 302 at MCIM followed by a 200 from the original host is an original-CDN delivery, not a mirror-served file.

## Reproducible live sample

`org.jackhuang.hmcl.sao.SaoMcimProof PACK_ZIP EMPTY_SCRATCH` uses the same production downloader and candidates. Run it headlessly with `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true` and the launcher jar plus platform JavaFX base/graphics/controls jars on the classpath (or a JavaFX-equipped runtime with `--add-modules=javafx.controls`). It selects exactly ten primary-mediafilez files (the two largest plus eight small files) and five primary-Modrinth files, rejecting a sample above 250 MB. It never downloads the complete index.

The proof's redirect allowlist is limited to `mod.mcimirror.top`, `mediafilez.forgecdn.net`, `cdn.modrinth.com`, and `bmclapi2.bangbang93.com`; the index's `edge.forgecdn.net` fallback is blocked in this proof only. Production retains every index source. The output `evidence.json` records expected/actual SHA-1, size, actual HTTP host/status history, final host, per-file time/speed, and before/after source-archive SHA-256. Downloaded jars and their directories are removed in a `finally` block. Keep the JSON, not the sample jars. The proof does not establish mainland network performance or physical gameplay.
