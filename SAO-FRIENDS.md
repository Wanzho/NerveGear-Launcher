# ARGUS friends and chat engine

The new `SaoFriends` controller implements the merged `SaoLauncherService` seam in
`SaoPackEngine`. The account state machine supplies only its current ephemeral bearer.
No UI sources or seam records are modified after merging `sao/ui`.

The full API, limits, same-port SSE/resume protocol, server bridge and exact singleplayer
client handoff are in the account-server repository's `SOCIAL-PROTOCOL.md`. This engine
uses finite SSE connections, independent API and stream workers, polling fallback,
idempotent send nonces, ordered server-ID maps and per-origin/profile cursor files.
Only a random installation device label and nonsecret cursor are stored locally.
Chat bodies and bearer credentials are not persisted by this controller.

Observable lists and futures are published on JavaFX. The explicit no-toolkit fallback
exists for headless tests. Pending messages become delivered after server acceptance;
failed sends remove their pending row and complete with a short Chinese
`SaoAccountException`. One ambiguous network failure retries the same nonce once.
There is no durable offline outbox or failed-message row in the current data records.

Game presence follows the actual prepared ARGUS process owner. The existing process
creation/exit hooks set GAME / 单人游戏 or LAUNCHER; live dedicated-server presence and
its explicit dimension-floor mapping override the process report. Account switches
cannot attach the prior account's running game to the new social session. Closing or
signing out cancels stale publications and closes the live body. Polling presence has
a 25-second expiry; the current socket supersedes a stale same-device polling lease.

Two screen follow-ups remain for the frontend owner:

- The current seam has no explicit page request. Repeated `messages(chatId)` calls
  load successive older 50-message pages; first access loads the newest page. Wire
  this to the top-of-scroll action rather than assuming all history is loaded.
- Detach the current message-list listener on conversation/window close. It currently
  remains attached and continues calling `markRead` for the formerly open chat. The
  backend marks only received IDs and does not assume visibility; it cannot detect
  that a frontend callback is stale. A visibility/close callback would also resolve it.

Singleplayer HUD reception/replies require the separate client-mod owner to implement
the documented HTTP/e33chat adapter. This task does not modify or deliver a client mod.
The dedicated-server bridge uses unmodified e33chat 2.4.17, its own whisper capture
path and existing group packets, with a small optional server group-routing mixin.
No FTB Teams ally import was added.
