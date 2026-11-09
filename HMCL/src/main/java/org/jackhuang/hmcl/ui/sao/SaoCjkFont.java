/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Makes every Chinese character the launcher draws use mainland Simplified forms.
///
/// James, 8 Nov 2026: "你看到"置"了吗？这个不是大陆用的简体笔画…就是日语那种". JavaFX fills characters a font lacks
/// from a fixed list. On macOS the list for the SAO face goes Helvetica, Lucida Grande, Geeza Pro, then Hiragino Sans
/// W3, a Japanese font, so 置 and 直 came out in Japanese strokes; the PingFang SC entry further down points at
/// `/System/Library/Fonts/PingFang.ttc`, which newer macOS keeps elsewhere. On Windows the list follows Tahoma's
/// font link, which starts with MS Gothic on non-Chinese installs.
///
/// This puts a Simplified Chinese face first in those lists: PingFang SC (Hiragino Sans GB when PingFang is missing)
/// on macOS, Microsoft YaHei on Windows. It reaches into JavaFX's font internals (`com.sun.javafx.font`, opened in
/// the jar's `Add-Opens`); when anything there differs it logs and leaves JavaFX as it is.
@NotNullByDefault
public final class SaoCjkFont {
    /// The regular Simplified face, or null when none was found.
    private static @Nullable Object regular;
    /// The bold Simplified face.
    private static @Nullable Object bold;
    /// Whether [#install()] ran.
    private static boolean installed;
    /// Whether [#install()] put a Simplified face first everywhere.
    private static boolean active;
    /// The fallback lists already patched or already in use; composite fonts live as long as JavaFX does.
    private static final Set<Object> DONE = Collections.newSetFromMap(new IdentityHashMap<>());

    /// Not instantiable.
    private SaoCjkFont() {
    }

