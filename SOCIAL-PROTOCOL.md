# ARGUS ID friends and chat, protocol 2 / sao_auth 1.7.0

Friends, private groups, history, unread state and delivery cursors live in the account
world's `sao-auth/social.json`. Identity is the stable profile UUID, never a nickname.
The existing account password hashes, identity keys and world data are unchanged.
No service is deployed by this change. The launcher and dedicated server are usable
without a client-mod change; singleplayer HUD integration is the client owner's work.

## Authentication and values

All routes below use `Authorization: Bearer <ARGUS accessToken>` over the existing
API origin and port. Use HTTPS outside literal-loopback tests; never follow redirects.
Tokens, tickets, message text and request bodies must not enter logs. Do not put them
in query strings. Names are the current 游戏昵称, resolved case-insensitively to UUIDs.

JSON responses are objects. Failed requests have `error` (fixed code) and
`errorMessage` (fixed English diagnostic). The launcher's `SaoAccountException` maps
codes to short Chinese messages; arbitrary provider/server text is never shown.
Social calls allow authenticated mailbox-management sessions, including `lockedVerify`;
the existing mailbox gate still applies to world admission. Sessions expire/revoke
exactly as before; logout, password changes and account rename invalidate the stream.
The independent launcher login proof is never a chat bearer.

Identifiers and cursors are nonnegative integer values below JavaScript's exact-integer
limit. Conversation IDs are opaque strings. A direct chat has exactly one deterministic
ID per unordered UUID pair. A group ID is random. Message `id` uses the same durable
monotonic sequence as state revisions; IDs have gaps. Timestamps are epoch milliseconds.
`sender` is the stable dashed UUID; `from` is its current nickname; `mine` is per reader.

## HTTP API

Prefix every route with `/sao/social/`. POST bodies are JSON; GET query values must be
URL encoded. History is newest first; launcher observable message lists are oldest first.

| Method / route | Request | Success |
| --- | --- | --- |
| GET `state` | none | `{friends, requests, chats, cursor}` |
| POST `request` | `{name}` | `{}`; opposite pending requests become a friendship |
| POST `answer` | `{name, accept: boolean}` | `{}`; only incoming requests can be answered |
| POST `remove` | `{name}` | `{}`; removes friendship, both requests and direct conversation/history |
| POST `block` | `{name}` | `{}`; same removal plus durable directional block |
| POST `direct` | `{name}` | `{id}`; requires a current friendship |
| POST `group` | `{title, members: [friendName, ...]}` | `{id}`; creator is added once |
| POST `invite` | `{chat, members: [friendName, ...]}` | `{}`; inviter must belong and invite their own friends |
| POST `leave` | `{chat}` | `{}`; the last member deletes the group and its history |
| POST `send` | `{chat, text, nonce, replyTo?: messageId}` | full message object, including its stable server `id` and optional `quote` |
| POST `playInvite` | `{chat, nonce}` | `INVITE` message; server derives `target` from dedicated presence and sets `expiresAt` |
| POST `acceptInvite` | `{chat, message: messageId}` | `{target}`; current membership, visibility, kind, expiry and allowlist checked |
| POST `recall` | `{chat, message: messageId}` | `RECALLED` replacement with same `id`, newer `revision`, original text erased |
| POST `typing` | `{chat}` | `{}`; transient five-second signal, at most once per three seconds per sender/chat |
| POST `preferences` | `{chat, muted?: boolean, pinned?: boolean}` | `{}`; private flags, omitted flags keep previous values |
| POST `remark` | `{name, remark}` | `{}`; private friend remark; empty clears it |
| POST `search` | `{text, chat?: chatId, limit?: 1..100}` | `{messages}`; newest first; omitted chat searches all current conversations |
| POST `read` | `{chat, through: messageId}` | `{}`; monotonic read watermark, bounded by current sequence |
| GET `history` | `chat`, `before=0`, `limit=50` | `{messages, nextBefore}`; `nextBefore=0` means exhausted |
| GET `poll` | `device`, `after=0`, `game=false` | bounded sync batch, same shape as SSE data |
| POST `ack` | `{device, cursor}` | `{}`; persist only after applying a batch locally |
| POST `disconnect` | `{device}` | `{}`; releases that device's fallback presence lease |
| GET `events` | `device`, `after=0`, `game=false`; optional `Last-Event-ID` header | finite `text/event-stream` with `sync` frames as specified below |

State is `{protocol: 2, friends, requests, chats, cursor}`. A sync batch adds
`messages`, `more`; the durable `schema: 1` file is extended additively and old
relationships, message IDs, timestamps, invitation epochs and ACKs are retained.

