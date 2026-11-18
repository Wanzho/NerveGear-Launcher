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
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/// Ed25519 trust roots and strict release decoding; production has no property, environment or disk key override.
@NotNullByDefault
final class SaoReleaseSignatures {
    /// Bounded detached base64 signature, without extra text or whitespace.
    static final int MAX_SIGNATURE_BYTES = 128;
    /// Bounded UTF-8 news document, independent of the smaller version policy cap.
    static final int MAX_NEWS_BYTES = 262144;
    /// Embedded X.509 SubjectPublicKeyInfo trust roots; adding a key supports overlapping release-key rotation.
    private static final @Unmodifiable List<String> EMBEDDED_KEYS = List.of(
            "MCowBQYDK2VwAyEA8cAcouYSyhc8kQ8yTsMaDWhnhpy1o5wz8WB5vtM3pAk=");
    /// Immutable production verifier; the private release key is never loaded by launcher startup.
    static final SaoReleaseSignatures PRODUCTION = embedded();
    /// Explicit trusted public keys; test-only instances may supply throwaway keypairs.
    private final @Unmodifiable List<PublicKey> keys;

    /// Copies an explicit key list; production callers use only the embedded singleton.
    SaoReleaseSignatures(List<PublicKey> keys) {
        if (keys.isEmpty()) throw new IllegalArgumentException("At least one release key is required");
        this.keys = List.copyOf(keys);
    }

    /// Loads only the immutable, compiled public-key list.
    private static SaoReleaseSignatures embedded() {
        try {
            List<PublicKey> keys = new ArrayList<>();
            for (String value : EMBEDDED_KEYS) keys.add(KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(value))));
            return new SaoReleaseSignatures(keys);
        } catch (GeneralSecurityException failure) { throw new ExceptionInInitializerError(failure); }
    }

    /// Verifies exactly the received bytes before callers decode UTF-8 or parse release JSON.
    void verify(byte @Unmodifiable [] document, byte @Unmodifiable [] encodedSignature) throws IOException {
        if (encodedSignature.length > MAX_SIGNATURE_BYTES) throw new IOException("Release signature is too large");
        for (byte value : encodedSignature) if (value < 0) throw new IOException("Release signature must be ASCII base64");
        String text = new String(encodedSignature, StandardCharsets.US_ASCII);
        try {
            byte @Unmodifiable [] signature = Base64.getDecoder().decode(text);
            if (signature.length != 64 || !Base64.getEncoder().encodeToString(signature).equals(text))
                throw new IOException("Malformed release signature");
            for (PublicKey key : keys) {
                Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(key); verifier.update(document);
                try { if (verifier.verify(signature)) return; }
                catch (SignatureException invalidSignature) { /* Try the remaining embedded rotation keys. */ }
            }
            throw new IOException("Release signature is not trusted");
        } catch (IllegalArgumentException | GeneralSecurityException failure) {
            throw new IOException("Malformed or unsupported release signature", failure);
        }
    }

    /// Decodes exact UTF-8 with malformed input rejected rather than repaired.
    static String utf8(byte @Unmodifiable [] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    /// Strict bounded news policy; schema 2 intentionally rejects the former unsigned shape.
    static NewsDocument news(String document) throws IOException {
        if (document.getBytes(StandardCharsets.UTF_8).length > MAX_NEWS_BYTES) throw new IOException("News document is too large");
        try {
            JsonObject root = object(document);
            required(root, Set.of("schema", "sequence", "news"));
            if (number(root, "schema") != 2) throw new IOException("Unsupported news schema");
            long sequence = number(root, "sequence");
            JsonElement items = root.get("news");
            if (!items.isJsonArray() || items.getAsJsonArray().size() > 100) throw new IOException("Invalid news array");
            List<SaoData.News> result = new ArrayList<>();
            for (JsonElement item : items.getAsJsonArray()) {
                if (!item.isJsonObject()) throw new IOException("Expected news object");
                JsonObject entry = item.getAsJsonObject();
                Set<String> expected = new HashSet<>(Set.of("kind", "title"));
                if (entry.has("date")) expected.add("date");
                if (entry.has("body")) expected.add("body");
                required(entry, expected);
                SaoData.NewsKind kind = SaoData.NewsKind.valueOf(text(entry, "kind"));
                String title = text(entry, "title"), date = entry.has("date") ? text(entry, "date") : "";
                String body = entry.has("body") ? text(entry, "body") : "";
                if (title.isBlank() || title.length() > 200 || date.length() > 40 || body.length() > 16000)
                    throw new IOException("Invalid news entry length");
                result.add(new SaoData.News(kind, title, date, body));
            }
            return new NewsDocument(sequence, result);
        } catch (RuntimeException failure) { throw new IOException("Invalid news document", failure); }
    }

    /// Fully schema-validated announcement values and their independent anti-rollback sequence.
    /// @param sequence nonnegative signed sequence
    /// @param entries source-ordered immutable announcements
    @NotNullByDefault
    record NewsDocument(long sequence, @Unmodifiable List<SaoData.News> entries) {
        /// Prevents the parsed announcements from being mutated after acceptance.
        NewsDocument { entries = List.copyOf(entries); }
    }

    /// Reads bounded-shape strict JSON without duplicate names, comments, nulls, coercions or trailing content.
    static JsonObject object(String document) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(document))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = value(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Expected one JSON object");
            return value.getAsJsonObject();
        } catch (RuntimeException failure) { throw new IOException("Invalid release JSON", failure); }
    }

    /// Reads release/cache JSON with bounded nesting and arrays, preserving exact integer lexical validation.
    private static JsonElement value(JsonReader reader, int depth) throws IOException {
        if (depth > 8) throw new IOException("Release JSON nesting is too deep");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); JsonObject result = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (result.has(name)) throw new IOException("Duplicate release JSON field");
                    result.add(name, value(reader, depth + 1));
                }
                reader.endObject(); yield result;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); JsonArray result = new JsonArray();
                while (reader.hasNext()) {
                    if (result.size() >= 256) throw new IOException("Release JSON array is too large");
                    result.add(value(reader, depth + 1));
                }
                reader.endArray(); yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String number = reader.nextString();
                if (!number.matches("0|[1-9][0-9]{0,18}")) throw new IOException("Expected nonnegative integer");
                yield new JsonPrimitive(Long.parseLong(number));
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            default -> throw new IOException("Unexpected release JSON value");
        };
    }

    /// Requires the complete known field set, including mandatory sequence fields.
    /// Requires the known fields of a signed release document; unknown fields from newer releases are ignored.
    static void required(JsonObject object, Set<String> fields) throws IOException {
        if (!object.keySet().containsAll(fields)) throw new IOException("Missing release fields");
    }

    static void fields(JsonObject object, Set<String> fields) throws IOException {
        if (!object.keySet().equals(fields)) throw new IOException("Unexpected or missing release fields");
    }

    /// Reads a true JSON string, without primitive coercion.
    static String text(JsonObject object, String name) throws IOException {
        @Nullable JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Expected release string");
        return value.getAsString();
    }

    /// Reads the nonnegative integer grammar already checked by the strict reader.
    static long number(JsonObject object, String name) throws IOException {
        @Nullable JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IOException("Expected release integer");
        return value.getAsLong();
    }
}
