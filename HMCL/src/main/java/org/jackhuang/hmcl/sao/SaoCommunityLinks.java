/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jetbrains.annotations.NotNullByDefault;
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
            new SaoData.CommunityLink("quark", "夸克网盘", SaoUpdates.QUARK_URL));

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
                    if (!Set.of("id", "label", "url").contains(key) || fields.containsKey(key) || reader.peek() != JsonToken.STRING)
                        throw new IOException("Invalid link field");
                    fields.put(key, reader.nextString());
                }
                reader.endObject();
                if (fields.size() != 3) throw new IOException("Missing link field");
                String id = fields.get("id"), label = fields.get("label"), url = fields.get("url");
                if (!id.matches("[a-z][a-z0-9_-]{0,31}") || !ids.add(id) || label.isBlank() || label.length() > 40
                        || label.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '<' || c == '>'))
                    throw new IOException("Invalid link label/id");
                checkedUrl(url); result.add(new SaoData.CommunityLink(id, label, url));
            }
            reader.endArray();
            if (reader.hasNext()) throw new IOException("Unknown or duplicate root field");
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing links data");
            return List.copyOf(result);
        } catch (RuntimeException failure) { throw new IOException("Invalid links document", failure); }
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