A friend is `{id, name, presence: GAME|LAUNCHER|OFFLINE, where, remark}`. `remark`
is visible only to its author and never replaces the public nickname. A request is
`{name, incoming}`. A conversation is `{id, title, group, members: [name,...], unread,
mentions, muted, pinned, notify, lastText, lastTime, typing, viewVersion}`. Direct
conversations additionally contain `peerRead`; groups never expose member receipts.
Pinned chats sort first, then by newest message time. Muting preserves unread and
mention counts but clears notification flags. A preview is at most 160 UTF-16 units,
without splitting a surrogate pair. Typing entries are `{id, from, expiresAt}`.

A message is `{id, revision, chat, sender, from, text, nonce, time, mine, kind, notify,
mentions?: [UUID,...], replyTo?: id, quote?: {sender, from, text},
target?: string, expiresAt?: milliseconds}`. `kind` is `TEXT`, `INVITE` or `RECALLED`.
`target`/`expiresAt` belong only to invites. `revision` defaults to `id` for unchanged
protocol-1 records. Message identity/history/read ordering uses `id`; live delivery
ordering and replacement deduplication use `(revision, id)`. Replace a known ID when
a newer revision arrives. A recall removes invite/quote/mention payload fields.

## Protocol-2 feature contracts

- Play invitations use POST `playInvite`, distinct from protocol-1 group `invite`.
  The sender must be in dedicated GAME presence: `誓约` maps to `mc.saooath.com`;
  exact presence addresses `mc.saooath.com`, `zz.saooath.com`, `tz.saooath.com`,
  `sq.saooath.com` are also accepted. Other labels, singleplayer, arbitrary hosts,
  schemes and ports fail `inviteTarget`. Request fields `target`, `host` or `where`
  are refused. Cards read `{name} 邀请你一起 Link Start`, expire after 600000 ms,
  and cannot be accepted at or after expiry. Acceptance returns a destination only;
  it does not launch anything. The launcher repeats an exact comparison with its
  current `knownServers()` list, and accepts no destination taken from display text.
- Replies require a visible source in the same current chat/invitation epoch.
  `quote` includes its current author and first 60 Unicode scalar values. The quote
  is produced by the authority even without local history; no quote plaintext copy
  is stored. Blocked/otherwise invisible sources have their quote and reply ID omitted.
- Only the sender can recall a visible own message, through exactly `time+120000`
  ms inclusive. At `+120001` it fails `recallExpired`; another sender fails `notSender`.
  Repeating an accepted recall returns its tombstone, even after the window.
  Stored text becomes empty; displayed text is `{name} 撤回了一条消息`. There is no
  hidden original, backup field or stored quote copy. Historical world backups are
  outside this transaction's retention boundary. The same stable ID gets a new
  revision so devices that already ACKed the original receive the replacement.
  Clients must also scrub any cached quoted previews referencing that ID. A retried
  original send using its now-recalled nonce fails `nonceConflict` without reviving it.
- `peerRead` is the other participant's monotonic read watermark. Show 已读 under
  the latest confirmed outgoing message at/below that watermark; never infer reading
  from delivery ACKs. There are no per-member group read receipts.
- Typing has no storage writes, sequence increment, history entry or game packet.
  The sender/chat limit is 3000 ms, the lease is 5000 ms, and expired or blocked
  endpoints are excluded in both directions. UI hides expired entries even if sync
  stops. Calls without current chat membership fail `chatMissing`.
- Group mentions match complete case-insensitive `@nickname` tokens against current
  members, resolve to stable UUIDs once at send time and deduplicate each recipient
  per message. Nonmembers, the sender, and either-direction blocked users are omitted.
  Each visible unread mentioning message adds one to `mentions`; reading/recalling it
  clears that contribution. Text is still delivered normally to other authorized peers.
- Private `muted` and `pinned` flags are stored per account/chat and survive restart.
  Private friend remarks allow 0–12 Unicode scalar values with the same single-line
  control/formatting safety checks as messages.
- Search text is a nonblank 1–100 UTF-16-unit case-insensitive substring, POSTed in
  the body, never a URL. Limit defaults to 50 and is bounded to 1–100. Results include
  only stored text in current visible conversations, respect blocks/invitation epochs,
  exclude recalls, and sort newest ID first. Search never marks read or moves ACKs.
- Unicode is preserved without normalization or stripping variation selectors, ZWJ,
  skin-tone modifiers or regional indicators. The existing 1024 UTF-16-unit message
  limit remains: `😀` counts as two units; `😀` repeated 512 times fits. Malformed
  lone surrogates, ISO controls, line separators and `§` formatting are refused.
