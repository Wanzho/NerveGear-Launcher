#!/usr/bin/env python3
"""Create the install layout using dmgbuild, without Finder or AppleScript."""

import json
import subprocess
import sys
from importlib.metadata import version
from pathlib import Path

import dmgbuild
from ds_store import DSStore
from mac_alias import Alias


def build(app, image, work):
    """Build UDZO with two HiDPI representations and a relocatable background alias."""
    for package, expected in (("dmgbuild", "1.6.7"), ("ds_store", "1.3.1"), ("mac_alias", "2.2.2")):
        if version(package) != expected:
            raise RuntimeError("Install the pinned packaging/dmg-requirements.txt: " + package)
    here = Path(__file__).resolve().parent
    background = work / "launcher-dmg-background.tiff"
    subprocess.run(["tiffutil", "-cathidpicheck", here / "launcher-dmg-background-v4.png",
                    here / "launcher-dmg-background-v4@2x.png", "-out", background], check=True)
    mounted = None

    def relocate_background(mount_point, options):
        """Move dmgbuild's root background into the requested hidden directory."""
        nonlocal mounted
        mounted = Path(mount_point)
        (mounted / ".background").mkdir()
        (mounted / ".background.tiff").rename(mounted / ".background/launcher.tiff")

    def event(info):
        """Retain build evidence and refresh the alias after dmgbuild writes its store."""
        print(json.dumps(info, default=str), flush=True)
        if info.get("type") == "operation::finished" and info.get("operation") == "dsstore::create":
            # This runs while the private writable image is still mounted. Recreate
            # the alias so both its file ID and path describe the final TIFF location.
            with DSStore.open(str(mounted / ".DS_Store"), "r+") as store:
                settings = store["."]["icvp"]
                settings["backgroundImageAlias"] = Alias.for_file(str(mounted / ".background/launcher.tiff")).to_bytes()
                store["."]["icvp"] = settings

    dmgbuild.build_dmg(str(image), "NerveGear启动器", lookForHiDPI=False, callback=event, settings={
        "format": "UDZO", "files": [str(app)], "symlinks": {"Applications": "/Applications"},
        "background": str(background), "window_rect": ((100, 100), (660, 594)),
        "default_view": "icon-view", "icon_locations": {app.name: (170, 187), "Applications": (490, 187)},
        "icon_size": 128, "text_size": 13, "label_pos": "bottom", "arrange_by": None,
        "show_toolbar": False, "show_status_bar": False, "show_sidebar": False,
        "show_pathbar": False, "show_tab_view": False, "sidebar_width": 0,
        "include_list_view_settings": False, "create_hook": relocate_background,
    })


if __name__ == "__main__":
    build(*(Path(value).resolve() for value in sys.argv[1:]))
