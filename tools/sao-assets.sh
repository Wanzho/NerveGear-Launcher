#!/bin/zsh
# Copies the SAO screen's assets into the build from the team's own folders. They are NOT committed:
# the SAO UI fonts carry no licence record, the title music is the anime's, the wallpapers and logo are the pack's.
# Usage: tools/sao-assets.sh   (run before building; the folder is in .gitignore)
set -e
here=${0:A:h:h}
out=$here/HMCL/src/main/resources/assets/sao-launcher
mod=${SAO_MOD_TREE:-$HOME/Developer/sao-neoforge120-serverlist}
fonts=${SAO_FONT_DIR:-"/Users/James/Documents/SAO Interface/sao-interface/tools/sao-reference-assets/sao-assets-master/fonts/SAO_Fonts_v4.000/TTF"}
jar=${SAO_PACKUI_JAR:-$mod/forge120-full/build/libs/sao_interface_packui-0.8-mppage2.jar}
tex=$mod/forge120-full/src/packui/resources/assets/sao/textures/entrance
rm -rf "$out"; mkdir -p "$out"/{fonts,wallpapers,music,lyrics}
cp "$fonts"/SAOUITT-Regular.ttf "$fonts"/SAOUITT-Bold.ttf "$fonts"/SAOWelcomeTT-Regular.ttf "$out/fonts/"
for f in "$tex"/wallpaper_??.jpg; do sips -Z 1920 "$f" --out "$out/wallpapers/${f:t}" >/dev/null; done
# The title logo is James's pick of 9 Nov, logo-45, copied byte for byte from the logo folder.
logo=${SAO_LOGO:-"/Users/James/Desktop/Claude Workspace/SAO logo v7/game/logo版本/logo-45-域斜钩顺弧加长-钩不转(44).png"}
cp "$logo" "$out/logo.png"
cp "$mod/forge120-full/src/packui/resources/assets/sao/lyrics/title.lrc" "$out/lyrics/title.lrc"
unzip -p "$jar" assets/sao/sounds/entrance/title_crossing_field.ogg > "$out/music/title.ogg"
for k in intro loop outro; do cp "$mod/forge120-full/src/packui/resources/assets/sao/sounds/entrance_helmet_$k.ogg" "$out/music/helmet_$k.ogg"; done
ls "$out/wallpapers" | wc -l | xargs echo wallpapers:
du -sh "$out"