- `viewVersion` changes on group visibility-affecting block/membership transactions.
  A client must discard cached messages/pages for a changed view and reload visible
  history. This prevents old cached group messages/quotes surviving a block or rejoin.

All new POST routes use the existing bearer and 600/account/minute operation quota.
`playInvite` shares the 30/minute, 500 ms and capacity/nonce rules of ordinary sends.
Typing additionally has its independent three-second limit. New fixed errors include
`inviteTarget`, `inviteExpired`, `messageMissing`, `messageRecalled`, `notSender`,
`recallExpired`, `typingRate`, `remarkLength`, and `searchLength`. All diagnostics
remain fixed and exclude request bodies, credentials and provider messages.

Blocking is enforced in both directions for requests and direct conversations. In a
shared group, messages from either blocked endpoint are excluded from the other's
history, live batches, previews and unread count. Other members continue receiving
messages. No nonfriend presence lookup exists. Removing the friendship prevents the
blocked account from seeing the blocker's online state. Blocking does not expose a
block-list query or provide an unblock UI in the current seam.

Newly invited and reinvited members see only messages at or after their invitation
epoch. Group names are globally unique ignoring case and cannot collide with an
existing native e33chat group. All current group members may invite their own friends;
there is no implicit operator/owner override of friendship or block checks.

`send.nonce` is a random 8–80-character `[A-Za-z0-9_-]` key, scoped to the sender.
Reuse the identical key and identical chat/text/replyTo/kind after an ambiguous timeout: the server
returns the original ID without charging the send limit or storing another copy.
Reusing a key with different content returns `nonceConflict`. The nonce remains
idempotent while the conversation/history is retained. A lost response after a direct
conversation was removed cannot resurrect that conversation.

`read.through` must be the greatest message ID actually displayed/fetched in that
conversation. Zero is a no-op. It never means “read future messages.” Read watermarks
are per player, whereas delivery ACKs are per device. Do not infer reading from an ACK.

## Live channel and delivery semantics

GET `/sao/social/events?device=<installation-id>&after=<cursor>&game=false` with:

```
Authorization: Bearer <ARGUS accessToken>
Accept: text/event-stream
Last-Event-ID: <cursor>
```

`Last-Event-ID`, when present, wins over `after`. The response is HTTP 200,
`Content-Type: text/event-stream; charset=utf-8`, `Cache-Control: no-store`. Each frame is:

```
id: 123
event: sync
data: {"cursor":123,"friends":[],"requests":[],"chats":[],"messages":[],"more":false}

```

One batch contains at most 100 visible message replacements in ascending revision order. If `more=true`,
resume from that batch's `cursor` to drain the next portion; do not jump to a later
state revision from another response. The server starts after `max(deviceACK, after)`.
A closed/revoked stream emits no more data. Subscriptions are finite (8 seconds),
with a sync emission about once per second. Reconnect immediately with the last applied
cursor. This fits the existing bounded HTTP transport and 15-second absolute socket
deadline without occupying a worker between emissions. There are at most 128 admitted
API sockets, two bounded general API workers and two separate login/session workers.

If SSE fails or is filtered, call `poll` and retry the stream with exponential backoff
(up to 10 seconds). A polling device holds a 25-second presence lease. SSE and polling
share one device slot, so a stale fallback GAME lease cannot outlive a newer LAUNCHER
socket. A stale socket close cannot erase the newer device state. Independent devices
retain independent presence. Dedicated-server GAME presence wins over launcher reports.

Exactly-once network receipt is impossible across a crash between receipt and ACK.
This protocol provides durable idempotent storage and once-per-device logical delivery:
apply by stable message ID and newest revision, durably save the cursor, then ACK.
An unacknowledged frame may replay after a dropped connection; discard equal/older revisions. The launcher uses
ordered ID maps and a nonsecret atomic cursor file scoped by API origin/profile. It
keeps pending nonces until acceptance or a fixed error, retries an ambiguous send once
with the same nonce, and never stores chat bodies or bearer credentials in that cache.
An exhausted retry reports a network failure; history/live sync can still reconcile
an accepted send. There is no persistent offline outbox in this release.

History paging is independent of device delivery. Opening history intentionally shows
previously delivered messages again; that is not a new notification. On restart, the
launcher reloads the most recent history page when a conversation is opened.

## Dedicated-server e33chat bridge

Pinned read-only dependency: `e33chat-Forge-1.20.1-2.4.17.jar`. It is not bundled or
redistributed. No configuration template changes are required. Keep
`groups_enabled=true` and `template_debug=false`; keep the client's chat debug option
off, since e33chat's optional debugging can log message contents. ARGUS never logs
bodies, passwords, tokens or tickets. Native e33chat and its configs remain owned by
that mod.

