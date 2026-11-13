#!/usr/bin/env python3
"""Read back a DMG's install layout on a private, read-only, non-browsing mount."""

import hashlib
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

from ds_store import DSStore
from mac_alias import Alias


def verify(image):
    """Assert the layout and background alias, always detaching after inspection."""
    with tempfile.TemporaryDirectory(prefix="launcher-dmg-check-") as directory:
        mount = Path(directory) / "volume"
        mount.mkdir()
        subprocess.run(["hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", mount, image], check=True)
        try:
            assert os.readlink(mount / "Applications") == "/Applications"
            assert (mount / "NerveGear启动器.app/Contents/Info.plist").is_file()
            app = mount / "NerveGear启动器.app"
            subprocess.run(["codesign", "--verify", "--deep", "--strict", "--verbose=2", app], check=True)
            signature = subprocess.check_output(["codesign", "-dv", "--verbose=4", app],
                                                stderr=subprocess.STDOUT, text=True)
            assert "Signature=adhoc" in signature, signature
            print(signature, flush=True)
            background = mount / ".background/launcher.tiff"
            assert background.is_file()
            with DSStore.open(str(mount / ".DS_Store"), "r") as store:
                window = store["."]["bwsp"]
                icons = store["."]["icvp"]
                view = store["."]["icvl"]
                positions = {name: store[name]["Iloc"] for name in ("NerveGear启动器.app", "Applications")}
            assert window["WindowBounds"] == "{{100, 100}, {660, 530}}"
            for key in ("ShowToolbar", "ShowStatusBar", "ShowSidebar", "ContainerShowSidebar", "ShowTabView", "ShowPathbar"):
                assert window[key] is False, key
            assert view == (b"type", b"icnv"), view
            assert positions == {"NerveGear启动器.app": (170, 187), "Applications": (490, 187)}, positions
            assert icons["iconSize"] == 128 and icons["textSize"] == 13
            assert icons["labelOnBottom"] is True and icons["arrangeBy"] == "none"
            assert icons["backgroundType"] == 2
            alias = Alias.from_bytes(icons.pop("backgroundImageAlias"))
            assert alias.target.posix_path == "/.background/launcher.tiff", alias.target.posix_path
            assert alias.volume.name == "NerveGear启动器", alias.volume.name
            assert alias.target.cnid == background.stat().st_ino
            tiff_info = subprocess.check_output(["tiffutil", "-info", background], stderr=subprocess.STDOUT, text=True)
            assert "Image Width: 660 Image Length: 498" in tiff_info
            assert "Image Width: 1320 Image Length: 996" in tiff_info
            assert "Resolution: 72, 72" in tiff_info and "Resolution: 144, 144" in tiff_info
            with image.open("rb") as stream:
                sha256 = hashlib.file_digest(stream, "sha256").hexdigest()
            print(json.dumps({"image": str(image), "sha256": sha256,
                              "Applications": os.readlink(mount / "Applications"), "window": window,
                              "icons": icons, "positions": positions, "view": str(view),
                              "background_alias": alias.target.posix_path, "volume": alias.volume.name,
                              "alias_file_id_matches": True, "codesign_deep_strict": "PASS (ad-hoc)", "tiff_info": tiff_info, "result": "PASS"},
                             ensure_ascii=False, indent=2), flush=True)
        finally:
            subprocess.run(["hdiutil", "detach", mount], check=True)


if __name__ == "__main__":
    for argument in sys.argv[1:]:
        verify(Path(argument).resolve())
