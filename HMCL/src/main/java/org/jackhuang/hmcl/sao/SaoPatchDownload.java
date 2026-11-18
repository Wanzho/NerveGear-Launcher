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

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/// Retrieves the exact object identified by a signed quick-update entry. The normal installer verifies its digest.
@NotNullByDefault
final class SaoPatchDownload {
    /// No instances.
    private SaoPatchDownload() { }

    /// Restricts routing to an HTTPS origin with no path prefix, credentials, query or fragment.
    static String validateOrigin(String origin) throws IOException {
        URI base = URI.create(SaoUpdates.accountApi(origin));
        if (!"/".equals(base.getRawPath())) throw new IOException("补丁下载地址无效");
        return origin;
    }

    /// The upload and download paths depend only on the signed digest, so changing patch bytes never reuses a key.
    static URI objectUri(String origin, SaoData.QuickRelease release) throws IOException {
        URI base = URI.create(validateOrigin(origin));
        if (!release.sha256().matches("[a-f0-9]{64}")) throw new IOException(SaoUpdates.INVALID_ARCHIVE);
        return base.resolve("patches/" + release.sha256() + ".zip");
    }

    /// Writes only to a disposable file owned by the caller. Rejects redirects and bytes beyond the signed size.
    static void fetch(String origin, SaoData.QuickRelease release, Path temporary,
                      BooleanSupplier cancelled, DoubleConsumer progress) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) objectUri(origin, release).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        long deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("补丁下载返回 HTTP " + status);
            long length = connection.getContentLengthLong();
            if (length >= 0 && length != release.size()) throw new IOException(SaoUpdates.INVALID_ARCHIVE);
            try (var input = connection.getInputStream(); var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                long remaining = release.size();
                while (true) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("补丁下载已取消");
                    if (System.nanoTime() - deadline >= 0) throw new IOException("补丁下载超时");
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining == 0 ? 1 : remaining));
                    if (count < 0) break;
                    if (count > remaining) throw new IOException(SaoUpdates.INVALID_ARCHIVE);
                    output.write(buffer, 0, count);
                    remaining -= count;
                    progress.accept((release.size() - remaining) / (double) release.size());
                }
                if (remaining != 0) throw new IOException(SaoUpdates.INVALID_ARCHIVE);
            }
        } finally { connection.disconnect(); }
    }
}