`ArgusChatBridge` obtains the real admitted UUID from the server connection. Private
`/msg`, `/tell`, `/w`, `/whisper` (including namespaced forms) are captured before
vanilla logging, target resolution and FTB aliases. Only a literal game nickname is
supported; selectors and incomplete commands do not create social messages. Existing
e33chat reply buttons use `/msg`, so offline friends work without an online tab-list
entry. `/r` and `/reply` use the latest incoming direct peer for that connection.
The same `ArgusSocial` transaction handles the send; there is no second mailbox.

Protocol-2 bridge projections are literal one-line text: invite cards never expose their
address or a clickable target; replies prefix `回复 {name}：{quoted preview} · `;
recalls append their notice and cannot remove a line already printed in game.
Recall delivery deduplicates on revision, retaining the original ID. Typing, receipts,
private flags and remarks never become game messages or packets.

Incoming direct messages use `Component.translatable("commands.message.display.incoming",
Component.literal(from), Component.literal(text))` through `ServerPlayer.sendSystemMessage`.
Own echoes use `commands.message.display.outgoing`. e33chat's inspected
`ChatListenerMixin.onSystemChat -> ChatClassifier.classifyByKey` sets its whisper
metadata, and its own capture/effect path supplies the bubble and banner. It explicitly
supports an offline sender name (UUID zero fallback in its classifier). No fake online
player or forged signed chat packet is needed.

