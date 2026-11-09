The common launcher backend now provides per-run launcher visibility, sanitized feedback ZIPs, cached community links, deferred graphics presets, first-open system checks, versioned notice acceptance, and social notification events. All code is outside the UI tree. `SaoExtrasSamples` supplies an offline decorator for the existing UI-owned fake.

## Service contract for the UI

The production implementation is `SaoPackEngine`. Types below are nested in `SaoData`. Futures run filesystem, hardware, or HTTP work on a daemon worker and complete on the JavaFX publication thread when a toolkit exists; headless tests use an immediate fallback. Lists/properties retain their identity. Public lists are unmodifiable; records are immutable. Async failures complete exceptionally, and shutdown cancels pending support requests.

| `SaoLauncherService` API | Contract |
| --- | --- |
| `launcherBehavior()` / `setLauncherBehavior(LauncherBehavior)` | Default `HIDE` (隐藏到后台). `KEEP` = 保持打开; `CLOSE` = 直接关闭. Persisted for the next launch. |
| `buildFeedbackZip()` → `CompletableFuture<FeedbackZip>` | One committed ZIP in the OS Downloads folder. `FeedbackZip.path()` is its actual path and `size()` is its compressed byte size. No file manager is opened by the backend. |
| `links()` → `ObservableList<CommunityLink>` | Stable, read-only, last-good links. Each record has `id`, `label`, `url`. Built-in 爱发电 and the existing sole Quark share are used if no valid cache exists. |
| `refreshLinks()` | Independent asynchronous refresh; no account request or server ping. Production refreshes once after startup too. Explicit fixture engines remain offline. |
| `graphicsPreset()` / `setGraphicsPreset(GraphicsPreset)` | `AUTO`, `FAST` (流畅), `BALANCED` (均衡), `QUALITY` (画质). Setter writes only launcher preferences, including while a game runs. Actual files change at the next launch boundary. |
| `suggestedGraphicsPreset()` → `CompletableFuture<GraphicsPreset>` | Concrete `FAST`/`BALANCED`/`QUALITY` suggestion based on HMCL's total RAM/GPU detection. No OpenGL context or benchmark. |
| `systemCheck()` → `CompletableFuture<List<SystemCheck>>` | Four Chinese rows: RAM, disk, Java/architecture, write permission. Each has `level()` = `OK`/`WARN`/`BLOCK`, and `text()`. |
| `userNoticeVersion()` | Current 用户须知/licences bundle version: `2026-11-09.1`. Bump when the UI's notice or licence presentation changes materially. |
| `userNoticeAccepted()` | True only when this exact version was explicitly accepted. |
| `acceptUserNotice(version)` | Call only after the player accepts the displayed current notice and licences. Wrong/stale versions throw `IllegalArgumentException`. Stores `preference.notice.accepted` in launcher preferences. |
| `setLauncherAttention(visible, focused)` | Feed actual window visibility and focus changes. Initial state is visible/focused. The hidden-game policy also sets attention automatically. |
| `notification()` → `ReadOnlyObjectProperty<Notification>` | Stable ephemeral event stream, initially null. Listen for each replacement; no replay/history. |

For feedback success, use the existing `FXUtils.showFileInExplorer(result.path())` in the UI's completion handler. `openFile()` opens a ZIP with its associated application, so it is not the reveal action. Render completion failures in the UI rather than treating a failed future as a saved file.

On first open, call `systemCheck()`, show `WARN` rows and disable installation for `BLOCK` rows. Show 用户须知 and the existing licences presentation when `userNoticeAccepted()` is false; record acceptance only after the explicit acceptance action. The backend owns the version flag, not the legal text or modal UI.

### Visibility and crash restoration

`LauncherBehavior.visibility()` maps `HIDE` to HMCL `HIDE_AND_REOPEN`, `KEEP` to `KEEP`, and `CLOSE` to `CLOSE`. The captured policy does not change mid-run. Hiding begins only after the OS process exists; JavaFX implicit exit is disabled while hidden and restored when the window returns. Both normal exits and crashes restore the launcher. Fast-exit and old-run callbacks are fenced so a delayed hide cannot hide an already-exited run.

The existing HMCL crash analyser and `lastCrash()` are reused. The crash result is published before the restored window is shown; an unreadable/oversized report falls back to captured output. The UI agent should listen to `lastCrash()` and present the existing crash card/log window after restoration. `CLOSE` invokes HMCL's ordinary shutdown only after the protected launch handoff succeeds. This backend run does not launch a game or visually verify a native window.

### Notification integration

