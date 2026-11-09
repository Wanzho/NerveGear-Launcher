# Launcher news

Production news uses the signed Gitee endpoint described in [SAO-UPDATES.md](../SAO-UPDATES.md), with `-Dsao.newsUrl` as a transport override. Pack-manifest `newsUrl` is retained as pack metadata and does not authorize remote release news. The exact bytes of `news.json` require a sibling `news.json.sig` from an embedded Ed25519 key before parsing. Both resources share one ten-second deadline and the constrained Gitee raw-host redirect policy.

The response is plain UTF-8 JSON, at most 256 KiB and 100 entries. Serve entries newest first. The screen renders plain text, not HTML. `kind` is `NOTICE` or `UPDATE`, `title` is required (1–200 characters), and optional `date` (up to 40 characters) and `body` (up to 16,000 characters) default to empty strings.

```json
{"schema":2,"sequence":1,"news":[{"kind":"NOTICE","title":"Scheduled maintenance","date":"2026-11-06","body":"The server will restart at 20:00."}]}
```

Only authenticated, fully validated news with a sequence at least as high as the persisted news high-water mark replaces the signed entry in `online-release-state.json` in the launcher’s data root. Cache loading re-verifies the exact bytes and signature. Missing/wrong signatures, lower sequences, network errors and malformed/oversized responses are logged and retain the last-good news without dialogs. Old unsigned caches are ignored. Increase `sequence` on each change, sign the final bytes locally, and upload the JSON and `.sig` together; see the Chinese release steps in SAO-UPDATES.md. The launcher also always includes an `UPDATE` entry for its embedded version. Its body comes from an actual `changelog`, `changes`, `更新日志`, `更新说明` or `变更` file in that archive (up to 64 KiB). With no such file, its title is `v<version>` and its body is empty. A dropped newer pack does not change the launcher's embedded-version entry.

`refreshOnline()` coalesces concurrent requests. The configured Minecraft server is queried separately using the Minecraft 1.20.1 status protocol (763), with a three-second total ping deadline. An unconfigured server has null status; a configured server that does not answer is offline, with unknown latency (`-1`). No server address is inferred from resource packs, options or news.
