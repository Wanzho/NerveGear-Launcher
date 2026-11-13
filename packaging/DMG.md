# macOS install images

Run packaging with a Python 3.11+ virtual environment containing the pinned tools:

```sh
python3 -m venv /path/to/venv
/path/to/venv/bin/pip install --require-hashes -r packaging/dmg-requirements.txt
/path/to/venv/bin/python packaging/package.py --output /path/to/output --toolchain-cache /path/to/cache --jar /path/to/launcher.jar
```

Use `--skip-signing` when signing is prohibited. It skips the existing ad-hoc
codesign step and records that decision in the codesign evidence files. Existing
runtime signatures and the compiler's native output are not stripped or altered.

`build_dmg.py` uses dmgbuild's non-browsing mounts and direct `.DS_Store` writes.
It combines the supplied 660×548 and 1320×1096 PNGs with
`tiffutil -cathidpicheck`. Its hooks move the TIFF into `.background/launcher.tiff`
and regenerate the background alias before conversion to UDZO. No Finder,
AppleScript, launcher execution, or GUI automation is used.

The volume is `NerveGear启动器`. The stored window is 660×594 at (100, 100), with
128-point icons at (170, 187) and (490, 187), bottom labels in 13-point text, and
stored settings hiding the toolbar, sidebar, status bar, path bar, and tabs.
Finder may override the path-bar setting globally; the taller frame leaves about
520 points of picture with the path bar and 547 without it. The v5 artwork includes
drag-to-install and System Settings → Privacy & Security → 仍要打开 instructions. `Applications` links to
`/Applications`. Packaging retains `hdiutil verify` and per-target evidence logs.

Read back the final images without opening windows:

```sh
/path/to/venv/bin/python packaging/verify_dmg.py /path/to/arm64.dmg /path/to/x64.dmg
```

The verifier attaches with `-nobrowse -readonly -mountpoint`, checks the symlink,
layout, background alias and file identity, reports TIFF representations, and
detaches in a `finally` block. Disk-image operations may need permissions outside
a restricted filesystem sandbox. Headless metadata verification does not claim
a visual Finder preview or an application launch test.
