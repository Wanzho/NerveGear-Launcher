# SAO pack backend and client hand-off

The screen uses the unchanged `SaoLauncherService` seam. Startup installs `new SaoPackEngine()` before controller initialization. `SaoServices.close()` closes this backend during launcher shutdown. Install, update, repair and launch run on one worker; property changes use the JavaFX application thread when a toolkit exists. The headless CLI never starts a toolkit or Minecraft.

## Distribution

Build the launcher with:

```sh
./gradlew :HMCL:shadowJar -PsaoPack=/path/to/SAO-OATH0.8.zip \
  -PsaoPackBaseline=/path/to/baseline-oath08/oath08-receipt.json
```

`-PsaoPackBaseline` is optional. It reuses read-only local files only after exact SHA-1 and byte-size verification. Without it, preprocessing downloads external Modrinth dependencies from their declared HTTPS sources, verifies them, and embeds them. Source archive and receipt files are never modified. No absolute baseline paths enter the embedded manifest.

The output is `HMCL/build/libs/NerveGear-Launcher-0.1.0.jar`. `-PsaoVersion` overrides the launcher's own version. Pack versions are separate: the supplied archive's `SAO OATH0.8` name normalizes its generic `1.0.0` export ID to `0.8`; future numeric OATH names remain distinct. `-PsaoPackVersion` overrides the derived release version. `-PsaoServer=host:port` configures auto-join; its default is empty. `MICROSOFT_AUTH_ID` remains the upstream build environment input.

The full distribution contains the original archive, a precomputed manifest and content-addressed copies of all external pack dependencies. Runtime can unpack the pack without asking players to obtain it separately. Minecraft 1.20.1, exact NeoForge 47.1.106 and any missing Java 17 runtime still use HMCL's installers. China uses BMCLAPI first with official fallback; other regions retain HMCL's default source order. Forge and other Minecraft/NeoForge versions are rejected.

A normal development build without `-PsaoPack` has no pack resources and reports `NOT_INSTALLED`. Link Start gives an explicit missing-pack error. For development only, `-Dsao.pack=/path/to/pack.zip` supplies a read-only original or preprocessed pack; `-Dsao.java=/path/to/java17` selects an executable. Missing external development dependencies are fetched through HMCL and verified.

## Inventory and updates

A preprocessed update contains root `sao-manifest.json` with this shape:

```json
{
  "version": "0.9",
  "minecraft": "1.20.1",
  "neoforge": "47.1.106",
  "server": "",
  "files": [
    {
      "path": "config/example.toml",
      "sha1": "0000000000000000000000000000000000000000",
      "size": 123,
      "entry": "overrides/config/example.toml",
      "downloads": []
    }
  ],
  "removals": []
}
```

The example hash/size are schematic. Every real file needs its actual uncompressed size and SHA-1. An external file omits `entry` and supplies ordered HTTPS `downloads`. The manifest is a complete release inventory. Include all referenced override payloads in the update archive so repair remains possible; a delta-only zip with missing unchanged override members is not a supported distribution format. The launcher accepts the original `modrinth.index.json` + `overrides/` layout too and derives the full inventory.

Updates compare old and new inventory identities and write only changed or new files. They remove only files previously owned by the installed manifest. Both explicit removals and old managed paths omitted from the new complete inventory are handled. Unmanaged files are retained. All required writes are staged and verified before commit; the receipt is committed last. Commit failure rolls back preceding file changes. If rollback itself fails, its staging backups remain for recovery. Symbolic-link destinations, traversal, Windows path aliases and case-colliding inventory paths are rejected.

Existing player files are preserved: `options*.txt`, `saves/`, `screenshots/`, `shaderpacks/`, `config/oculus.properties`, `config/iris.properties`, paths containing `keybind`, and `servers.dat`/`servers.dat_old`. This includes Minecraft key mappings, Fzzy keybinds and shader selection. Installation can seed an absent default; update/repair never overwrites an existing player file. Repair hashes every inventory entry, restores missing or changed managed files and reports differing preserved defaults separately. Incremental update reports `verified=-1` because unchanged payloads are deliberately not rehashed during that operation.

Accepted dropped archives are copied into the launcher's own pack cache; a public manifest/cache filename marker survives restart. A newer accepted release wins over an older embedded pack, including its server configuration. Downgrades are rejected. If a newer installed receipt has lost its source cache, re-providing that same release restores the source and runs repair; an older embedded pack cannot downgrade it.

## Account and password boundary

Only existing HMCL `OfflineAccount` and `MicrosoftAccount` objects can launch. The backend does not add another authentication provider or create accounts. Microsoft credential refresh remains HMCL's existing account behavior.

The sao_auth password is a mutable `char[]`. The service takes ownership by cloning it and wiping the caller's buffer immediately. It retains the clone only for the current operation. Failure, cancellation, shutdown and successful hand-off wipe it. It is never placed in a process argument, environment variable, receipt, JSON settings, disk file or launcher log.

