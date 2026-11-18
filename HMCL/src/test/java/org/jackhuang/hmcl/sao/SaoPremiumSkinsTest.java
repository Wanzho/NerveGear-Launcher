/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

/// Synthetic profile, file and account lifecycle fixtures without sockets, credentials or JavaFX toolkit.
@NotNullByDefault
class SaoPremiumSkinsTest {
    /// Isolated launcher and official-library roots.
    @TempDir Path root;
    /// Creates distinct standard-size images.
    private static byte[] png(int color) throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB); image.setRGB(0, 0, color);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return output.toByteArray();
    }

    /// Builds the actual object-keyed official format; modelImage is deliberately irrelevant.
    private static String localJson(byte[] png) {
        JsonObject entry = new JsonObject(); entry.addProperty("name", "Fixture"); entry.addProperty("slim", true);
        entry.addProperty("skinImage", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        entry.addProperty("modelImage", "not a skin");
        JsonObject skins = new JsonObject(); skins.add("skin_1", entry);
        JsonObject document = new JsonObject(); document.add("customSkins", skins); return document.toString();
    }

    /// Reads valid PNG data and leaves the source bytes unchanged; rejects malformed, oversized and non-PNG input.
    @Test void localFixtures() throws Exception {
        Path file = root.resolve("launcher_custom_skins.json"); assertTrue(SaoPremiumSkins.local(file).isEmpty());
        String json = localJson(png(0xff123456)); Files.writeString(file, json);
        var entries = SaoPremiumSkins.local(file); assertEquals(1, entries.size()); assertTrue(entries.get(0).slim());
        assertEquals("Fixture", entries.get(0).name()); assertEquals(json, Files.readString(file));
        Files.writeString(file, "{"); assertThrows(IOException.class, () -> SaoPremiumSkins.local(file));
        Files.writeString(file, localJson(new byte[33])); assertThrows(IOException.class, () -> SaoPremiumSkins.local(file));
        Files.write(file, new byte[SaoPremiumSkins.FILE_LIMIT + 1]); assertThrows(IOException.class, () -> SaoPremiumSkins.local(file));
    }

    /// ACTIVE and other states both download with model preservation and no bearer at texture hosts.
    @Test void allProfileSkins() throws Exception {
        String url = "https://textures.minecraft.net/texture/";
        String json = "{\"skins\":[{\"state\":\"ACTIVE\",\"variant\":\"SLIM\",\"url\":\"" + url + "a".repeat(64)
                + "\"},{\"state\":\"INACTIVE\",\"variant\":\"CLASSIC\",\"url\":\"" + url + "b".repeat(64) + "\"}]}";
        var entries = SaoMicrosoftSkin.library("fixture", (uri, bearer) -> {
            if (uri.getHost().equals("api.minecraftservices.com")) { assertEquals("fixture", bearer); return json.getBytes(StandardCharsets.UTF_8); }
            assertNull(bearer); return png(uri.getPath().endsWith("a") ? 0xff123456 : 0xffabcdef);
        });
        assertEquals(2, entries.size()); assertTrue(entries.get(0).slim()); assertFalse(entries.get(1).slim());
        assertThrows(IOException.class, () -> SaoMicrosoftSkin.library("fixture", (uri, bearer) -> new byte[SaoMicrosoftSkin.LIMIT + 1]));
        assertThrows(IOException.class, () -> SaoMicrosoftSkin.library("fixture", (uri, bearer) -> json.replace("textures.minecraft.net", "evil.test").getBytes(StandardCharsets.UTF_8)));
    }

    /// A failed profile source still imports the read-only local library.
    @Test void independentSourcesAndDedupe() throws Exception {
        Path file = root.resolve("official.json"); byte[] image = png(0xff123456); Files.writeString(file, localJson(image));
        var entries = SaoPremiumSkins.load("fixture", file, (uri, bearer) -> { throw new IOException("offline"); });
        var doubled = new ArrayList<>(entries); doubled.addAll(entries);
        var cached = SaoPremiumSkins.cache(root, doubled); assertEquals(1, cached.size()); assertTrue(cached.get(0).premium());
        SaoData.SavedSkin own = new SaoOathSkins(root).save("Own", image, false);
        assertEquals(cached, SaoPremiumSkins.merge(List.of(own), cached)); assertTrue(Files.exists(own.file()));
    }

    /// Resolves all three official defaults without accessing those directories.
    @Test void paths() {
        assertEquals(Path.of("/app/.minecraft/launcher_custom_skins.json"), SaoPremiumSkins.officialFile("Windows 11", root, "/app"));
        assertEquals(root.resolve("Library/Application Support/minecraft/launcher_custom_skins.json"), SaoPremiumSkins.officialFile("Mac OS X", root, null));
        assertEquals(root.resolve(".minecraft/launcher_custom_skins.json"), SaoPremiumSkins.officialFile("Linux", root, null));
    }

    /// Exercises real account snapshot publication, deletion guard, non-premium hiding and logout clearing without a session.
    @Test void accountLifecycle() throws Exception {
        var entry = new SaoPremiumSkins.Entry("Fixture", png(0xff123456), true);
        try (var accounts = new SaoOathAccounts(root, () -> URI.create("http://127.0.0.1:1/"), () -> "en",
                new SaoOathCredentialStore(root, false), new SaoOathAccounts.PremiumProof() {
                    /// Interactive login is forbidden by this fixture.
                    public org.jackhuang.hmcl.auth.AuthInfo session() { throw new AssertionError("No login"); }

                    /// Supplies only synthetic public pixels.
                    public @Unmodifiable List<SaoPremiumSkins.Entry> library(@Nullable String name) { return List.of(entry); }
                }, account -> fail("No launch"), error -> fail("No popup"))) {
            var current = SaoOathAccounts.class.getDeclaredField("current"); current.setAccessible(true);
            var refresh = SaoOathAccounts.class.getDeclaredMethod("refreshPremiumLibrary", long.class); refresh.setAccessible(true);
            var publish = SaoOathAccounts.class.getDeclaredMethod("publishState"); publish.setAccessible(true);
            current.set(accounts, new SaoData.OathAccount("Fixture", "1".repeat(32), "", true, false, 0, null));
            refresh.invoke(accounts, 0L); publish.invoke(accounts); assertTrue(accounts.savedSkins().isEmpty());
            current.set(accounts, new SaoData.OathAccount("Fixture", "1".repeat(32), "", true, false, 0, "Fixture"));
            refresh.invoke(accounts, 0L); publish.invoke(accounts); assertEquals(1, accounts.savedSkins().size());
            var saved = accounts.savedSkins().get(0); accounts.deleteSavedSkin(saved); assertTrue(Files.exists(saved.file()));
            assertEquals(1, accounts.savedSkins().size());
            accounts.logout(); assertTrue(accounts.savedSkins().isEmpty());
            refresh.invoke(accounts, 0L); publish.invoke(accounts); assertTrue(accounts.savedSkins().isEmpty());
        }
    }
}
