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

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/// Strict, bounded, plain-text community metadata; links are opened only by a later explicit UI action.
@NotNullByDefault
final class SaoCommunityLinks {
    /// Public endpoint controlled by the team.
    static final String URL = "https://gitee.com/xwzho/sao-oath-launcher/raw/master/links.json";
    /// Small payload limit independent of announcements.
    static final int MAX_BYTES = 16384;
    /// Non-placeholder offline defaults, including the existing sole pack share.
    static final @Unmodifiable List<SaoData.CommunityLink> DEFAULTS = List.of(
            new SaoData.CommunityLink("afdian", "爱发电", "https://afdian.com/a/mczhbfwq"),
            new SaoData.CommunityLink("quark", "夸克网盘", SaoUpdates.QUARK_URL),
            new SaoData.CommunityLink("qq", "QQ群", null, "1104279677"));

    /// links.json is unsigned, so its buttons may only point at the team's known community sites (and their subdomains);
    /// a changed Gitee file cannot add a button to an arbitrary download site.
    static final @Unmodifiable Set<String> ALLOWED_HOSTS = Set.of("afdian.com", "afdian.net", "bilibili.com", "b23.tv", "qm.qq.com");

    /// Utility class.
    private SaoCommunityLinks() { }

    /// Rejects duplicates, unknown fields, primitive coercions, trailing input, non-HTTPS URLs and over eight links.
    static @Unmodifiable List<SaoData.CommunityLink> parse(String document) throws IOException {
        if (document.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("Links document is too large");
        try (JsonReader reader = new JsonReader(new StringReader(document))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            if (!reader.hasNext() || !reader.nextName().equals("links")) throw new IOException("Expected links");
            reader.beginArray();
            List<SaoData.CommunityLink> result = new ArrayList<>(); Set<String> ids = new HashSet<>();
            while (reader.hasNext()) {
                if (result.size() >= 8) throw new IOException("At most eight links");
                reader.beginObject(); Map<String, String> fields = new HashMap<>();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (!Set.of("id", "label", "url", "copy").contains(key) || fields.containsKey(key) || reader.peek() != JsonToken.STRING)
                        throw new IOException("Invalid link field");
                    fields.put(key, reader.nextString());
                }
                reader.endObject();
                if (fields.size() != 3) throw new IOException("Missing link field");
                String id = Objects.requireNonNull(fields.get("id")), label = Objects.requireNonNull(fields.get("label"));
                @Nullable String url = fields.get("url"), copy = fields.get("copy");
                if ((url == null) == (copy == null) || (copy != null && !copy.matches("[0-9]{1,32}")))
                    throw new IOException("Expected HTTPS URL or digit-only copy value");
                if (!id.matches("[a-z][a-z0-9_-]{0,31}") || !ids.add(id) || label.isBlank() || label.length() > 40
                        || label.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '<' || c == '>'))
                    throw new IOException("Invalid link label/id");
                if (url != null && !allowedHost(checkedUrl(url))) throw new IOException("Community link host is not allowed");
                result.add(new SaoData.CommunityLink(id, label, url, copy));
            }
            reader.endArray();
            if (reader.hasNext()) throw new IOException("Unknown or duplicate root field");
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing links data");
            return List.copyOf(result);
        } catch (RuntimeException failure) { throw new IOException("Invalid links document", failure); }
    }

    /// Keeps the Quark community action on the same validated version.json share as every download button.
    static @Unmodifiable List<SaoData.CommunityLink> withQuark(List<SaoData.CommunityLink> links, @Nullable String quark) {
        List<SaoData.CommunityLink> result = new ArrayList<>();
        for (SaoData.CommunityLink link : links) {
            // Unsigned community metadata cannot introduce a differently named Quark download action.
            if (link.id().equals("quark") || link.url() != null && "pan.quark.cn".equalsIgnoreCase(URI.create(link.url()).getHost())) continue;
            result.add(link);
        }
        if (quark != null) result.add(Math.min(1, result.size()), new SaoData.CommunityLink("quark", "夸克网盘", quark));
        return List.copyOf(result);
    }

    /// Whether the link's host is an allowed community site or one of its subdomains.
    static boolean allowedHost(URI uri) {
        String host = Objects.requireNonNull(uri.getHost()).toLowerCase(Locale.ROOT);
        for (String allowed : ALLOWED_HOSTS) if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
        return false;
    }

    /// Requires an absolute HTTPS URL without credentials, custom ports, controls or local IP literals.
    static URI checkedUrl(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            if (value.length() > 2048 || value.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                    || !"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 || uri.getHost().equalsIgnoreCase("localhost")
                    || uri.getHost().contains(":") || uri.getHost().matches("[0-9.]+"))
                throw new IOException("Invalid community HTTPS URL");
            return uri;
        } catch (IllegalArgumentException failure) { throw new IOException("Invalid community URL", failure); }
    }
}
