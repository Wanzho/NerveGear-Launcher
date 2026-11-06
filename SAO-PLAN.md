# 刀剑神域·誓约 启动器 (SAO Oath Launcher) — plan

Fork of HMCL **v3.17.0.361** (19 Oct 2026), the last release before HMCL disabled game downloads in mainland China (#7017, 20 Oct). Branch `sao/main`. GPLv3 + HMCL's additional terms: new name and version, HMCL copyright notice kept (About page), source public.

## James's decisions (6 Nov 2026)
- Pack-only launcher: one SAO screen installs/updates the 誓约 pack (OATH 0.8, NeoForge 47.1.106, MC 1.20.1), picks Java and memory, joins our server. HMCL's version lists, mod download, instance pages hidden.
- Look: our SAO UI family (blue log-in panel, bubbles, SAO font, title wallpaper, crossing field + lyrics); Launch = Link Start.
- Accounts: offline name, Microsoft (正版), sao_auth log-in inside the launcher.
- Windows, macOS, Linux.
- Distribution (James, 6 Nov): players download the launcher and the pack together as one Quark zip. First start installs the pack from the folder beside the launcher (no pack download); MC 1.20.1 + NeoForge 47.1.106 come through HMCL's downloader (BMCLAPI in China). Updates: a newer Quark zip, or an update zip dropped on the window; the launcher applies it and keeps options, saves, screenshots. An optional small version notice (any static file host) can say a new zip is out.
- Base on an HMCL version that still has the download functions.

## Phases
0. **Fork shell** — name, version, app id, icons; HMCL update check, announcements, telemetry and HMCL links off; own data folder (`.sao-oath-launcher`) so it never touches a player's HMCL setup.
1. **SAO main screen** (frontend) — JavaFX replacement for RootPage/MainPage: wallpaper crossfade, music + lyrics, account panel (three kinds), Link Start, progress for install/update, small settings sheet (memory, Java, language, folder, music/lyrics on/off). Every HMCL page we keep is restyled or hidden; nothing half-skinned.
2. **Pack engine** (backend) — a manifest inside the zip (version, files with sha1/size, removed files, NeoForge/MC versions), first install via HMCL's game + NeoForge installer (BMCLAPI mirror in China), incremental update that keeps player options/saves/screenshots, repair, fixed instance; server auto-join (`--quickPlayMultiplayer`).
3. **sao_auth hand-off** — the launcher's log-in panel gives the password to the client mod for the 誓约 server only, never on disk in plain text, never in the process arguments; no server secrets in the launcher.
4. **Packaging** — Windows .exe, macOS .app (+ dmg), Linux .sh/.jar; each with a bundled Java 17/21 runtime or HMCL's Java download.
5. **Proof** — fresh-machine install, update, repair, offline/Microsoft/sao_auth, all three systems (Windows in the Parallels VM).

## Open
- Whether the zip also carries MC + NeoForge files (fully offline first start, bigger zip) or the launcher downloads them.
- Server address for auto-join.
- Microsoft log-in needs an Azure app client id registered for this launcher (HMCL's own id is for HMCL builds only).
