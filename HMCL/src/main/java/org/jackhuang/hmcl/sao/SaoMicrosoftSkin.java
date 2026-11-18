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
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Bounded Microsoft profile acquisition and conservative ARGUS synchronization, independent of the screen.
@NotNullByDefault
final class SaoMicrosoftSkin {
    /// Maximum encoded profile or image bytes; enforced while streaming as well as before decoding.
    static final int LIMIT = 1024 * 1024;
    /// Validated public cosmetics, containing no authentication material.
    @NotNullByDefault
    record Skin(byte @Unmodifiable [] png, boolean slim, @Nullable URI cape) { }
    /// Injectable transport; only the profile request receives a bearer.
    @NotNullByDefault
    interface Fetch {
        /// Returns bounded bytes or fails without exposing response bodies in diagnostics.
        byte[] get(URI uri, @Nullable String bearer) throws Exception;
    }

    /// ARGUS operations used after publishing the local preview.
    @NotNullByDefault
    interface Target {
        /// Reads authoritative slots; failure must never be interpreted as an empty own slot.
        SaoTextureState state() throws Exception;

        /// Selects premium without deleting the own slot.
        void premium() throws Exception;

        /// Uses the existing multipart skin upload path.
        void upload(byte[] png, boolean slim) throws Exception;

        /// Requests asynchronous inheritance, including capes.
        void refresh() throws Exception;
    }

    /// Utility class.
    private SaoMicrosoftSkin() { }

    /// Fetches only ACTIVE cosmetics from the authenticated Minecraft Services profile API.
    static @Nullable Skin fetch(String token, Fetch fetch) throws Exception {
        byte[] response = fetch.get(URI.create("https://api.minecraftservices.com/minecraft/profile"), token);
        if (response.length > LIMIT) throw new IOException("Profile too large");
        JsonObject profile = JsonParser.parseString(new String(response, StandardCharsets.UTF_8)).getAsJsonObject();
        @Nullable JsonObject active = active(profile, "skins");
        if (active == null) return null;
        String variant = active.get("variant").getAsString();
        if (!variant.equals("CLASSIC") && !variant.equals("SLIM")) throw new IOException("Invalid skin model");
        URI url = texture(active.get("url").getAsString());
        @Nullable JsonObject cape = active(profile, "capes");
        @Nullable URI capeUrl = cape == null ? null : texture(cape.get("url").getAsString());
        return new Skin(normalize(fetch.get(url, null)), variant.equals("SLIM"), capeUrl);
    }

    /// Fetches every profile skin regardless of state, with the same origin and image validation as the active skin.
    static java.util.@Unmodifiable List<SaoPremiumSkins.Entry> library(String token, Fetch fetch) throws Exception {
        byte[] bytes = fetch.get(URI.create("https://api.minecraftservices.com/minecraft/profile"), token);
        if (bytes.length > LIMIT) throw new IOException("Profile too large");
        JsonObject profile = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        java.util.List<SaoPremiumSkins.Entry> result = new java.util.ArrayList<>();
        if (!profile.has("skins")) return java.util.List.of();
        JsonArray skins = profile.getAsJsonArray("skins");
        if (skins.size() > SaoPremiumSkins.MAX_ENTRIES) throw new IOException("Too many skins");
        for (JsonElement element : skins) {
            JsonObject item = element.getAsJsonObject();
            String variant = item.get("variant").getAsString();
            if (!variant.equals("SLIM") && !variant.equals("CLASSIC")) throw new IOException("Invalid model");
            result.add(new SaoPremiumSkins.Entry("Microsoft " + (result.size() + 1),
                    normalize(fetch.get(texture(item.get("url").getAsString()), null)), variant.equals("SLIM")));
        }
        return java.util.List.copyOf(result);
    }

    /// Finds the active slot, never assuming the first item is active.
    private static @Nullable JsonObject active(JsonObject profile, String key) {
        if (!profile.has(key)) return null;
        for (JsonElement element : profile.getAsJsonArray(key)) {
            JsonObject item = element.getAsJsonObject();
            if (item.has("state") && "ACTIVE".equals(item.get("state").getAsString())) return item;
        }
        return null;
    }

    /// Allows only canonical official texture URLs; legacy HTTP references are upgraded before downloading.
    static URI texture(String value) throws IOException {
        URI uri = URI.create(value);
        if (!"textures.minecraft.net".equals(uri.getHost()) || uri.getPort() != -1 || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || !uri.getRawPath().matches("/texture/[a-fA-F0-9]{32,64}")) throw new IOException("Texture origin refused");
        return URI.create("https://textures.minecraft.net" + uri.getRawPath());
    }

    /// Publishes before any ARGUS call; own uploads are preserved and cooldowns suppress manual refresh.
    static void sync(Skin skin, Runnable preview, Target target) throws Exception {
        preview.run();
        SaoTextureState state = target.state();
        if (state.images().containsKey("premiumSkin")) target.premium();
        else {
            if (!state.images().containsKey("ownSkin")) target.upload(skin.png(), skin.slim());
            if (state.refreshAfter() == 0 && !state.status().equals("syncing")) target.refresh();
        }
    }

    /// Converts legacy limbs using HMCL NormalizedSkin's mirrored face mapping, then applies upload validation.
    static byte[] normalize(byte[] bytes) throws IOException {
        byte[] clean = SaoOathSkins.sanitized(bytes, true);
        BufferedImage source = Objects.requireNonNull(ImageIO.read(new ByteArrayInputStream(clean)));
        if (source.getHeight() == 64) return clean;
        BufferedImage result = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, 64, 32, source.getRGB(0, 0, 64, 32, null, 0, 64), 0, 64);
        int @Unmodifiable [] @Unmodifiable [] faces = {{4,16,20,48,4},{8,16,24,48,4},{0,20,24,52,12},{4,20,20,52,12},
                {8,20,16,52,12},{12,20,28,52,12},{44,16,36,48,4},{48,16,40,48,4},
                {40,20,40,52,12},{44,20,36,52,12},{48,20,32,52,12},{52,20,44,52,12}};
        for (int[] face : faces) for (int y = 0; y < face[4]; y++) for (int x = 0; x < 4; x++)
                    result.setRGB(face[2] + 3 - x, face[3] + y, source.getRGB(face[0] + x, face[1] + y));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(result, "png", output)) throw new IOException("PNG encoding failed");
        return SaoOathSkins.sanitized(output.toByteArray(), false);
    }

    /// Makes a fixed-origin request with no redirects, bounded bytes and timeouts; tokens remain request-local.
    static byte[] download(URI uri, @Nullable String bearer) throws IOException {
        if (bearer != null && !uri.equals(URI.create("https://api.minecraftservices.com/minecraft/profile")))
            throw new IOException("Profile origin refused");
        if (bearer == null) uri = texture(uri.toString());
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(4000); connection.setReadTimeout(4000);
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > LIMIT)
                throw new IOException("Cosmetic source unavailable");
            try (InputStream input = connection.getInputStream()) {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > LIMIT - output.size() || System.nanoTime() > deadline)
                        throw new IOException("Cosmetic source limit reached");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    /// One sanitized diagnostic, never an exception, URL, identity or token.
    static void failed() { System.getLogger(SaoMicrosoftSkin.class.getName()).log(System.Logger.Level.INFO, "Microsoft skin sync unavailable; keeping current appearance"); }
}
