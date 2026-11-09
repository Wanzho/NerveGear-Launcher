# Explicit redistribution allow-list bundling

The headless tool never decides licences. A reviewer supplies exact, evidence-backed approvals for unmodified binaries. Unknown or forbidden mods stay as index downloads. The source ZIP is opened read-only; the output must be a new filename.

```sh
export JAVA_TOOL_OPTIONS=-Djava.awt.headless=true
java -Djava.awt.headless=true --add-modules=javafx.controls -jar launcher.jar \
  --bundle-allowed input.zip allow-list.json output.zip
java -Djava.awt.headless=true -jar launcher.jar --pack-hash output.zip
```

Use the existing Java 21 Full runtime, or supply the JavaFX base/graphics/controls dependencies on the classpath. The command is intercepted before launcher storage, dialogs, logging setup or JavaFX initialization. It never launches Minecraft or opens a browser.

The allow-list JSON format is:

```json
{
  "files": [
    {
      "path": "mods/example.jar",
      "sha1": "0123456789012345678901234567890123456789",
      "verdict": "allowed with attribution",
      "projectUrl": "https://www.curseforge.com/minecraft/mc-mods/example",
      "license": "MIT",
      "notice": "Exact required copyright and permission notice, plus any source location."
    }
  ],
  "extraFiles": [
    {
      "path": "overrides/licenses/example/source.zip",
      "localPath": "/absolute/path/to/reviewed-source.zip",
      "sha256": "0123456789012345678901234567890123456789012345678901234567890123"
    }
  ]
}
```

`verdict` must be exactly `allowed` or `allowed with attribution`. The latter requires nonempty `notice`. The SHA-1 must match the actual original index row. Each selected path must be a unique, external `mods/*.jar`, with no conflicting override. Approvals are all validated before network requests. Evidence fields may accompany approvals but do not relax these checks. Free/noncommercial or other usage restrictions remain binding; including a jar does not grant broader uses.

Optional `extraFiles` supply reviewed matching Corresponding Source and full licence texts when required. Each must be a new member under `overrides/licenses/`, with a regular local file, bounded size and matching SHA-256. Saved ZIP bytes are rehashed before publication. These sources are explicitly reviewed artifacts, not downloaded automatically from licence names or guessed repository tags.

Allowed jars use the same six-worker, Modrinth-first downloader, SHA-1/size verification, stall fallback and retry rules as the installer. This tool's redirects are restricted to the Modrinth, mediafilez and edge CDNs. All payloads are staged; corrupt downloads, missing attribution or changed source artifacts leave no output or partial jar.

The saved archive adds approved jars at `overrides/mods/`, source/licence artifacts and `overrides/SAO-BUNDLED-MODS-NOTICE.txt`. Only approved file rows are removed from `modrinth.index.json`; other JSON fields/remaining rows (including hashes) are retained. All original non-index member payloads are copied unchanged; compression/container bytes can change during repackaging. Existing output files are never overwritten. The complete saved archive is parsed again through the installer before publication.
