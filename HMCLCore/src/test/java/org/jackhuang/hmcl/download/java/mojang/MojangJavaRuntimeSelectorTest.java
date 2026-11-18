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
package org.jackhuang.hmcl.download.java.mojang;

import org.jackhuang.hmcl.game.GameJavaVersion;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.platform.Platform;
import org.jackhuang.hmcl.util.platform.UnsupportedPlatformException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/// Offline runtime-index regressions; fixture URLs are inert and Linux ARM64 availability is synthetic.
@NotNullByDefault
final class MojangJavaRuntimeSelectorTest {
    /// Minecraft 1.20.1 requests gamma, independently of the older general-purpose Java 17 beta constant.
    private static final GameJavaVersion GAMMA = new GameJavaVersion("java-runtime-gamma", 17);

    /// Target keys must depend on the argument rather than the architecture running the test JVM.
    @Test void mapsEverySupportedTargetWithoutConsultingHostArchitecture() {
        assertAll(
                () -> assertEquals("windows-arm64", MojangJavaRuntimeSelector.platformKey(Platform.WINDOWS_ARM64)),
                () -> assertEquals("windows-x64", MojangJavaRuntimeSelector.platformKey(Platform.WINDOWS_X86_64)),
                () -> assertEquals("windows-x86", MojangJavaRuntimeSelector.platformKey(Platform.WINDOWS_X86)),
                () -> assertEquals("mac-os-arm64", MojangJavaRuntimeSelector.platformKey(Platform.MACOS_ARM64)),
                () -> assertEquals("mac-os", MojangJavaRuntimeSelector.platformKey(Platform.MACOS_X86_64)),
                () -> assertEquals("linux-aarch64", MojangJavaRuntimeSelector.platformKey(Platform.LINUX_ARM64)),
                () -> assertEquals("linux", MojangJavaRuntimeSelector.platformKey(Platform.LINUX_X86_64)),
                () -> assertEquals("linux-i386", MojangJavaRuntimeSelector.platformKey(Platform.LINUX_X86)),
                () -> assertNull(MojangJavaRuntimeSelector.platformKey(Platform.UNKNOWN)));
    }

    /// A native gamma entry wins even when a valid x64 entry also exists in the manifest.
    @Test void windowsArmPrefersNativeGamma() throws Exception {
        var selected = MojangJavaRuntimeSelector.select(fixture(), GAMMA, "windows-arm64");
        assertEquals("windows-arm64", selected.platform());
        assertEquals("https://example.invalid/runtime/windows-arm64-gamma.json", selected.download().manifest().getUrl());
    }

    /// Reproduces the failing beta index shape: an existing ARM64 platform with an empty component array.
    @Test void windowsArmEmptyBetaFallsBackToX64ForThatSameComponent() throws Exception {
        var selected = MojangJavaRuntimeSelector.select(fixture(), GameJavaVersion.JAVA_17, "windows-arm64");
        assertEquals("windows-x64", selected.platform());
        assertEquals("https://example.invalid/runtime/windows-x64-beta.json", selected.download().manifest().getUrl());
    }

    /// An empty gamma array cannot terminate selection before Windows x64 is tried.
    @Test void windowsArmEmptyGammaFallsBackToX64() throws Exception {
        var index = fixture();
        index.downloads().get("windows-arm64").put(GAMMA.component(), List.of());
        assertEquals("windows-x64", MojangJavaRuntimeSelector.select(index, GAMMA, "windows-arm64").platform());
    }

    /// A platform with other components must not hide the requested gamma component's x64 fallback.
    @Test void windowsArmMissingGammaFallsBackToX64() throws Exception {
        var index = fixture();
        index.downloads().get("windows-arm64").remove(GAMMA.component());
        assertEquals("windows-x64", MojangJavaRuntimeSelector.select(index, GAMMA, "windows-arm64").platform());
        index.downloads().remove("windows-arm64");
        assertEquals("windows-x64", MojangJavaRuntimeSelector.select(index, GAMMA, "windows-arm64").platform());
    }

