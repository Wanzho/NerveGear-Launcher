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

The HMCL updater is an inert compatibility facade: neither automatic nor manual checks schedule networking, and `--apply-to` is rejected without writing a jar. Terracotta has an inert facade with no daemon, process, installer, scan or network requests. The upstream development/nightly announcement branch is disabled by the fork's stable channel. The Countly-named crash-report class in this revision formats a local report only; there is no Countly upload transport in this source.

Metadata publishing, update, documentation, community, EULA, contact and changelog URLs are empty. Unsupported-Java boot errors no longer offer an HMCL download link. Microsoft authentication implementation and the `MICROSOFT_AUTH_ID` build-time environment input are preserved.

## Frontend integration boundary

This backend branch intentionally retains the upstream UI source files. The SAO frontend must replace legacy RootPage navigation, keep the HMCL copyright notice in its About content, and omit legacy About/Feedback/Terracotta routes. In particular, `AboutPage`, `FeedbackPage`, `GameCrashWindow`, and the `hmcl://settings/feedback` handler contain literal upstream links that do not come from Metadata. They remain clickable if an upstream page is exposed. The backend does not claim those hard-coded UI links have been removed.

The backend does not open or validate any JavaFX window or start Minecraft during headless verification. Microsoft sign-in, stdin consumption by the separate client mod, and game compatibility require separate runtime validation.
