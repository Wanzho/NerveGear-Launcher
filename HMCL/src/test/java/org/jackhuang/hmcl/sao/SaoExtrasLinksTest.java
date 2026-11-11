/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/// Headless, socket-free link schema, redirect, body bound, deadline and cache tests.
@NotNullByDefault
class SaoExtrasLinksTest {
    /// Disposable last-good cache root.
    @TempDir Path temporary;
    /// Minimal strict schema sample.
    private static final String VALID = "{\"links\":[{\"id\":\"site\",\"label\":\"官网\",\"url\":\"https://afdian.com/site\"}]}";

    /// Valid lists preserve order and defaults use the existing sole Quark link.
    @Test void schemaAndDefaults() throws Exception {
        var links = SaoCommunityLinks.parse(VALID);
        assertEquals("官网", links.get(0).label());
        assertThrows(UnsupportedOperationException.class, () -> links.clear());
        assertEquals(List.of(), SaoCommunityLinks.parse("{\"links\":[]}"));
        try (var online = new SaoOnlineData(temporary, (request, limit) -> { throw new AssertionError("No network"); })) {
            assertEquals(SaoCommunityLinks.DEFAULTS, online.cachedLinks());
            assertEquals(SaoUpdates.QUARK_URL, online.cachedLinks().get(1).url());
            assertEquals(List.of("afdian", "quark", "qq"), online.cachedLinks().stream().map(SaoData.CommunityLink::id).toList());
            assertNull(online.cachedLinks().get(2).url());
            assertEquals("QQ群", online.cachedLinks().get(2).label());
            assertEquals("1104279677", online.cachedLinks().get(2).copy());
        }
    }

    /// Copy-only groups round-trip through the real transport/cache without ever inventing a join URL.
    @Test void copyGroupAndStrictDigits() throws Exception {
        String group = "{\"links\":[{\"id\":\"qq\",\"label\":\"QQ群\",\"copy\":\"1104279677\"}]}";
        try (var online = new SaoOnlineData(temporary, (request, limit) ->
                new SaoOnlineData.Response(200, null, group.getBytes(StandardCharsets.UTF_8)))) {
            assertEquals(online.fetchLinks(), online.cachedLinks());
            assertEquals("1104279677", online.cachedLinks().get(0).copy());
            assertNull(online.cachedLinks().get(0).url());
        }
        for (String value : List.of("", " 1104279677", "+1104279677", "1104-279677", "１１０４２７９６７７", "1".repeat(33), "https://example.org")) {
            assertThrows(IOException.class, () -> SaoCommunityLinks.parse(group.replace("1104279677", value)));
            assertThrows(IllegalArgumentException.class, () -> new SaoData.CommunityLink("qq", "QQ群", null, value));
        }
        for (String invalid : List.of(group.replace("\"copy\":", "\"url\":\"https://example.org\",\"copy\":"),
                group.replace("\"1104279677\"", "1104279677"), group.replace("\"1104279677\"", "null"),
                group.replace("\"copy\":", "\"copy\":\"1\",\"copy\":")))
            assertThrows(IOException.class, () -> SaoCommunityLinks.parse(invalid));
        assertThrows(IllegalArgumentException.class, () -> new SaoData.CommunityLink("qq", "QQ群", null, null));
    }

    /// Refuses malformed fields, duplicate IDs/keys, type coercions, scripts and excess links before caching.
    @Test void rejectsUnsafeOrAmbiguousDocuments() {
        for (String invalid : List.of(VALID + "{}", "// comment\n" + VALID,
                VALID.replace("\"site\"", "123"), VALID.replace("\"id\":", "\"extra\":\"x\",\"id\":"),
                VALID.replace("\"id\":\"site\"", "\"id\":\"site\",\"id\":\"site\""),
                VALID.replace("\"官网\"", "\"<b>官网</b>\""), VALID.replace("\"官网\"", "\"a\\nlabel\""),
                VALID.replace("https://afdian.com/site", "http://example.org/site"),
                VALID.replace("https://afdian.com/site", "https://evil.example/site"),
                VALID.replace("https://afdian.com/site", "https://afdian.com.evil.example/site"),
                VALID.replace("https://afdian.com/site", "javascript:alert(1)"),
                VALID.replace("https://afdian.com/site", "https://user:pass@example.org/site"),
                VALID.replace("https://afdian.com/site", "https://example.org:443/site"),
                VALID.replace("https://afdian.com/site", "https://127.0.0.1/site"),
                VALID.replace("https://afdian.com/site", "https://[::1]/site"),
                VALID.replace("https://afdian.com/site", "https://localhost/site"),
                "{\"links\":null}", "{\"links\":[],\"links\":[]}", "{\"links\":[{}]}",
                "{\"links\":[" + String.join(",", Collections.nCopies(9, "{\"id\":\"same\",\"label\":\"x\",\"url\":\"https://example.org\"}")) + "]}")) {
            assertThrows(IOException.class, () -> SaoCommunityLinks.parse(invalid), invalid);
        }
        assertThrows(IOException.class, () -> SaoCommunityLinks.parse(" ".repeat(SaoCommunityLinks.MAX_BYTES + 1)));
    }

    /// Exactly one trusted hop shares a shrinking ten-second deadline; invalid replacements keep the last good file.
    @Test void oneHopAndLastGoodCache() throws Exception {
        AtomicInteger requests = new AtomicInteger(); List<Duration> timeouts = new ArrayList<>();
        try (var online = new SaoOnlineData(temporary, (request, limit) -> {
            timeouts.add(request.timeout().orElseThrow()); assertEquals(SaoCommunityLinks.MAX_BYTES, limit);
            return requests.incrementAndGet() == 1
                    ? new SaoOnlineData.Response(302, "https://raw.giteeusercontent.com/links.json?fixture=1", new byte[0])
                    : new SaoOnlineData.Response(200, null, VALID.getBytes(StandardCharsets.UTF_8));
        })) {
            assertEquals(online.fetchLinks(), online.cachedLinks()); assertEquals(2, requests.get());
            assertTrue(timeouts.get(0).compareTo(Duration.ofSeconds(10)) <= 0);
            assertTrue(timeouts.get(0).compareTo(Duration.ofSeconds(9)) > 0);
            assertTrue(timeouts.get(1).compareTo(timeouts.get(0)) < 0);
        }
        byte[] prior = Files.readAllBytes(temporary.resolve("online-links.json"));
        for (var response : List.of(new SaoOnlineData.Response(200, null, "{bad}".getBytes(StandardCharsets.UTF_8)),
                new SaoOnlineData.Response(200, null, new byte[SaoCommunityLinks.MAX_BYTES + 1]),
                new SaoOnlineData.Response(200, null, new byte[]{(byte) 0xc3, (byte) 0x28}),
                new SaoOnlineData.Response(302, "https://foreign.invalid/links", new byte[0]),
                new SaoOnlineData.Response(302, "https://raw.giteeusercontent.com/repeat", new byte[0]))) {
            try (var online = new SaoOnlineData(temporary, (request, limit) -> response)) {
                assertThrows(Exception.class, online::fetchLinks);
                assertEquals("site", online.cachedLinks().get(0).id());
            }
            assertArrayEquals(prior, Files.readAllBytes(temporary.resolve("online-links.json")));
        }
    }
}