    /// An ARM64 entry below the required Java major is as unusable as an empty array.
    @Test void windowsArmOlderRuntimeFallsBackToX64() throws Exception {
        var index = fixture();
        var nativeEntry = index.downloads().get("windows-arm64").get(GAMMA.component()).get(0);
        index.downloads().get("windows-arm64").put(GAMMA.component(), List.of(withVersion(nativeEntry, "16.0.2")));
        assertEquals("windows-x64", MojangJavaRuntimeSelector.select(index, GAMMA, "windows-arm64").platform());
    }

    /// Version filtering keeps Mojang's first compatible native entry ahead of both later native and x64 entries.
    @Test void preservesNativeThenX64AndManifestEntryOrder() throws Exception {
        var index = fixture();
        var nativeEntry = index.downloads().get("windows-arm64").get(GAMMA.component()).get(0);
        var firstCompatible = withVersion(nativeEntry, "17.0.13");
        index.downloads().get("windows-arm64").put(GAMMA.component(),
                List.of(withVersion(nativeEntry, "16.0.2"), firstCompatible, withVersion(nativeEntry, "17.0.15")));
        assertSame(firstCompatible, MojangJavaRuntimeSelector.select(index, GAMMA, "windows-arm64").download());
        @Unmodifiable List<String> order = MojangJavaRuntimeSelector.platformCandidates("windows-arm64");
        assertEquals(List.of("windows-arm64", "windows-x64"), order);
        assertThrows(UnsupportedOperationException.class, () -> order.add("windows-x86"));
    }

    /// Windows x64, Apple Silicon and a supplied Linux ARM64 component keep their exact platform entries.
    @Test void retainsWindowsX64MacArmAndLinuxArm() throws Exception {
        var index = fixture();
        for (String platform : List.of("windows-x64", "mac-os-arm64", "linux-aarch64")) {
            assertEquals(platform, MojangJavaRuntimeSelector.select(index, GAMMA, platform).platform());
            assertEquals(List.of(platform), MojangJavaRuntimeSelector.platformCandidates(platform));
        }
    }

    /// Linux ARM64 and macOS ARM64 must report absence instead of silently borrowing x64 runtimes.
    @Test void missingNonWindowsArmEntriesDoNotCrossArchitectures() throws Exception {
        var index = fixture();
        index.downloads().remove("linux-aarch64");
        index.downloads().get("mac-os-arm64").put(GAMMA.component(), List.of());
        assertThrows(UnsupportedPlatformException.class, () -> MojangJavaRuntimeSelector.select(index, GAMMA, "linux-aarch64"));
        assertThrows(UnsupportedPlatformException.class, () -> MojangJavaRuntimeSelector.select(index, GAMMA, "mac-os-arm64"));
    }

    /// Exhausted candidates identify the requested component and both platforms without the former empty-list error.
    @Test void exhaustedWindowsFallbackReportsEveryAttemptedPlatform() throws Exception {
        var index = fixture();
        index.downloads().get("windows-arm64").put(GAMMA.component(), List.of());
        index.downloads().get("windows-x64").put(GAMMA.component(), List.of());
        var failure = assertThrows(UnsupportedPlatformException.class,
                () -> MojangJavaRuntimeSelector.select(index, GAMMA, "windows-arm64"));
        assertTrue(failure.getMessage().contains("java-runtime-gamma"));
        assertTrue(failure.getMessage().contains("[windows-arm64, windows-x64]"));
        assertFalse(failure.getMessage().contains("Candidates: []"));
        assertThrows(UnsupportedPlatformException.class, () -> MojangJavaRuntimeSelector.select(index, GAMMA, null));
    }

    /// Loads a fresh mutable index per case so fixture mutations remain isolated and require no network.
    private static MojangJavaDownloads fixture() throws IOException {
        try (InputStream input = Objects.requireNonNull(MojangJavaRuntimeSelectorTest.class
                .getResourceAsStream("/java-runtimes/component-platforms.json"))) {
            return JsonUtils.fromNonNullJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), MojangJavaDownloads.class);
        }
    }

    /// Reuses a fixture manifest with a controlled Java version for compatibility and ordering cases.
    private static MojangJavaDownloads.JavaDownload withVersion(MojangJavaDownloads.JavaDownload entry, String version) {
        return new MojangJavaDownloads.JavaDownload(entry.availability(), entry.manifest(),
                new MojangJavaDownloads.Version(version, entry.version().released()));
    }
}
