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
    private static final String VALID = "{\"links\":[{\"id\":\"site\",\"label\":\"官网\",\"url\":\"https://example.org/site\"}]}";

    /// Valid lists preserve order and defaults use the existing sole Quark link.
    @Test void schemaAndDefaults() throws Exception {
        var links = SaoCommunityLinks.parse(VALID);
        assertEquals("官网", links.get(0).label());
        assertThrows(UnsupportedOperationException.class, () -> links.clear());
        assertEquals(List.of(), SaoCommunityLinks.parse("{\"links\":[]}"));
        try (var online = new SaoOnlineData(temporary, (request, limit) -> { throw new AssertionError("No network"); })) {
            assertEquals(SaoCommunityLinks.DEFAULTS, online.cachedLinks());
            assertEquals(SaoUpdates.QUARK_URL, online.cachedLinks().get(1).url());
        }
    }

    /// Refuses malformed fields, duplicate IDs/keys, type coercions, scripts and excess links before caching.
    @Test void rejectsUnsafeOrAmbiguousDocuments() {
        for (String invalid : List.of(VALID + "{}", "// comment\n" + VALID,
                VALID.replace("\"site\"", "123"), VALID.replace("\"id\":", "\"extra\":\"x\",\"id\":"),
                VALID.replace("\"id\":\"site\"", "\"id\":\"site\",\"id\":\"site\""),
                VALID.replace("\"官网\"", "\"<b>官网</b>\""), VALID.replace("\"官网\"", "\"a\\nlabel\""),
                VALID.replace("https://example.org/site", "http://example.org/site"),
                VALID.replace("https://example.org/site", "javascript:alert(1)"),
                VALID.replace("https://example.org/site", "https://user:pass@example.org/site"),
                VALID.replace("https://example.org/site", "https://example.org:443/site"),
                VALID.replace("https://example.org/site", "https://127.0.0.1/site"),
                VALID.replace("https://example.org/site", "https://[::1]/site"),
                VALID.replace("https://example.org/site", "https://localhost/site"),
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
