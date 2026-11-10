# Linked Microsoft appearance sources — 1.8.0

Release 1.8.0 includes the complete 1.7.0 social protocol 2 implementation. Cosmetic
inheritance adds account API routes; it does not change the login query, launch ticket,
Microsoft ownership proof or social protocol version. No deployment is performed by a
local build.

## Identity and storage

Only the account's stored `premiumUuid` authorizes inheritance. The ARGUS nickname is
never used to find a Mojang profile. The independently stored `premiumName` is refreshed
from that UUID's Mojang profile and used for OptiFine's current-name lookup. This name
update preserves credentials, remembered devices, account nickname and stable profile ID.

`textures/profiles.json` remains the explicit-upload index. The separate private
`inherited-textures.json` schema 1 stores the linked UUID, four inherited slots, optional
source preferences, last completed refresh, manual cooldown and next daily time.
Both indexes refer to normalized immutable blobs in `textures/<sha256>.png`. Uploads are
never overwritten by inheritance. Back up both indexes and the blob directory with the
existing account/signing files. Unlinking through any existing account operation removes
inherited associations and resets preferences to own when the profile/state is next read;
there is no new unlink endpoint. Late background results recheck current ownership under
the account write lock before publishing.

Production first login/link queues initialization on low-priority daemon workers.
Subsequent refreshes occupy a stable per-profile daily slot spread across 24 hours, with
minute-resolution dispatch and two background workers. Authentication/profile reads never
wait for provider HTTP. The injected-clock router constructor disables automatic network
work for deterministic tests; explicit manual fixture refresh remains available.

## Authenticated launcher API

All routes use the existing memory-only `Authorization: Bearer <ARGUS token>` and the
existing account-service origin. No Microsoft token is accepted or returned here.

| Method | Route | Body | Result |
|---|---|---|---|
| GET | `/sao/textures` | none | 200 state |
| POST | `/sao/textures/choices` | one or both of `skinSource`, `capeSource` | 200 state |
| POST | `/sao/textures/refresh` | `{}` | 202 queued/current state |

`skinSource` accepts `own` or `premium`. `capeSource` accepts `own`, `mojang`, `optifine`,
`minecraftcapes` or `none`. An unlinked account can read state but source changes and
refresh return 403 `premiumUnlinked`. Invalid choices return 400 `textureChoice`.
A refresh attempt charges one durable five-minute account cooldown. A repeat returns
429 `textureRefreshLimited` with integer `waitSeconds` rounded upward. Queue saturation
returns 503 `textureSyncBusy` without charging the cooldown. Daily work does not consume
manual cooldown.

State fields:

```json
{
  "linked": true,
  "skinSource": "premium",
  "capeSource": "mojang",
  "effectiveSkinSource": "premium",
  "effectiveCapeSource": "mojang",
  "images": {
    "premiumSkin": {
      "hash": "<64 lowercase hexadecimal characters>",
      "model": "slim",
      "url": "https://<account-service-origin>/textures/<hash>"
    }
  },
  "lastRefresh": 0,
  "refreshAfter": 0,
  "status": "syncing"
}
```

`images` may contain `ownSkin`, `premiumSkin`, `ownCape`, `mojang`, `optifine` and
`minecraftcapes`. Unavailable images are null or absent. Model is empty for classic skin
and all capes; slim skin uses `slim`. `lastRefresh` is epoch milliseconds of the last
completed attempt, or zero. `refreshAfter` is the remaining manual cooldown in seconds.
Status is `idle`, `syncing`, `synced` or `failed`; failure can coexist with usable last-good
images. Poll GET while syncing. The refresh response does not claim synchronous completion.

Without an explicit choice, an own upload wins. A linked account without its own skin
uses premium skin. Cape defaults are own upload, Mojang, OptiFine, MinecraftCapes, then
none. An explicitly selected unavailable image falls back through those same defaults.
Explicit cape `none` always hides the cape. Stored choices remain distinct from effective
sources, so later successful refreshes can restore the preferred source.

