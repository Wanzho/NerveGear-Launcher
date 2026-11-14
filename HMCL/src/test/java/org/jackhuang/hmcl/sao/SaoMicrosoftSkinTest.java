/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Pure in-memory Microsoft/ARGUS fixtures; no login, sockets, toolkit or game.
@NotNullByDefault
class SaoMicrosoftSkinTest {
    /// Public official texture fixture URL.
    private static final String URL = "https://textures.minecraft.net/texture/" + "a".repeat(64);
    /// Encodes a small synthetic image.
    private static byte[] png(int height) throws IOException {
        BufferedImage image = new BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(4, 20, 0xff123456);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output); return output.toByteArray();
    }
    /// Fixtures an inactive first skin, active model and active cape, asserting bearer isolation.
    private static SaoMicrosoftSkin.Skin skin(String variant) throws Exception {
        return Objects.requireNonNull(SaoMicrosoftSkin.fetch("fixture-secret", (uri, bearer) -> {
            if (uri.getHost().equals("api.minecraftservices.com")) {
                assertEquals("fixture-secret", bearer);
                return ("{\"skins\":[{\"state\":\"INACTIVE\",\"url\":\"https://bad.example/skin\"},"
                        + "{\"state\":\"ACTIVE\",\"url\":\"" + URL + "\",\"variant\":\"" + variant
                        + "\"}],\"capes\":[{\"state\":\"ACTIVE\",\"url\":\"" + URL + "\"}]}").getBytes(StandardCharsets.UTF_8);
            }
            assertNull(bearer); assertEquals(URI.create(URL), uri); return png(64);
        }));
    }
    /// Records server effects and verifies the preview existed before the first server read.
    @NotNullByDefault
    private static final class Target implements SaoMicrosoftSkin.Target {
        /// Fixture slots.
        private final Map<String, SaoTextureState.Image> slots = new HashMap<>();
        /// Ordered server effects.
        private final List<String> calls = new ArrayList<>();
        /// Preview publication marker.
        private boolean preview;
        /// Captured upload model.
        private boolean slim;
        /// Remaining cooldown seconds.
        private long cooldown;
        /// Optional state failure.
        private boolean fail;
        /// Adds an available image slot.
        void slot(String key) { slots.put(key, new SaoTextureState.Image("a".repeat(64), "", URI.create(URL))); }
        /// Returns authoritative fixture state after preview publication.
        public SaoTextureState state() throws IOException {
            assertTrue(preview); calls.add("state"); if (fail) throw new IOException("fixture");
            return new SaoTextureState(true,"own","own","own","none",slots,0,cooldown,"idle");
        }
        /// Records source selection.
        public void premium() { calls.add("premium"); }
        /// Records validated upload and model.
        public void upload(byte[] png, boolean model) throws Exception {
            assertEquals(64, Objects.requireNonNull(ImageIO.read(new ByteArrayInputStream(png))).getHeight());
            calls.add("upload"); slim = model;
        }
        /// Records inheritance request.
        public void refresh() { calls.add("refresh"); }
        /// Exercises the production policy with a fixture preview callback.
        void sync(SaoMicrosoftSkin.Skin skin) throws Exception { SaoMicrosoftSkin.sync(skin, () -> preview = true, this); }
    }
    /// Premium availability selects its source without replacing an existing own slot.
    @Test void premiumAvailable() throws Exception {
        Target target = new Target(); target.slot("premiumSkin"); target.slot("ownSkin"); target.sync(skin("CLASSIC"));
        assertEquals(List.of("state", "premium"), target.calls);
    }
    /// Missing slots upload the active slim skin, then ask for cape inheritance.
    @Test void uploadWhenEmptyAndSlim() throws Exception {
        Target target = new Target(); SaoMicrosoftSkin.Skin skin = skin("SLIM"); target.sync(skin);
        assertTrue(target.slim); assertEquals(URI.create(URL), skin.cape());
        assertEquals(List.of("state", "upload", "refresh"), target.calls);
    }
    /// Existing own skins are never uploaded over, and manual cooldown is honored.
    @Test void keepExistingOwnAndCooldown() throws Exception {
        Target target = new Target(); target.slot("ownSkin"); target.cooldown = 60; target.sync(skin("CLASSIC"));
        assertEquals(List.of("state"), target.calls);
    }
    /// Unknown server slots cannot cause an upload, while the local preview remains available.
    @Test void failureKeepsPreviewWithoutUpload() throws Exception {
        Target target = new Target(); target.fail = true;
        assertThrows(IOException.class, () -> target.sync(skin("CLASSIC")));
        assertTrue(target.preview); assertEquals(List.of("state"), target.calls);
        assertThrows(IOException.class, () -> SaoMicrosoftSkin.fetch("fixture", (uri, bearer) -> { throw new IOException("offline"); }));
    }
    /// No active custom skin leaves the existing appearance unchanged and downloads nothing else.
    @Test void noSkin() throws Exception {
        assertNull(SaoMicrosoftSkin.fetch("fixture", (uri, bearer) -> {
            assertNotNull(bearer); return "{\"skins\":[],\"capes\":[]}".getBytes(StandardCharsets.UTF_8);
        }));
    }
    /// Rejects spoofed hosts, redirect-style paths, alternate ports, oversized and invalid PNGs.
    @Test void rejectsHostSizeAndPng() throws Exception {
        for (String value : List.of(URL.replace("textures.minecraft.net", "textures.minecraft.net.evil.test"),
                URL.replace(".net", ".net:443"), URL + "?redirect=x", URL.replace("/texture/", "/texture/../")))
            assertThrows(IOException.class, () -> SaoMicrosoftSkin.texture(value));
        assertThrows(IllegalArgumentException.class, () -> SaoMicrosoftSkin.normalize(new byte[SaoMicrosoftSkin.LIMIT + 1]));
        assertThrows(IllegalArgumentException.class, () -> SaoMicrosoftSkin.normalize(new byte[33]));
        assertThrows(IOException.class, () -> SaoMicrosoftSkin.fetch("fixture", (uri, bearer) -> new byte[SaoMicrosoftSkin.LIMIT + 1]));
        assertEquals(URI.create(URL), SaoMicrosoftSkin.texture(URL.replace("https:", "http:")));
    }
    /// Legacy conversion mirrors limb faces into the 64-square upload layout.
    @Test void legacyConversion() throws Exception {
        BufferedImage image = Objects.requireNonNull(ImageIO.read(new ByteArrayInputStream(SaoMicrosoftSkin.normalize(png(32)))));
        assertEquals(64, image.getHeight()); assertEquals(0xff123456, image.getRGB(23, 52));
    }
}
