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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/// Headless regression fixtures reproduce Forge/NeoForge 47, latest.log and HotSpot report structure.
@NotNullByDefault
class SaoCrashSummaryTest {
    /// Isolated game directory; no JavaFX toolkit or game process is started.
    @TempDir Path temporary;

    /// Real-format, synthetic NeoForge 1.20.1 diagnostic with an intentionally oversized mod inventory.
    private static String forgeReport() {
        return """
                ---- Minecraft Crash Report ----
                // Don't do that.
                Time: 2026-11-15 12:00:00
                Description: Rendering overlay

                java.lang.IllegalStateException: Overlay failed
                    at example.overlay.Renderer.draw(Renderer.java:10)
                    at example.overlay.Renderer.tick(Renderer.java:20)
                    at net.minecraft.client.Minecraft.run(Minecraft.java:30)
                    at example.Fourth.omitted(Fourth.java:40)
                Caused by: java.lang.IllegalArgumentException: Broken layer
                    at example.overlay.Layer.create(Layer.java:11)
                    at example.overlay.Layer.load(Layer.java:12)
                    at example.overlay.Layer.init(Layer.java:13)
                    at example.Fourth.omitted(Fourth.java:44)
                Caused by: java.lang.NullPointerException: Missing texture
                    at example.overlay.Texture.get(Texture.java:15)

                A detailed walkthrough of the error, its code path and all known details is as follows:
                ---------------------------------------------------------------------------------------
                -- Head --
                Thread: Render thread
                Suspected Mod(s): Example Overlay (exampleoverlay), Version: 1.0
                    at TRANSFORMER/exampleoverlay@1.0/example.overlay.Renderer.draw(Renderer.java:10)
                Stacktrace:
                    at example.overlay.Renderer.draw(Renderer.java:10)
                -- System Details --
                Details:
                    Minecraft Version: 1.20.1
                    NeoForge: net.neoforged:47.1.106
                    Mod List:
                """ + "example-1.0.jar | Example | example | 1.0 | COMMON_SET | Manifest: NOSIGNATURE\n".repeat(450);
    }

    /// The wrapper must keep the beginning and cause chain rather than the last 16K of mod rows.
    @Test void neoForgeLongModListKeepsCauseAndHead() throws Exception {
        Files.createDirectories(temporary.resolve("crash-reports"));
        Path file = Files.writeString(temporary.resolve("crash-reports/crash-client.txt"), forgeReport());
        SaoData.Crash crash = Objects.requireNonNull(new SaoGameLogs(temporary).analyze(true, 0));
        assertEquals(file, crash.report());
        assertTrue(crash.detail().startsWith("Description: Rendering overlay"), crash.detail());
        assertTrue(crash.detail().contains("java.lang.IllegalStateException: Overlay failed"));
        assertTrue(crash.detail().contains("Caused by: java.lang.NullPointerException: Missing texture"));
        assertTrue(crash.detail().contains("Layer.init(Layer.java:13)"));
        assertFalse(crash.detail().contains("Fourth.omitted"));
        assertTrue(crash.detail().contains("-- Head --"));
        assertTrue(crash.detail().contains("可能是：Example Overlay (exampleoverlay), Version: 1.0"));
        assertFalse(crash.reason().contains("原因未知"));
        assertNoInventory(crash.detail());
    }

    /// An embedded report needs no trailing HMCL crash-location marker and accepts log prefixes.
    @Test void latestLogEmbeddedReportWithoutEndMarker() throws Exception {
        SaoGameLogs logs = new SaoGameLogs(temporary);
        logs.append("[12:00:00] [Render thread/INFO]: Earlier noise");
        logs.append(forgeReport().lines().map(line -> "[12:00:01] [Render thread/ERROR]: " + line)
                .collect(java.util.stream.Collectors.joining("\n")));
        SaoData.Crash crash = Objects.requireNonNull(logs.analyze(true, 0));
        assertTrue(crash.detail().startsWith("Description: Rendering overlay"), crash.detail());
        assertTrue(crash.detail().contains("Caused by: java.lang.IllegalArgumentException: Broken layer"));
        assertNoInventory(crash.detail());
    }

    /// A plain latest.log ending in a stack trace still supplies a readable first exception and three frames.
    @Test void latestLogEndingInException() throws Exception {
        SaoGameLogs logs = new SaoGameLogs(temporary);
        Files.createDirectories(temporary.resolve("logs"));
        Files.writeString(temporary.resolve("logs/latest.log"), """
                [12:00:00] [main/INFO]: Starting Minecraft 1.20.1
                [12:00:02] [main/ERROR]: Unhandled failure
                java.lang.RuntimeException: Failed loading config file example.toml of type COMMON for modid examplemod
                    at example.Config.read(Config.java:1)
                    at example.Config.load(Config.java:2)
                    at example.Config.start(Config.java:3)
                    at example.Fourth.omitted(Fourth.java:4)
                Caused by: java.io.IOException: Invalid configuration
                    at example.Parser.read(Parser.java:5)
                """);
        logs.loadPrevious();
        SaoData.Crash crash = Objects.requireNonNull(logs.analyze(true, 0));
        assertEquals(temporary.resolve("logs/latest.log"), crash.report());
        assertTrue(crash.detail().contains("java.lang.RuntimeException:"));
        assertTrue(crash.detail().contains("Caused by: java.io.IOException:"));
        assertTrue(crash.detail().contains("可能是：examplemod"));
        assertFalse(crash.detail().contains("Fourth.omitted"));
        assertNoInventory(crash.detail());
    }