    /// Puts the Simplified face first in every fallback list, now and for fonts made later. Call on the JavaFX
    /// thread before the first window is shown; later calls do nothing.
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("mac")) {
                @Nullable Path ttc = pingFang();
                if (ttc != null) {
                    Font.loadFonts(ttc.toUri().toString(), 12);
                    regular = face("PingFang SC Regular");
                    bold = face("PingFang SC Semibold");
                }
                if (regular == null) {
                    regular = face("Hiragino Sans GB W3");
                    bold = face("Hiragino Sans GB W6");
                }
            } else if (os.contains("win")) {
                regular = face("Microsoft YaHei UI");
                if (regular == null) regular = face("Microsoft YaHei");
                bold = face("Microsoft YaHei UI Bold");
                if (bold == null) bold = face("Microsoft YaHei Bold");
            }
            if (regular == null) {
                LOG.info("SAO: no Simplified Chinese face found, Chinese keeps JavaFX's own fallback");
                return;
            }
            if (bold == null) bold = regular;

            // JavaFX keeps one composite font per name and fills in its fallback list only after caching it, so
            // make the launcher's faces now, then patch every cached one; the hooked cache patches any font made
            // later the next time it is looked up, before its first character is mapped.
            for (FontWeight w : FontWeight.values())
                for (FontPosture p : FontPosture.values()) {
                    Font.font(SaoAssets.saoFamily(), w, p, 12);
                    Font.font("System", w, p, 12);
                }
            SaoAssets.sao(12, false);
            SaoAssets.sao(12, true);
            SaoAssets.welcome(12);

            Object factory = factory();
            Field map = field(Class.forName("com.sun.javafx.font.PrismFontFactory"), "compResourceMap");
            synchronized (factory) {
                @SuppressWarnings("unchecked")
                HashMap<Object, Object> old = (HashMap<Object, Object>) map.get(factory);
                for (Object comp : old.values()) patch(comp);
                map.set(factory, new Hooked(old));
            }

            // The logical families (System, SansSerif…) keep their own lists; make each now and patch it.
            Class<?> logical = Class.forName("com.sun.javafx.font.LogicalFont");
            Method get = logical.getDeclaredMethod("getLogicalFont", String.class, boolean.class, boolean.class, float.class);
            get.setAccessible(true);
            Method resource = Class.forName("com.sun.javafx.font.PGFont").getMethod("getFontResource");
            for (String family : new String[]{"System", "SansSerif", "Serif", "Monospaced"})
                for (int style = 0; style < 4; style++)
                    patch(resource.invoke(get.invoke(null, family, (style & 1) != 0, (style & 2) != 0, 12f)));

            active = true;
            LOG.info("SAO: Chinese drawn in " + name(regular) + " / " + name(bold));
        } catch (Throwable e) {
            LOG.warning("SAO: could not set the Simplified Chinese fallback", e);
        }
    }

    /// Whether [#install()] ran and put a Simplified Chinese face first (screenshot tools fail without it).
    ///
    /// @return true once patched
    public static synchronized boolean active() {
        return active;
    }

    /// Finds PingFang's file: macOS 15 and later keep it in a font asset folder.
    ///
    /// @return the file, or null
    private static @Nullable Path pingFang() {
        Path old = Paths.get("/System/Library/Fonts/PingFang.ttc");
        if (Files.isRegularFile(old)) return old;
        Path assets = Paths.get("/System/Library/AssetsV2");
        if (!Files.isDirectory(assets)) return null;
        try (DirectoryStream<Path> fonts = Files.newDirectoryStream(assets, "com_apple_MobileAsset_Font*")) {
            for (Path set : fonts) {
                try (DirectoryStream<Path> items = Files.newDirectoryStream(set, "*.asset")) {
                    for (Path item : items) {
                        Path ttc = item.resolve("AssetData").resolve("PingFang.ttc");
                        if (Files.isRegularFile(ttc)) return ttc;
                    }
                }
            }
        } catch (IOException | SecurityException e) {
            LOG.warning("SAO: could not look for PingFang", e);
        }
        return null;
    }

    /// The factory JavaFX makes its fonts with.
    ///
    /// @return the factory
    /// @throws ReflectiveOperationException when JavaFX's internals differ
    private static Object factory() throws ReflectiveOperationException {
        Method m = Class.forName("com.sun.javafx.font.PrismFontFactory").getDeclaredMethod("getFontFactory");
        m.setAccessible(true);
        return m.invoke(null);
    }

    /// One installed or loaded face by its full name.
    ///
    /// @param fullName the face's full name
    /// @return the face's font resource, or null when that face is not there
    private static @Nullable Object face(String fullName) {
        try {
            Object factory = factory();
            Method m = Class.forName("com.sun.javafx.font.PrismFontFactory")
                    .getDeclaredMethod("getFontResource", String.class, String.class, boolean.class);
            m.setAccessible(true);
            @Nullable Object res = m.invoke(factory, fullName, null, false);
            // An unknown name comes back as some other face; only take the one asked for.
            return res != null && fullName.equalsIgnoreCase(name(res)) ? res : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /// A font resource's full name.
    ///
    /// @param res the resource
    /// @return its name, or "?"
    private static String name(Object res) {
        try {
            Method m = Class.forName("com.sun.javafx.font.FontResource").getMethod("getFullName");
            return Objects.toString(m.invoke(res));
        } catch (Throwable e) {
            return "?";
        }
    }

    /// Puts the Simplified face first in one composite font's fallback list.
    ///
    /// @param comp a composite font: one made from a physical face, a fallback list or a logical family
    private static void patch(@Nullable Object comp) {
        if (comp == null) return;
        try {
            @Nullable Field inner = maybeField(comp.getClass(), "fallbackResource");
            if (inner != null) {
                // Null while JavaFX is still making this font.
                patch(inner.get(comp));
                return;
            }
            synchronized (DONE) {
                if (!DONE.add(comp)) return;
            }
            Class<?> c = comp.getClass();
            @Nullable Field names = maybeField(c, "linkedFontNames");
            if (names == null) return;
            Field mapper = field(c, "mapper");
            // Text already shaped with this font holds slot numbers; shifting them would draw the wrong glyphs.
            if (mapper.get(comp) != null) {
                LOG.info("SAO: a font was used before its Chinese fallback could be set");
                return;
            }
            Method linked = c.getDeclaredMethod("getLinkedFonts");
            linked.setAccessible(true);
            linked.invoke(comp);
            Field files = field(c, "linkedFontFiles");
            Field fonts = field(c, "fallbacks");
            boolean isBold = field(c, "isBold").getBoolean(comp);
            Object face = Objects.requireNonNull(isBold ? bold : regular);
            String faceName = name(face);
            String[] n = (String[]) names.get(comp);
            if (n != null && n.length > 0 && faceName.equalsIgnoreCase(n[0])) return;
            String faceFile = Objects.toString(Class.forName("com.sun.javafx.font.FontResource").getMethod("getFileName").invoke(face), "");
            names.set(comp, prepend(n, faceName, String.class));
            files.set(comp, prepend((String[]) files.get(comp), faceFile, String.class));
            Object[] f = (Object[]) fonts.get(comp);
            fonts.set(comp, prepend(f, face, f == null ? Class.forName("com.sun.javafx.font.FontResource") : f.getClass().getComponentType()));
        } catch (Throwable e) {
            LOG.warning("SAO: could not patch a font fallback list", e);
        }
    }

    /// A copy of an array with one entry put in front.
    ///
    /// @param a     the array, or null for none
    /// @param first the new first entry
    /// @param type  the entry type
    /// @param <T>   the entry type
    /// @return the new array
    @SuppressWarnings("unchecked")
    private static <T> T[] prepend(@Nullable T[] a, Object first, Class<?> type) {
        int len = a == null ? 0 : a.length;
        T[] out = (T[]) Array.newInstance(type, len + 1);
        out[0] = (T) first;
        if (a != null) System.arraycopy(a, 0, out, 1, len);
        return out;
    }

    /// A declared field, made accessible.
    ///
    /// @param c    the class
    /// @param name the field
    /// @return the field
    /// @throws NoSuchFieldException when it is not there
    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /// A declared field, made accessible, or null.
    ///
    /// @param c    the class
    /// @param name the field
    /// @return the field, or null
    private static @Nullable Field maybeField(Class<?> c, String name) {
        try {
            return field(c, name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /// JavaFX's cache of composite fonts, patching each font as JavaFX adds it.
    @SuppressWarnings("rawtypes")
    private static final class Hooked extends HashMap {
        /// Takes over the existing cache.
        ///
        /// @param old the cache so far
        @SuppressWarnings("unchecked")
        Hooked(Map<?, ?> old) {
            super(old);
        }

        @Override
        public Object get(Object key) {
            @Nullable Object comp = super.get(key);
            patch(comp);
            return comp;
        }
    }
}