Listen to `notification()` before reporting hidden/unfocused attention. `Notification` contains local monotonic `sequence`, `kind` (`MESSAGE`, `FRIEND_REQUEST`, `INVITE`), `chat` (empty for requests), bounded plain-text `title`, bounded `preview`, and millisecond `time`.

The UI owns OS permission requests, native notification display, and click routing. Call `setLauncherAttention(stage.isShowing(), stage.isFocused())` whenever either value changes, including after a restored window gains focus. Do not log notification bodies. An invite preview is display text, not an address; accepting still uses the existing authoritative `acceptInvite` service.

Only incoming live SSE/poll deliveries are eligible. Initial inbox snapshots, history/search, outgoing/pending rows, recalls, duplicate IDs/revisions, expired invites, muted chats, protocol `shouldNotify=false`, and foreground deliveries do not notify. Friend requests notify only on a newly pending incoming transition. API refreshes cannot consume the first live-inbox baseline. Signout/account replacement fences queued events. Events are not persisted; consumed foreground messages do not notify later when focus changes. The per-session deduplication window retains 20,000 delivered IDs.

## Feedback contents and privacy

`SaoFeedbackZip` uses an explicit allowlist: `logs/latest.log`, `logs/debug.log`, `options.txt`, captured process output, the three newest immediate `crash-*.txt` reports, up to three immediate `hs_err_pid*.log` files in each of the instance and launcher data roots, and up to four explicit current/recent paths supplied by HMCL's logger. HMCL XZ and Minecraft gzip logs are inflated before scrubbing. It does not walk the account folder, arbitrary config folders, preferences, tickets or chat storage. Symlinked instance sources are refused. Crash and launcher archive entry names are generic and do not reveal original personal filenames.

The generated system summary includes OS/version/architecture, CPU, GPU, total RAM, launcher version, embedded and installed pack versions, launcher Java, selected game Java and HMCL-compatible Java 17 inventories. `mods-sha1.txt` hashes each actual installed top-level mod jar, preserving exact generated digests while separately scrubbing filenames. Missing diagnostics are omitted; an uninstalled instance still produces a minimal valid ZIP.

Credential-bearing lines are omitted entirely, covering password/token/ticket/session/authorization/cookie/bearer/API-key forms, common login commands, JWTs, multiline values/objects and PEM private-key blocks. Ordinary JSON Unicode and URL-encoded diagnostics are decoded before privacy matching. Emails, IPv4/IPv6 and mapped/zone addresses are redacted. Home paths (including escaped Windows variants) and the OS username become `~`. Stateful process redaction preserves this protection across individual line callbacks. The legacy log export now uses the same scrubber. Source files are never edited. Each sanitized entry is capped at 16 MiB, with a 96 MiB source-text budget and truncation notes; replacement expansion is bounded after scrubbing too. Partial ZIPs are removed and completed ZIPs are committed atomically.

## Verified pack evidence and preset ownership

The source was opened read-only: the supplied `SAO OATH0.8 (1).zip`. Its `modrinth.index.json` declares Minecraft 1.20.1 and NeoForge 47.1.106. Merging the client index and overriding paths yields **12,112 managed files, 1,826,559,588 bytes**; archive size is **477,071,659 bytes**.

Renderer/performance declarations include Embeddium 0.3.31, Sodium Dynamic Lights 1.0.10, EntityCulling 1.10.5, FerriteCore 6.0.1, ImmediatelyFast 1.5.4, BadOptimizations 2.4.1, CullLessLeaves Reforged 1.0.5, Flerovium 1.2.16, immersive_optimization 0.1.0, Radium 0.12.4, Noisium 2.3.0, Lightspeed 1.1.2hotfix, FastEvent 1.1.1 and FastFurnace 8.0.2. Overrides include Chloride 1.7.5, AllTheLeaks 1.1.3, `sao-performance` 0.80.0 and structure_layout_optimizer 1.0.11. These are archive declarations, not runtime proof that every external mod loads.

The archive ships `oculus.properties` selecting MakeUp UltraFast 9.1c, and the index declares MakeUp and Complementary shader ZIPs. The index also declares `mekalus-mc1.20.1-1.8.0.1.jar` and `ocs-1.0.jar`; no separately named Oculus, Iris, Rubidium or Distant Horizons jar was found. Their external bytes were not fetched or inspected in this run. Thus the shipped Oculus config is supported, while the exact shader-loader runtime identity remains unconfirmed. No speculative Iris/Distant Horizons config is created.

