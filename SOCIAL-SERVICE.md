# ARGUS social service: protocol 2 UI handoff

Use `SaoLauncherService` only. Production implementation is `SaoPackEngine` delegating
to `SaoFriends`; no UI sources were edited. Futures complete on JavaFX (the explicit
no-toolkit test path executes directly). Lists retain their identity and are read-only;
records are replaced. The old constructors and two-argument send remain available.
All new failures are `SaoAccountException` with fixed Chinese messages and stable codes.

| Service API | UI behavior / result |
| --- | --- |
| `friends()` | `Friend` adds stable `id`, private `remark`; `displayName()` chooses remark or public name |
| `chats()` | pinned first; `Chat` adds `mentions`, `muted`, `pinned`, `shouldNotify`, `peerRead`, `typing` |
| `messages(chatId)` | oldest first, stable list; `ChatMessage` now retains IDs and rich metadata |
| `loadOlderMessages(chatId)` | explicit older-page scroll callback; old repeated `messages()` paging still works |
| `sendMessage(chatId, text)` | protocol-1 ordinary send with a pending row and nonce retry |
| `sendMessage(chatId, text, replyTo)` | quote a confirmed source ID from the same chat; 0 means ordinary send |
| `sendPlayInvite(chatId)` | sends a presence-derived ten-minute invite; no client-supplied host |
| `acceptInvite(message)` | `CompletableFuture<String>` returns exact server target after authoritative revalidation; never launches automatically |
| `recallMessage(message)` | own confirmed message through two minutes inclusive; replaces same ID in the list |
| `typing(chatId)` | call while editing; service coalesces to once per three seconds; do not call from message listeners |
| `setChatPreferences(chatId, muted, pinned)` | stores both current-user flags; preserve the other flag when toggling one |
| `setFriendRemark(name, remark)` | private 0–12 Unicode scalar values; empty clears |
| `searchMessages(text, chatId, limit)` | immutable newest-first results, 1–100; `chatId=""` means all current chats |
| `markRead(chatId)` | only while visible, after displaying messages; marks through the latest received ID |
| `knownServers()` | current canonical server plus relay nodes; exact allowlist used again on invite acceptance |

`ChatMessage` fields: existing `from/text/time/mine/pending`, plus `id/chat/sender/nonce/
revision/kind/quote/invite/mentions/shouldNotify`. `kind` is `TEXT`, `INVITE` or `RECALLED`.
`Quote(replyTo, sender, from, text)` holds the supplied author and first 60 scalar values.
`PlayInvite(target, expiresAt)` supplies the expiry/countdown; always pass the original
message to `acceptInvite`, never extract a destination from text. Acceptance re-fetches
by chat/message ID, checks membership, blocks, recall and expiry, and validates the
returned target against this launcher's list. The UI owns the explicit Link Start action.

For 已读, choose the last confirmed outgoing row with `message.readByPeer(chat)==true`
and show the label under that one row. Groups always return false. Never equate an ACK
or delivered pending row with reading. `Typing(id, from, expiresAt).active(now)` expires
locally at five seconds: use a UI timer to remove the text even during network loss.
Read/mute/pin/typing/remarks never generate a game chat message.

The wire field `notify` is named `shouldNotify` in Java (Object.notify is final).
Use chat/message `shouldNotify` only for new incoming delivery; history, search, replayed IDs
and record replacements must not generate another banner. Muted chats preserve all
counts. `mentions` is a count of unread visible messages that mention this account,
not the number of `@` tokens. The server resolves UUIDs; the UI must not infer them
from names. Quotes update when their source is recalled; original text is erased on
the authority. Blocks/membership changes invalidate cached pages and reload visible
history. Display the returned recall line; do not keep a hidden original in UI state.

Text length remains 1024 UTF-16 units (`String.length()`), preserving protocol 1.
Emoji sequences are sent without normalization. Remarks/quotes count Unicode scalar
values (`codePointCount`), so a supplementary emoji does not get split in previews.
No attachment API, image upload or file transfer is present in this protocol.

## Offline screens and SaoFakeService

`SaoFakeService` is under the prohibited `org/jackhuang/hmcl/ui/` tree. Its source was
left untouched. The backend supplies an in-memory fixture decorator in
`org.jackhuang.hmcl.sao.SaoSocialSamples`, so the UI owner can wrap its existing fake:

```java
SaoLauncherService service = SaoSocialSamples.decorate(existingSaoFakeService);
```

The decorator delegates unrelated launcher methods to that fake and supplies social
lists/actions without an account server. `demo-direct` includes an invite, a reply,
a recalled message, a read outgoing message, typing, emoji and a pin. `demo-group`
includes a mention and mute; both friends have remarks. It supports in-memory send,
reply, invite acceptance, recall, search, markRead, remark and flag changes. No fixture
is installed into the production registry. Create a fresh decorator to reset samples.
The five-second typing sample deliberately expires; reopen/reset the demo to replay it.

Detach the conversation's message listener on window close and call `markRead` only
while visible. The backend cannot infer the visibility of a closed window from a stale
listener. See `SOCIAL-PROTOCOL.md` for the full authenticated route/limit contract.
