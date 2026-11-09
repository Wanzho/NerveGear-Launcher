整合包远程依赖优先使用索引中已有的 Modrinth CDN 地址，然后依次尝试 mediafilez、edge 和其他原始地址。每个候选均按索引 SHA-1 和长度校验；最多六文件并发，低速文件单独回退。MCIM 仅保留 API 镜像用途，不再自动生成文件镜像候选。

## Candidate policy and evidence

`HMCLDownloadProvider.getPackDownloadCandidates(urls)` uses a stable, deduplicating sort, independent of locale and Minecraft source preference:

1. Indexed `cdn.modrinth.com` URLs.
2. Indexed `mediafilez.forgecdn.net` URLs.
3. Indexed `edge.forgecdn.net` URLs.
4. Other original URLs, preserving their original relative order.
5. An explicitly indexed `mod.mcimirror.top` URL, if present; none is synthesized.

Encoded paths are kept intact. The mcim1 proof observed 15/15 file requests redirecting from MCIM to original hosts; none was served by MCIM. The team's 9 November mainland/Europe probe reports the same for every file in the index. An extra MCIM request therefore costs latency and cannot improve these file transfers. MCIM API rules for CurseForge and Modrinth remain separate and unchanged. General file-provider rules also stop rewriting these CDN hosts to MCIM.

Requests and redirects send `NerveGear-Launcher/<Metadata.VERSION>`, without account or machine identifiers. Any explicitly indexed MCIM requests retain the previous process-wide two-starts/second pacing and Retry-After handling. Production does not introduce another third-party file mirror.

## Transfer and transaction guarantees

`SaoGameBridge.download` delegates to `SaoModDownloads`, a checksum-aware `FileDownloadTask` adapter. The installer stages missing files with a fixed maximum of six workers, drains every worker, and commits only after verifying the batch. Existing correct destination files are SHA-1/size checked and never downloaded again, including direct downloader calls.

Each candidate has up to three attempts with a 15-second connect timeout and 10-second read timeout. Ordinary transient errors use 1/2-second exponential backoff plus up to 250 ms jitter. Wrong SHA-1 and non-throttling 4xx responses immediately advance to the next candidate. A per-file watchdog checks every 50 ms: a completed 10-second payload window averaging below **100 KiB/s** disconnects that candidate and advances without same-host retries. This threshold also covers the team's roughly 0.06 MB/s CloudFront observation. A body read timeout likewise advances to another candidate. TCP disconnect completion is subject to the current read timeout; the watchdog also detects fully silent bodies without needing another write callback.

Ordinary interrupted/truncated transfers retain HMCL's in-memory Range/If-Range resume when the server advertises byte ranges, length and ETag/Last-Modified. The whole-file digest covers resumed bytes. Switching candidates discards partial contexts. Servers that cannot resume restart cleanly. Cross-process resume is deliberately absent: previous temporary files are never trusted as jars.

Network bytes are bounded by index size and stay in private temporary files. Full SHA-1 and length verification precede atomic publication into private transaction staging; the installer rechecks before commit. Failed replacements preserve existing destination files. Closing the downloader interrupts workers and disconnects HTTP; batch owners drain workers before deleting staging. URL/ETag and BMCLAPI cache shortcuts cannot bypass index verification.

## Progress and evidence API

The existing `SaoLauncherService.installProgress()` remains a `ReadOnlyObjectProperty<SaoInstallProgress>`, published on the JavaFX thread (headless fixtures use its synchronous fallback). No UI source changes are needed.

| Field | Meaning |
| --- | --- |
| `stage` / `label` | Actual phase and display label, e.g. `下载模组 37/178` |
| `completedFiles` / `totalFiles` | Verified files / files requested in this batch; repairs may need fewer than the original index |
| `bytesDone` / `totalBytes` | Verified bytes plus active partial bytes / index size sum; discarded partials can decrease done |
| `networkBytes` | Measured payload, including retries and corrupt discarded bytes; excludes protocol headers |
| `bytesPerSecond` | Measured throughput, coalesced to 200 ms; zero after the batch |
| `failures` | Immutable terminal file failures, ordered actual request URL/host/status and bounded reason |

Unknown Minecraft/loader/Java totals remain `totalBytes=-1`, counts/speed zero: use an indeterminate phase rather than invented percentages. `COMPLETE` means the pack receipt committed, not game launch. Failed counters remain until the next operation.

Per-file `SaoModDownloads.Transfer` adds an immutable `stalls` list: actual host, measured window seconds, received bytes and bytes/second. Its `attempts` retain every real request, redirect and retry. `finalHost=existing` means locally verified reuse; `bundled` means an internal resource. File speed is measured network payload divided by that file's elapsed time, including requests, retries and verification; parallel file times must not be summed.

## Live sample

`org.jackhuang.hmcl.sao.SaoMcimProof PACK_ZIP EMPTY_SCRATCH` runs the production path headlessly. Its 20-entry sample includes Blue Skies and Alex's Caves, thirteen other primary-CurseForge files and five primary-Modrinth files, bounded to 300 MB. Candidate order is still the production Modrinth-first order, even for entries whose original first URL was CurseForge.

The proof permits only `cdn.modrinth.com`, `mediafilez.forgecdn.net`, `edge.forgecdn.net` and `mod.mcimirror.top`, enforcing the allowlist before every redirected connection. It writes `evidence.json` containing expected/actual SHA-1, measured speed, attempts, stalls and original-archive SHA-256 before/after. It always deletes sample jars. Run with both `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true` and `-Djava.awt.headless=true`; a JavaFX-equipped runtime needs `--add-modules=javafx.controls` to load HMCL task classes, without initializing the toolkit.

This Mac is in Europe. These speeds validate routing and integrity from this connection and do not establish mainland performance.