Only the following keys are owned by presets. All other options, keybinds, resource-pack selections, config keys, performance safeguards, culling whitelists and mod files remain semantically unchanged. JSON formatting may be normalized; unknown JSON values remain unchanged. Line-based files preserve unknown lines and their existing LF/CRLF convention. Missing files are created with only owned keys/sections. All replacements are staged before the first write, each move is atomic where supported, and already-committed files are restored on a commit error; any rollback failure is preserved as a suppressed exception for diagnosis.

| File | Key | 流畅 `FAST` | 均衡 `BALANCED` | 画质 `QUALITY` |
| --- | --- | --- | --- | --- |
| `options.txt` | `renderDistance` | `6` | `10` | `16` |
| `options.txt` | `simulationDistance` | `4` | `6` | `10` |
| `options.txt` | `graphicsMode` | `0` | `1` | `1` |
| `options.txt` | `particles` | `2` | `1` | `0` |
| `options.txt` | `renderClouds` | `"false"` | `"fast"` | `"true"` |
| `options.txt` | `entityDistanceScaling` | `0.5` | `0.75` | `1.0` |
| `options.txt` | `mipmapLevels` | `0` | `2` | `4` |
| `options.txt` | `ao` | `false` | `true` | `true` |
| `config/embeddium-options.json` | `quality.weather_quality` | `FAST` | `FANCY` | `FANCY` |
| same | `quality.leaves_quality` | `FAST` | `FANCY` | `FANCY` |
| same | `quality.enable_vignette` | `false` | `true` | `true` |
| same | `performance.animate_only_visible_textures` | `true` | `true` | `false` |
| `config/oculus.properties` | `enableShaders` | `false` | `false` | `true` only if the declared shader ZIP exists; otherwise `false` |
| same | `shaderPack` | `MakeUp-UltraFast-9.1c.zip` | same | same |
| `config/sodiumdynamiclights-client.toml` | `[sodiumdynamiclights].mode` | `"OFF"` | `"FAST"` | `"REALTIME"` |

`AUTO` initially resolves to a concrete preset: unknown RAM/GPU, RAM below 8 GiB, or integrated Intel/UHD/Iris/Vega → FAST; at least 16 GiB with a detected discrete GPU → QUALITY; other detected hardware → BALANCED. This is a conservative heuristic, not a measured FPS promise. A discrete GPU takes precedence when the machine also lists an integrated GPU. The applied selection/version is persisted; later launches with the same selection leave in-game edits alone. Selecting another preset queues another application. No preset file write occurs while the backend's real-process flag is set.

## First-open thresholds and persistence

RAM below 8 GiB warns and suggests 流畅. Unknown RAM also warns. Disk below 6 GiB warns if the install reserve is otherwise satisfied; insufficient reserve blocks. The reserve is `2 × active managed payload bytes + archive bytes + 2 GiB`: one transactional replacement copy plus room for Java, game libraries and headroom. The OATH 0.8 fallback is **6,277,674,483 bytes** (about 5.85 GiB). A replacement ZIP uses its own inventory, including if larger than the current pack. Startup without a source uses the exact inspected 0.8 sizes above.

HMCL's Java 17 inventory already checks architecture compatibility. Missing automatic Java warns that startup can install it; an invalid explicit selection blocks and suggests 自动. The write check actually creates/removes a small temporary probe in the nearest existing instance ancestor. macOS's exact `/var → /private/var` and `/tmp → /private/tmp` system aliases are allowed; arbitrary instance symlinks are refused. Actual install, update and repair paths independently enforce disk/write gates.

Preferences remain in the existing atomic launcher settings store: `extras.launcherBehavior`, `extras.graphicsPreset`, `extras.graphicsApplied`, and `preference.notice.accepted`. No account credential is added to that file.

## Offline samples and handoff

Use `SaoExtrasSamples.decorate(new SaoFakeService())`, optionally wrapping `SaoSocialSamples.decorate(...)`. The decorator provides stable links, reversible preferences, sample check rows, a clearly marked `DEMO-NOT-A-REAL-FILE` feedback result and all three event kinds. It performs no disk/network/native-window work. Do not reveal the synthetic feedback path during screen tests.

The run folder contains `SaoFakeService-samples.patch` if the UI worker wants the delegating methods directly inside the existing fake, plus `FAKE-SERVICE-INTEGRATION.md`. The patch is review material and is not applied by this backend worker. No source under `org/jackhuang/hmcl/ui/` is changed.

The run-folder `links.json` is ready for team review/commit. Its QQ/B站/官网 entries use clearly marked `example.invalid/TODO-*` placeholders. Replace them before publishing to Gitee. Production built-in defaults never include those placeholders.