Group delivery instantiates e33chat's existing `GroupChatPacket(UUID, name, group,
content, quoteSender, quoteContent)` and sends it only to current authorized group
members. Its existing client handler calls `ChatMessageStore.addGroupMessage`.
`GroupListPacket(enabled, names, memberCounts, myGroups)` combines native groups with
only the receiving player's ARGUS groups. UUID membership, name and count come from
one account-store group; offline members count normally. Its client handler calls
`GroupChannelState.applyDirectory`.

The optional `@Pseudo` `E33GroupMixin` has four short injection methods, targeting
`say`, `sendGroupList`, `leave`, and the same-signature `join/delete/create` methods.
It is necessary because e33chat exposes no private/invitation-only group extension
point: its own groups are public joinable and cannot represent offline invites safely.
Only ARGUS names are intercepted. Existing `/e33chat group msg <name> <text>` routes
through `GroupManager.say` into ARGUS; leave routes into the same membership transaction.
Native join/delete/create cannot bypass ARGUS invitations. Native groups retain their
own data and behavior. The native text-only `group list` command still enumerates its
native registry; the HUD's packet directory contains both. No e33chat field or codec
is modified. Optional class absence skips the mixin; a loaded incompatible packet API
refuses bridge startup. A future e33chat upgrade requires repeating the pinned proof.

Dedicated delivery ACKs after packets are queued to the live connection. Unmodified
e33chat has no per-message receipt/renderer acknowledgment; a disconnect after queueing
can lose a HUD notification, while authoritative history remains available in the
launcher. This is distinct from the stronger client-ID dedup contract above. The
headless proof covers actual vanilla whisper packets, e33chat group packets and
bidirectional commands. Physical bubble placement/banner animation remains untested.

## Singleplayer client-owner contract (not implemented here)

No account-server mod runs inside the singleplayer world. The client owner must add
an HTTP consumer to the client mod; do not start a local account server or send these
messages to an arbitrary multiplayer server.

1. At startup take ownership of `.sao-launch-ticket.json` using the fixed-file, owner,
   symlink, profile, target and expiry checks in `CLIENT-LAUNCH-PROTOCOL.md`; delete it
   immediately. Retain only its validated `apiRoot`, profile ID and in-memory login
   proof. The standard HMCL Yggdrasil session already handed to Minecraft contains
   the ARGUS access token (`Minecraft.getInstance().getUser().getAccessToken()`).
   Use that token as the social bearer. Never use a Microsoft token or the one-use
   `ticket` as a chat bearer. No second plaintext credential file is required.
2. Bind the stream to that exact API origin and stable UUID. A client startup/login
   handler must take the file even if the user never joins 誓约. The proof's short
   expiry applies to dedicated LOGIN only; the ordinary ARGUS bearer lasts the
   configured access-token lifetime (default 24 hours). On 401/403 or signout clear
   the owned session and request launcher sign-in. Do not silently refresh/revoke
   the game's shared session. A token cannot survive account-service restart.
3. Create a distinct persistent device label, e.g. a random UUID for this client
   installation, different from the launcher's device and `oath-server`. Persist
   the cursor under origin/profile. Use SSE/resume, polling fallback, ID dedup,
   bounded pages, nonce retries and ACK order exactly as above. Set `game=true`
   while the game runs. Never block rendering, ticks, world join or Link Start on
   an HTTP request. Close the stream and disconnect the fallback lease on shutdown.
4. On the Minecraft client thread, hand a direct message to e33chat's own whisper
   capture path. Either call its vanilla system-message path with the incoming/
   outgoing translation above, or set an exact
   `ChatMessageStore.SenderMeta(senderUUID, Component.literal(from),
   Component.literal(text), false, from, true, peerName)` immediately before adding
   the corresponding translated component to `Minecraft.gui.getChat().addMessage`.
   The latter preserves the stable offline UUID and feeds e33chat's capture and
   banner path. Do not call only a generic gray/system-message append.
5. Project the account groups with `GroupChannelState.enabled=true` and
   `GroupChannelState.applyDirectory(names, counts, myGroups)`. For each message deduplicated by ID plus newest revision,
   including recall notices, the client must format replies/cards as above. For a
   group message, follow the inspected packet handler:
   `ChatMessageStore.addGroupMessage(Component.literal(text), senderUUID,
   Component.literal(from), groupTitle, "", "")`. Merge with any native directory;
   do not overwrite a remote server's unrelated native groups. Refresh the ARGUS
   directory after e33chat resets it during world changes. Keep IDs in the client
   adapter, since e33chat's public store does not retain ARGUS server IDs.
6. Intercept e33chat's outgoing `/msg` and `/e33chat group msg` for ARGUS peers/groups
   while outside 誓约. Map the peer through `direct`, then POST `send`, or send to
   the group ID. Route leave/invites through this API; never send those commands to
   an unrelated server or the integrated server. Keep all native channels working.
7. While connected to 誓约, use the dedicated bridge as the sole HUD delivery source
   and command path. Suspend the client's HTTP-to-HUD injection to prevent duplicate
   bubbles. The launcher can retain its independent device stream. Switch back to
   the client adapter after leaving 誓约, with its own durable cursor and ID set.
   Reconcile history explicitly rather than generating a second banner for backlog.
8. Call `read` only while the relevant conversation is visible and through the last
   displayed ID. World changes and hidden windows must not mark incoming messages
   read. Use e33chat's normal effect observer and unread APIs rather than a second
   notification overlay.

These are concrete endpoints and inspected e33chat call signatures, not a delivered
client implementation. Its owner must test singleplayer reception/reply, world changes,
account switch, menu/reconnect behavior, bubble/banner rendering and source dedup.
The existing mandatory dedicated launch-query client handler remains a prerequisite.

## Limits and frontend handoff

- Text: 1–1024 UTF-16 units, nonblank, one line, no ISO control or `§` formatting code.
- Sends: at most 30 per player per rolling minute, at least 500 ms apart, across
  launcher/devices/groups/game bridge. Identical idempotent retries are free.
- Friends: 200. Outstanding requests: at most 200 involving each endpoint, 10,000
  globally. Crossed requests become one friendship. There is no automatic FTB import.
- Groups: 32 members including creator; 100 groups per member; 2,000 conversations
  globally (including direct chats). Group titles: 1–12 UTF-16 units, no whitespace,
  controls, initial `#`, or `[]<>§`, unique ignoring case. Native collisions are refused.
- Stored messages: 100,000 globally. Capacity refuses new messages; no silent pruning.
  Back up and manage retention deliberately before reaching capacity. Atomic JSON
  storage suits a small community; it rewrites the file on durable changes and is
  not a substitute for a large-scale database. Files over 256 MiB refuse startup.
- Devices: 16 acknowledged device labels per account. Keep a stable installation ID.
  This seam has no device-removal UI. API operations: 600/account/minute, plus the
  existing bounded transport. History: 1–100 per page, default 50; live batches: 100.

The screens receive stable read-only observable lists, record replacements and
JavaFX-thread future completion. Pending rows appear immediately, then become delivered
when server acceptance is known. Failed rows disappear and the future supplies a fixed
Chinese error; there is no failed-row field or persistent offline queue in this seam.

Two UI follow-ups are required without backend guessing: add an explicit older-page
scroll request (repeated `messages(chatId)` calls currently fetch successive 50-row
pages), and detach message listeners when a conversation/window closes. The current
screen's attached listener continues calling `markRead` after closing its window. The
backend does not invent visibility and marks only IDs actually received, but cannot
know that the callback is stale. Add a close/visibility callback or detach the listener
before claiming fully correct unread behavior for a closed friends window. No UI source
was changed by the backend implementation.
