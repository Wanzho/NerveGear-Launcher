# SAO Oath Launcher backend

The frontend calls `SaoLauncherService`; the backend installs one `SaoPackEngine` before controllers are initialized. The original seam remains intact. `sao_auth` is a password handoff associated with an offline or Microsoft account, not a third account type.

The launcher identity is **刀剑神域·誓约 启动器 / SAO Oath Launcher**, version **0.1.0**. Pack releases have independent versions. Existing HMCL copyright and license notices remain in the source and About page.

## Storage isolation

Internal `HMCL_*` constant names remain for upstream source compatibility, but they point to this fork's storage:

| Purpose | Default |
| --- | --- |
| Portable launcher data | `<working directory>/.sao-oath-launcher` |
| Windows user data | `%APPDATA%/.sao-oath-launcher` (home fallback) |
| macOS user data | `~/Library/Application Support/.sao-oath-launcher` |
| Linux/BSD user data | `$XDG_DATA_HOME/.sao-oath-launcher`, otherwise `~/.local/share/.sao-oath-launcher` |
| Common Minecraft cache | `<portable data>/minecraft` |
| Dependencies | `<portable data>/dependencies` |

Explicit deployment overrides are `sao.dir` / `SAO_OATH_LOCAL_HOME`, `sao.home` / `SAO_OATH_USER_HOME`, and `sao.dependencies.dir` / `SAO_OATH_DEPENDENCIES_DIR`. The equivalent `hmcl.*` storage properties and HMCL storage environment variables are ignored. Legacy `hmcl.json` and `.hmcl.json` discovery beside the jar or in the current directory is disabled. Startup also no longer imports adjacent `authlib-injectors.json`.

## Upstream services

The HMCL updater is an inert compatibility facade: neither automatic nor manual checks schedule networking, and `--apply-to` is rejected without writing a jar. Legacy Terracotta navigation remains inert. The dedicated SAO host/join actions lazily prepare HMCL's verified Terracotta bundle, start one owned transport and request a room; leave/shutdown cancels its task graph and terminates its process tree. Reading the room property or constructing the engine never starts that work. The upstream development/nightly announcement branch is disabled by the fork's stable channel. The Countly-named crash-report class in this revision formats a local report only; there is no Countly upload transport in this source.

Metadata publishing, update, documentation, community, EULA, contact and changelog URLs are empty. Unsupported-Java boot errors no longer offer an HMCL download link. Microsoft authentication implementation and the `MICROSOFT_AUTH_ID` build-time environment input are preserved.

## Frontend integration boundary

This backend branch intentionally retains the upstream UI source files. The SAO frontend must replace legacy RootPage navigation, keep the HMCL copyright notice in its About content, and omit legacy About/Feedback/Terracotta routes. In particular, `AboutPage`, `FeedbackPage`, `GameCrashWindow`, and the `hmcl://settings/feedback` handler contain literal upstream links that do not come from Metadata. They remain clickable if an upstream page is exposed. The backend does not claim those hard-coded UI links have been removed.

The backend does not open or validate any JavaFX window or start Minecraft during headless verification. Microsoft sign-in, stdin consumption by the separate client mod, and game compatibility require separate runtime validation.

## Window service

All extended `SaoLauncherService` methods are implemented. Observable lists have stable, unmodifiable views; the engine replaces immutable records on JavaFX's application thread. Explicit `java.awt.headless=true` tools publish synchronously without a toolkit. Local inventory refreshes are asynchronous, coalesced and suppress identical list events. Screens should keep empty states reactive: an initially empty list may receive a snapshot after its window opens.

News uses the optional manifest `newsUrl` and a last-good public cache; see [sao-news.md](sao-news.md). The server address remains empty unless configured. The status request uses protocol 763 with a three-second total deadline and no overlapping refreshes.

Worlds use HMCL's NBT-backed `World` parser in the fixed instance. Production backups use `WorldBackupTask`; explicit headless tools use locked `World.export` and remove `session.lock` from the archive, avoiding that task's JavaFX-Control formatter dependency. Restore stages and validates the archive, backs up the current world, then swaps directories. A failed commit rolls back; a failed rollback retains the displaced world in `.sao-restore-*` recovery staging. Saves, skins and pack settings refuse mutations through `lastError` while the tracked game runs. Export paths cannot recurse into their source world.

Shader detection reads a mod's own metadata declaration, excluding dependency-only references. OATH 0.8's renamed `mekalus-mc1.20.1-1.8.0.1.jar` declares `oculus`; its active selection belongs in `config/oculus.properties`. A leftover properties file alone does not establish shader support. Without an installed Oculus/Iris declaration, listing/importing still works and selecting a shader reports an error. The launcher never adds a shader mod. Pointer files ending in `.zip.txt` are not executable packs. Resource changes preserve other options, selected-pack order and distribution-required IDs from the active archive's default `options.txt`; missing requirements are restored before launch. Symlinked config/options targets are rejected.

Screenshots are scanned newest first on request. Game stdout/stderr feeds a 5,000-line ring. Authentication/handoff lines are filtered before display or export. Support zips contain captured output and sanitized text files from `logs`/`crash-reports` only, including inflated `.log.gz` files with distinct names. Auth/handoff/token-named files are excluded. HMCL's `CrashReportAnalyzer` supplies the last run's reason and matched detail; the launcher persists only its run timestamp and abnormal-exit flag for analysis after restart.

Java listings and explicit choices accept compatible Java 17 only, using JavaManager when initialized and the launcher's managed repository. Explicit download uses HMCL's Java 17 task. Download-source selection configures both metadata and payload providers; parallelism reaches HMCL's real download executor. The downloads window observes real fetch tasks, including room-bundle transfers, and retains 100 recent rows. Per-row speed remains `0` (unknown), not an estimated value.

Memory, automatic allocation, Java path, window/fullscreen, extra JVM arguments, source, parallelism and small screen preferences persist in `launcher-settings.json` under the launcher's data root. Automatic memory uses HMCL's allocation function at launch. The launch options consume the selected runtime, memory, window and tokenized JVM arguments. Invalid setting requests publish `lastError`; a screen should reread the accepted setting rather than displaying a rejected slider value. Native file/folder handlers run in the background with a bounded wait; they are not exercised by headless tests.

Terracotta delegates package verification and installation to HMCL's existing provider. Its bundled metadata currently specifies 0.4.2 with GitHub release packages and the existing regional mirrors. macOS may need the provider's administrator-approved package installation after an explicit host/join request. No room or package download occurs during backend verification. A joined room exposes the transport's actual URL in its message; the backend does not invent a Minecraft server-list entry.

## Reproducible distribution builds

`-PsaoPack=<zip>` embeds the source archive and complete external inventory. `-PsaoPackBaseline=<verified receipt or directory>` reuses files after SHA-1 and size checks. Add `-PsaoPackOffline=true` to reject any missing/invalid baseline payload before an external-pack network fallback. This does not disable Gradle dependency resolution. `-PsaoNewsUrl` defaults to empty when generating a manifest from a Modrinth archive. A Microsoft build receives `MICROSOFT_AUTH_ID` from the build environment; account registration and live sign-in require separate verification.
