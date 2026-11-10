/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.download.java.mojang;

import org.jackhuang.hmcl.game.GameJavaVersion;
import org.jackhuang.hmcl.java.JavaInfo;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.Platform;
import org.jackhuang.hmcl.util.platform.UnsupportedPlatformException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Map;

/// Selects a component from Mojang's runtime index without downloading files or probing the host.
@NotNullByDefault
public final class MojangJavaRuntimeSelector {
    /// Prevents construction of this stateless selector.
    private MojangJavaRuntimeSelector() { }

    /// Maps the requested platform, including Linux AArch64, independently of the launcher's host architecture.
    public static @Nullable String platformKey(Platform platform) {
        Architecture architecture = platform.getArchitecture();
        return switch (platform.getOperatingSystem()) {
            case WINDOWS -> switch (architecture) {
                case X86 -> "windows-x86";
                case X86_64 -> "windows-x64";
                case ARM64 -> "windows-arm64";
                default -> null;
            };
            case MACOS -> switch (architecture) {
                case X86_64 -> "mac-os";
                case ARM64 -> "mac-os-arm64";
                default -> null;
            };
            case LINUX -> switch (architecture) {
                case X86 -> "linux-i386";
                case X86_64 -> "linux";
                case ARM64 -> "linux-aarch64";
                default -> null;
            };
            default -> null;
        };
    }

    /// Orders Windows ARM64's native runtime before the x64 runtime supported by Windows 11 translation.
    /// Other platforms retain their exact architecture and never borrow a runtime from another OS.
    public static @Unmodifiable List<String> platformCandidates(@Nullable String platform) {
        if (platform == null) return List.of();
        return "windows-arm64".equals(platform) ? List.of(platform, "windows-x64") : List.of(platform);
    }

    /// Finds the first compatible entry for the requested component, preserving Mojang's order within each platform.
    /// Missing components, empty arrays and older Java versions all advance to the next platform candidate.
    public static Selection select(MojangJavaDownloads index, GameJavaVersion javaVersion,
                                   @Nullable String requestedPlatform) throws UnsupportedPlatformException {
        @Unmodifiable List<String> platforms = platformCandidates(requestedPlatform);
        for (String platform : platforms) {
            @Nullable Map<String, List<MojangJavaDownloads.JavaDownload>> components = index.downloads().get(platform);
            if (components == null) continue;
            @Nullable List<MojangJavaDownloads.JavaDownload> candidates = components.get(javaVersion.component());
            if (candidates == null) continue;
            for (MojangJavaDownloads.JavaDownload candidate : candidates) {
                if (JavaInfo.parseVersion(candidate.version().name()) >= javaVersion.majorVersion())
                    return new Selection(platform, candidate);
            }
        }
        throw new UnsupportedPlatformException("No compatible Mojang runtime for component " + javaVersion.component()
                + " (Java " + javaVersion.majorVersion() + "); tried platforms: " + platforms);
    }

    /// Carries the actual selected platform alongside the manifest entry for diagnostics and download scheduling.
    ///
    /// @param platform index key that supplied the component
    /// @param download matching component entry, including its manifest URL
    @NotNullByDefault
    public record Selection(String platform, MojangJavaDownloads.JavaDownload download) { }
}