The launcher publishes `ReadOnlyObjectProperty<SaoTextureState> inheritedTextures()` and
`CompletableFuture<Void>` actions `setSkinSource(String)`, `setCapeSource(String)` and
`refreshInheritedTextures()`. Image descriptors contain hash, model and preview `URI`.
All production preview URIs are validated against the captured issuing account origin,
including during polling if endpoint settings change. The initial queued refresh is polled
in the background without delaying sign-in, including linked `idle` state with no
completed refresh while its first initialization job waits for a worker. A manual refresh polls for 45 seconds; a still
queued result reports `同步中` and continues background polling instead of claiming success.
The existing `oathSkin()` is refreshed when inherited work completes. Logout clears the
observable state. Cosmetic outages do not sign out the account. The fake service uses six
distinct local sample PNGs and mirrors source actions and manual cooldown.

## Fetch and image policy

Logical outbound hosts are exactly `sessionserver.mojang.com`, `textures.minecraft.net`,
`s.optifine.net` and `api.minecraftcapes.net`. Alternate ports, credentials, query strings,
fragments, encoded paths, path traversal and cross-host redirects are refused before
network access. At most one redirect is allowed within the original host; HTTPS cannot
downgrade. A process-wide semaphore permits at most two outbound fetches across accounts
and router instances. Each request uses a 1-second connect timeout, 1.5-second read timeout
and 4-second streaming deadline; a stalled read can extend that deadline by at most one
read timeout. Redirects share that deadline. No authorization/cookies are sent to providers.

Mojang profile JSON is capped at 64 KiB and fetched by compact UUID from
`https://sessionserver.mojang.com/session/minecraft/profile/<uuid>`. `SKIN` and optional
`CAPE` come from its base64 textures property, with images restricted to the exact
`textures.minecraft.net/texture/<hash>` route. OptiFine uses
`http://s.optifine.net/capes/<current-premium-name>.png`.

MinecraftCapes uses `https://api.minecraftcapes.net/profile/<compact-uuid>`, bounded at
2 MiB JSON. It accepts the historical `textures.cape` base64 image and current nullable
`cape_url` / `animated_cape_url` fields, preferring animation when `animatedCape` is true.
Any supplied URL must remain HTTPS on the exact `api.minecraftcapes.net` host; a new CDN
host is refused and preserves the last good copy. Live checks confirmed the nullable
fields and deprecation notice; the origin of populated current URL fields remains
unconfirmed. No additional host has been inferred or added.

Raw images are capped at 1 MiB. JSON uses the existing strict UTF-8, depth-12 and
duplicate-key checks. PNG signatures and IHDR dimensions are checked before ImageIO.
Skin must be 64×64 or 64×32. Cape output is a 2:1 power-of-two canvas, 64 through 1024
pixels wide. MinecraftCapes vertical strips are at most 4096 pixels high / 4 Mi pixels;
only the first width/2-high frame is retained. OptiFine 46×22 and exact integer multiples
are copied pixel-for-pixel into a sufficiently large power-of-two canvas; no interpolation
or fractional scaling is used. All outputs pass `YggdrasilTextures` RGBA re-encoding and
the configured public blob byte limit before association.

HTTP failures (including 404), invalid JSON, wrong hosts, malformed/oversized PNGs and
storage failures preserve that source's last good image. A successful profile explicitly
without a cape may clear that source. Other successful providers can still update. Signed
Yggdrasil texture properties always contain only the account service's `/textures/<hash>`
URLs and use its existing RSA key. Discovery still advertises only the account-service host.

## Verification

`inheritedTextureTest` runs real loopback fixture HTTP responses for Mojang, OptiFine and
both MinecraftCapes schemas. It covers slim skins, OptiFine standard/HD padding, animation,
source defaults and fallback, own-upload isolation, malformed inputs, redirect and byte
limits, shared concurrency, independent last-good retention, cooldown persistence, unlink
races, daily dispatch, authenticated choices and signed local URLs. No public provider,
game process, launcher window, browser or real account is used by these contracts.

Launcher `SaoInheritedTextureTest` covers captured-origin transport, source actions,
curated errors, initial/manual refresh polling, observable signout clearing and all six
local fake preview PNGs. Existing local premium fixtures use the injected-clock constructor
so premium-login regression tests cannot start automatic public cosmetic requests.
