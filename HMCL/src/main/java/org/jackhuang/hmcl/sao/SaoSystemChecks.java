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

import org.jackhuang.hmcl.util.platform.SystemInfo;
import org.jackhuang.hmcl.util.platform.hardware.GraphicsCard;
import org.jetbrains.annotations.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/// Headless hardware/disk inspection and short Chinese first-open advisories, reusing HMCL's hardware and Java probes.
@NotNullByDefault
final class SaoSystemChecks {
    /// Binary GiB used by the UI's RAM and disk thresholds.
    static final long GIB = 1024L * 1024 * 1024;
    /// Unique managed payload bytes in the inspected OATH 0.8 index plus overrides; excludes Minecraft/runtime.
    static final long OATH08_BYTES = 1_826_559_588L;
    /// Archive size read from the supplied original, used only when no runtime manifest is available.
    static final long OATH08_ZIP_BYTES = 477_071_659L;

    /// Immutable nonpersonal system summary; unknown values are explicit and conservative.
    @NotNullByDefault
    record Hardware(long memoryBytes, String cpu, String gpu, boolean discrete) { }

    /// Captured injectable checks, permitting deterministic threshold tests without OS-specific behavior.
    @NotNullByDefault
    record Facts(long memoryBytes, long freeBytes, long installBytes, boolean writable, boolean compatibleJava,
                 boolean invalidSelectedJava) { }

    /// Utility class.
    private SaoSystemChecks() { }

    /// Uses HMCL's platform-specific detector rather than running system commands or inspecting network interfaces.
    static Hardware hardware() {
        long memory = SystemInfo.getTotalMemorySize();
        @Nullable var cpu = SystemInfo.getCentralProcessor();
        @Nullable List<GraphicsCard> cards = SystemInfo.getGraphicsCards();
        String gpu = cards == null || cards.isEmpty() ? "unknown" : String.join("; ", cards.stream().map(GraphicsCard::getName).toList());
        boolean discrete = cards != null && cards.stream().anyMatch(card -> card.getType() == GraphicsCard.Type.Discrete);
        return new Hardware(memory, cpu == null ? "unknown" : cpu.getName(), gpu, discrete);
    }

    /// Reserves three copies of the larger payload/archive size and 2 GiB for installation headroom.
    static long requiredBytes(long managedBytes, long archiveBytes) {
        return SaoDiskSpace.packRequired(Math.max(managedBytes, archiveBytes));
    }

    /// Checks the filesystem containing the nearest existing ancestor of the future instance path.
    static long freeBytes(Path instance) throws IOException { return Files.getFileStore(existingAncestor(instance)).getUsableSpace(); }

    /// Proves a write using a temporary file on that filesystem; never changes player settings or leaves a probe.
    static boolean writable(Path instance) {
        try {
            Path directory = existingAncestor(instance);
            if (!Files.isDirectory(directory) || !Files.isWritable(directory)) return false;
            Path probe = Files.createTempFile(directory, ".sao-write-check-", ".tmp");
            try { Files.writeString(probe, "write-check"); return true; }
            finally { Files.deleteIfExists(probe); }
        } catch (IOException | SecurityException failure) { return false; }
    }

    /// Rejects a symbolic link in the proposed instance ancestry before probing or installing.
    private static Path existingAncestor(Path path) throws IOException {
        @Nullable Path current = path.toAbsolutePath().normalize();
        @Nullable Path nearest = null;
        while (current != null) {
            if (Files.isSymbolicLink(current) && !systemAlias(current)) throw new IOException("实例路径不能包含符号链接");
            if (nearest == null && Files.exists(current, LinkOption.NOFOLLOW_LINKS)) nearest = current;
            current = current.getParent();
        }
        if (nearest == null) throw new IOException("找不到实例所在磁盘");
        return nearest;
    }

    /// macOS exposes these OS-owned aliases even for ordinary temporary files; arbitrary instance aliases still fail.
    private static boolean systemAlias(Path path) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return false;
        if (path.equals(Path.of("/var"))) return path.toRealPath().equals(Path.of("/private/var"));
        if (path.equals(Path.of("/tmp"))) return path.toRealPath().equals(Path.of("/private/tmp"));
        return false;
    }

    /// Produces stable RAM/disk/Java/write rows; low RAM warns, insufficient installation capacity blocks.
    static @Unmodifiable List<SaoData.SystemCheck> evaluate(Facts facts) {
        List<SaoData.SystemCheck> rows = new ArrayList<>();
        rows.add(facts.memoryBytes() <= 0 ? row(SaoData.CheckLevel.WARN, "未能读取内存，建议流畅画质")
                : facts.memoryBytes() < 8 * GIB ? row(SaoData.CheckLevel.WARN, "内存不足 8 GB，建议流畅画质")
                : row(SaoData.CheckLevel.OK, "内存充足"));
        rows.add(facts.freeBytes() < 0 ? row(SaoData.CheckLevel.BLOCK, "无法检查磁盘空间，请检查实例位置")
                : facts.freeBytes() < facts.installBytes() ? row(SaoData.CheckLevel.BLOCK, "磁盘空间不足，暂不能安装")
                : facts.freeBytes() < 6 * GIB ? row(SaoData.CheckLevel.WARN, "剩余空间不足 6 GB，建议清理")
                : row(SaoData.CheckLevel.OK, "磁盘空间充足"));
        rows.add(facts.invalidSelectedJava() ? row(SaoData.CheckLevel.BLOCK, "所选 Java 版本或架构不兼容，请改为自动")
                : facts.compatibleJava() ? row(SaoData.CheckLevel.OK, "Java 17 与系统架构兼容")
                : row(SaoData.CheckLevel.WARN, "未找到兼容的 Java 17，启动时自动安装"));
        rows.add(facts.writable() ? row(SaoData.CheckLevel.OK, "实例位置可写")
                : row(SaoData.CheckLevel.BLOCK, "实例位置不可写，请检查权限"));
        return List.copyOf(rows);
    }

    /// Creates one immutable display row.
    private static SaoData.SystemCheck row(SaoData.CheckLevel level, String text) { return new SaoData.SystemCheck(level, text); }
}