When the pack has a configured server and auto-joins it, the launcher writes exactly one line to the child's standard input:

```text
SAO-AUTH-V1 <base64 of UTF-8 password>\n
```

The final `\n` denotes one LF byte. No extra JSON, user identifier or token follows. The password limit is 1,024 UTF-16 code units. The encoder uses mutable byte buffers without constructing a password `String`, wipes the UTF-8/base64 buffers in `finally`, flushes, and closes stdin. The launcher also closes stdin without a frame when the password is absent. If stdin transfer fails after process creation, the just-created child is destroyed.

With an empty server address, Link Start opens the title screen and discards/wipes any supplied sao_auth password. It sends no frame. A password is not reusable for a later manually chosen server. The receiving sao_auth client mod must read this bounded one-use stdin frame, retain the decoded password only in memory, use it only on the configured 誓约 server connection, wipe it after login or disconnect, and never log the frame/password. That client implementation and an actual authenticated game session require their own integration proof; a launcher unit test cannot establish them.

## Skin boundary

The picker accepts only decodable PNGs with an actual PNG/IHDR header of 64×64 or legacy 64×32, bounded to 1 MiB encoded size. HD dimensions and invalid PNGs are rejected before storage. It copies the source to `<instance>/sao/skin.png` and writes `<instance>/sao/skin.json` as `{"model":"classic"}` or `{"model":"slim"}`. Passing null removes both. The picked source remains untouched. Validation and these small storage operations are synchronous so invalid input throws to the picker immediately.

Sending a selected skin over the game connection, the server's in-memory lifetime/rate limits and rendering ahead of CustomSkinLoader belong to the sao_auth mod. This launcher backend only supplies the local PNG/model files. It does not upload skins to a permanent server store.

## Headless validation

Unit tests cover inventory/version normalization, full local/remote installation, changed-only updates, managed removal, player preservation, checksum failure before commit, rollback, symlink rejection, downgrade rejection, update persistence after restart, absent-pack behavior, real PNG dimensions/model/removal, and wiped one-line stdin transfer.

Direct class entry bypasses HMCL's normal `Main` bootstrap and its JavaFX dependency loader. The shadow jar deliberately does not bundle JavaFX. For the CLI, first provide the platform-correct JavaFX **21.0.8** runtime jars for **javafx.base**, **javafx.graphics** and **javafx.controls** (the same modules declared in `JavaFXUtils.MODULES`). A JavaFX 21.0.8 SDK `lib` directory supplies these. Maven users need the platform-classified jars containing the classes, such as `*-mac-aarch64.jar`, rather than only empty unclassified marker jars. This classpath requirement does not initialize a toolkit; the CLI explicitly enables headless dispatch.

After building the complete jar and preparing those runtime libraries, use a Java 17 executable to run one real scratch proof:

```sh
java -Djava.awt.headless=true \
  -cp "NerveGear-Launcher-0.1.0.jar:/path/to/javafx-21.0.8/lib/*" \
  org.jackhuang.hmcl.sao.SaoPackCli prove \
  --root /path/to/fresh-scratch --embedded --report /path/to/fresh-scratch/proof.json
```

Use `;` instead of `:` as the classpath separator on Windows. Alternatively, use the SDK `lib` directory with `--module-path` and `--add-modules javafx.controls`; graphics and base resolve transitively.

`prove` requires a fresh instance. It installs real Minecraft/NeoForge dependencies through HMCL, applies the actual embedded pack, creates a complete synthetic newer release changing one managed TOML file/removing another, verifies exactly one write/one removal, damages one file and deletes one external dependency, then verifies repair restores exactly two files. It independently hashes the final saved inventory and player preservation sentinels. The report identifies the update as synthetic and records that neither JavaFX nor a game was started. The scratch directory retains the instance, manifest, update zip and JSON evidence.

Other CLI commands are `inspect`, `install`, `update` and `repair`, with `--embedded` or `--pack ZIP`, optional `--baseline RECEIPT` and `--report JSON`. Mutating commands require `--root`. `--files-only` explicitly skips HMCL runtime installation and is forbidden for `prove`. Reports do not include account details, passwords, baseline source paths or absolute personal paths.

Headless installation evidence does not prove a game window, Microsoft login, sao_auth server login, skin packets/rendering or Windows/Linux compatibility. No CLI command launches or focuses a game.

The first real CLI attempt in this development session used only the shadow jar on its classpath and stopped at `NoClassDefFoundError: javafx/beans/property/ReadOnlyObjectProperty`, before Minecraft/NeoForge installation. It was not retried. Its failed attempt is not installation, update or repair proof; the corrected prerequisite/command above is supplied for a future authorized run.
