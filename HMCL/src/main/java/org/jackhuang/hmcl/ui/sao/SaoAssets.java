/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.scene.image.Image;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/// Loads the SAO screen's fonts, wallpapers, logo, music and lyrics from `/assets/sao-launcher`.
///
/// The assets are copied in at build time by `tools/sao-assets.sh` and are not in the public repo; every getter
/// copes with them missing (system fonts, a plain gradient, no music, no lyrics), so a bare build still runs.
@NotNullByDefault
public final class SaoAssets {
    /// The resource folder holding every SAO asset.
    private static final String ROOT = "/assets/sao-launcher/";
    /// Full name of the SAO UI regular face once loaded, or null.
    private static final @Nullable String UI_REGULAR;
    /// Full name of the SAO UI bold face once loaded, or null.
    private static final @Nullable String UI_BOLD;
    /// Full name of the SAO welcome face (the "Welcome to Sword Art Online!" lettering), or null.
    private static final @Nullable String WELCOME;
    /// Family name of the SAO UI face, or "System".
    private static final String SAO_FAMILY;

    static {
        @Nullable Font regular = load("fonts/SAOUITT-Regular.ttf");
        @Nullable Font bold = load("fonts/SAOUITT-Bold.ttf");
        @Nullable Font welcome = load("fonts/SAOWelcomeTT-Regular.ttf");
        UI_REGULAR = regular == null ? null : regular.getName();
        UI_BOLD = bold == null ? UI_REGULAR : bold.getName();
        WELCOME = welcome == null ? UI_REGULAR : welcome.getName();
        SAO_FAMILY = regular == null ? "System" : regular.getFamily();
    }

    /// Not instantiable.
    private SaoAssets() {
    }

    /// Registers one font file with JavaFX.
    ///
    /// @param path the path under [#ROOT]
    /// @return the loaded font, or null when the file is missing or unreadable
    private static @Nullable Font load(String path) {
        try (@Nullable InputStream in = SaoAssets.class.getResourceAsStream(ROOT + path)) {
            if (in == null) return null;
            return Font.loadFont(in, 12);
        } catch (IOException e) {
            return null;
        }
    }

    /// The SAO UI face for Latin letters and digits (James: "SAO font universal"); CJK falls back to the system face.
    ///
    /// @param size the size in points
    /// @param bold whether to use the bold cut
    /// @return the font
    public static Font sao(double size, boolean bold) {
        @Nullable String name = bold ? UI_BOLD : UI_REGULAR;
        // Looked up by full face name: family + weight lookups can miss fonts loaded from a stream.
        return name != null ? new Font(name, size) : Font.font("System", bold ? FontWeight.BOLD : FontWeight.NORMAL, size);
    }

    /// The wide SAO welcome lettering used for "Link Start".
    ///
    /// @param size the size in points
    /// @return the font
    public static Font welcome(double size) {
        return WELCOME != null ? new Font(WELCOME, size) : Font.font("System", size);
    }

    /// The family name of the SAO UI face, for CSS.
    ///
    /// @return the family name, or "System" when the font is missing
    public static String saoFamily() {
        return SAO_FAMILY;
    }

    /// Opens an asset image in the background.
    ///
    /// @param path the path under [#ROOT]
    /// @return the image, or null when missing
    public static @Nullable Image image(String path) {
        @Nullable var url = SaoAssets.class.getResource(ROOT + path);
        return url == null ? null : new Image(url.toExternalForm(), true);
    }

    /// The paths of every wallpaper that exists, in order.
    ///
    /// @return wallpaper paths under [#ROOT]
    public static @Unmodifiable List<String> wallpapers() {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            String p = String.format("wallpapers/wallpaper_%02d.jpg", i);
            if (SaoAssets.class.getResource(ROOT + p) != null) out.add(p);
        }
        return List.copyOf(out);
    }

    /// Opens the title music (Ogg Vorbis).
    ///
    /// @return a fresh stream, or null when the music is not built in
    public static @Nullable InputStream music() {
        return SaoAssets.class.getResourceAsStream(ROOT + "music/title.ogg");
    }

    /// Reads the bilingual title lyrics.
    ///
    /// @return the LRC text, or an empty string when missing
    public static String lyrics() {
        try (@Nullable InputStream in = SaoAssets.class.getResourceAsStream(ROOT + "lyrics/title.lrc")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