    /// Legacy Windows logs can contain non-UTF-8 bytes; startup must still retain later diagnostic lines.
    @Test void previousLogToleratesLegacyEncodedBytes() throws Exception {
        Path log = temporary.resolve("logs/latest.log");
        Files.createDirectories(log.getParent());
        Files.write(log, "旧日志\njava.lang.IllegalStateException: diagnostic\n".getBytes(java.nio.charset.Charset.forName("GB18030")));
        SaoGameLogs logs = new SaoGameLogs(temporary);
        logs.loadPrevious();
        assertTrue(logs.snapshot().stream().anyMatch(line -> line.contains("java.lang.IllegalStateException: diagnostic")));
    }

    /// Native failures live beside the game, not only in crash-reports; preserve the actual failing frame.
    @Test void nativeCrashShowsProblematicFrame() throws Exception {
        Path file = Files.writeString(temporary.resolve("hs_err_pid1234.log"), """
                # A fatal error has been detected by the Java Runtime Environment:
                # EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ff123456789, pid=1234, tid=456
                # JRE version: OpenJDK Runtime Environment (17.0.12+7)
                # Java VM: OpenJDK 64-Bit Server VM
                # Problematic frame:
                # C  [nvoglv64.dll+0x123456]
                #
                ---------------  T H R E A D  ---------------
                Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
                C  [nvoglv64.dll+0x123456]
                """);
        SaoData.Crash crash = Objects.requireNonNull(new SaoGameLogs(temporary).analyze(true, 0));
        assertEquals(file, crash.report());
        assertTrue(crash.detail().contains("EXCEPTION_ACCESS_VIOLATION"));
        assertTrue(crash.detail().contains("Problematic frame:\n# C  [nvoglv64.dll+0x123456]"));
        assertNoInventory(crash.detail());
        assertNull(new SaoGameLogs(temporary).analyze(false, System.currentTimeMillis() + 5000));
    }

    /// Direct parser coverage isolates raw log prefixes from the wrapper's privacy-redacted timestamps.
    @Test void rawAndRedactedPrefixesProduceSameEvidence() {
        String report = forgeReport().lines().map(line -> "[12:00:01] [Render thread/ERROR]: " + line)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(SaoGameLogs.sanitize(SaoCrashSummary.parse(report).detail()),
                SaoCrashSummary.parse(SaoGameLogs.sanitize(report)).detail());
    }

    /// Forge 47 loading sections name the failing mod even when the old HMCL matcher only sees a section marker.
    @Test void forgeLoadingSectionNamesMod() {
        SaoCrashSummary.Summary summary = SaoCrashSummary.parse("""
                ---- Minecraft Crash Report ----
                Description: Mod loading error has occurred
                java.lang.Exception: Mod Loading has failed
                    at net.minecraftforge.logging.CrashReportExtender.dumpModLoadingCrashReport(CrashReportExtender.java:60)
                -- MOD brokenmod --
                Details:
                    Mod File: brokenmod-1.0.jar
                    Failure message: Broken Mod (brokenmod) has failed to load correctly
                -- System Details --
                Mod List:
                    unrelated.jar | Unrelated | unrelated | 1.0 | COMMON_SET | Manifest: NOSIGNATURE
                """);
        assertTrue(summary.detail().contains("可能是：brokenmod"));
        assertTrue(summary.detail().contains("java.lang.Exception: Mod Loading has failed"));
        assertFalse(summary.detail().contains("unrelated"));
        assertNoInventory(summary.detail());
    }

    /// Vanilla debug crashes retain evidence without inventing a suspect from Java or Minecraft package names.
    @Test void noSuspectIsInventedAndNewestEmbeddedReportWins() {
        SaoCrashSummary.Summary summary = SaoCrashSummary.parse(forgeReport() + "\n" + """
                ---- Minecraft Crash Report ----
                Description: Manually triggered debug crash
                java.lang.Throwable: Manually triggered debug crash
                    at net.minecraft.client.KeyboardHandler.keyPress(KeyboardHandler.java:1)
                -- Head --
                Thread: Render thread
                Suspected Mods: NONE
                -- System Details --
                Mod List:
                """);
        assertEquals("Manually triggered debug crash", summary.headline());
        assertFalse(summary.detail().contains("可能是："));
        assertFalse(summary.detail().contains("Overlay failed"));
        assertTrue(summary.detail().contains("java.lang.Throwable:"));
        assertNoInventory(summary.detail());
    }

    /// Each exception retains precisely its first three frames, including uncaught-thread and module prefixes.
    @Test void causeChainHasThreeFramesPerException() {
        SaoCrashSummary.Summary summary = SaoCrashSummary.parse("""
                Exception in thread "main" java.lang.RuntimeException: launch failed
                    at java.base/example.A.first(A.java:1)
                    at example.A.second(A.java:2)
                    at example.A.third(A.java:3)
                    at example.A.fourth(A.java:4)
                Caused by: java.io.IOException: read failed
                    at example.B.first(B.java:1)
                    at example.B.second(B.java:2)
                    at example.B.third(B.java:3)
                    at example.B.fourth(B.java:4)
                """);
        assertEquals(6, summary.detail().lines().filter(line -> line.strip().startsWith("at ")).count());
        assertFalse(summary.detail().contains("fourth"));
        assertTrue(summary.detail().contains("Caused by: java.io.IOException: read failed"));
    }

    /// Shared exclusion assertions catch both the section label and representative inventory columns.
    private static void assertNoInventory(String summary) {
        assertFalse(summary.contains("Mod List"));
        assertFalse(summary.contains("NOSIGNATURE"));
        assertFalse(summary.contains("example-1.0.jar"));
    }
}
