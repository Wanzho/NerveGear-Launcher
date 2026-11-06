# Launcher news

The pack manifest may contain `newsUrl`, an HTTPS JSON endpoint. It defaults to an empty string, which disables the request. Distribution builds can set `-PsaoNewsUrl=https://example.invalid/news.json`. The endpoint must not contain credentials; redirects are not followed.

The response is plain UTF-8 JSON, at most 256 KiB and 100 entries. Serve entries newest first. The screen renders plain text, not HTML. `kind` is `NOTICE` or `UPDATE`, `title` is required (1–200 characters), and optional `date` (up to 40 characters) and `body` (up to 16,000 characters) default to empty strings.

```json
{"news":[{"kind":"NOTICE","title":"Scheduled maintenance","date":"2026-11-06","body":"The server will restart at 20:00."}]}
```

Only a fully validated response replaces `online-news.json` in the launcher's data root. Network errors, malformed responses and oversized responses retain the last good document after restart. This cache contains public news only. The launcher also always includes an `UPDATE` entry for its embedded version. Its body comes from an actual `changelog`, `changes`, `更新日志`, `更新说明` or `变更` file in that archive (up to 64 KiB). With no such file, its title is `v<version>` and its body is empty. A dropped newer pack does not change the launcher's embedded-version entry.

`refreshOnline()` coalesces concurrent requests. The configured Minecraft server is queried separately using the Minecraft 1.20.1 status protocol (763), with a three-second total ping deadline. An unconfigured server has null status; a configured server that does not answer is offline, with unknown latency (`-1`). No server address is inferred from resource packs, options or news.
