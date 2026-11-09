/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import java.util.List;

/// Immutable operation snapshot published by the service on its UI publication thread.
/// Counts refer to the current stage; bytesDone includes current partial bytes and can decrease on retry.
/// totalBytes=-1 means unknown. networkBytes includes retransfers; speed is measured network bytes/second.
/// @param stage stable operation identifier
/// @param label localized text suitable for immediate display
/// @param completedFiles verified successful remote files in this batch
/// @param totalFiles remote files needed in this batch, or zero for an unknown count
/// @param bytesDone verified bytes plus current partial payload bytes, which may decrease on retry
/// @param totalBytes index byte sum, or -1 for an unknown denominator
/// @param networkBytes measured payload bytes, including discarded attempts and excluding protocol headers
/// @param bytesPerSecond measured payload throughput, zero when the batch ends
/// @param failures every terminal remote-file failure observed so far
@NotNullByDefault
public record SaoInstallProgress(Stage stage, String label, int completedFiles, int totalFiles,
                                 long bytesDone, long totalBytes, long networkBytes, double bytesPerSecond,
                                 @Unmodifiable List<Failure> failures) {
    /// Stable stage identifiers for UI localization; a stage may repeat as HMCL task graphs advance.
    @NotNullByDefault
    public enum Stage {
        /// No operation is active.
        IDLE,
        /// Verifying the installed inventory.
        CHECKING,
        /// Extracting or copying pack members into private staging.
        PREPARING,
        /// Fetching missing remote dependencies, with verified completion counts.
        DOWNLOAD_MODS,
        /// Minecraft metadata, client, libraries or assets are being downloaded.
        DOWNLOAD_MINECRAFT,
        /// Installing the exact supported NeoForge loader.
        INSTALL_NEOFORGE,
        /// Obtaining the required Java runtime.
        DOWNLOAD_JAVA,
        /// Checking or repairing Minecraft/loader dependencies.
        CHECK_DEPENDENCIES,
        /// Committing verified staged files and the pack receipt.
        APPLY_PACK,
        /// The operation completed successfully.
        COMPLETE,
        /// The operation failed; failures contains every failed remote file when applicable.
        FAILED
    }

    /// One actual HTTP request/response, including redirects; status=0 denotes no received response.
    /// @param url exact request address
    /// @param host actual request host
    /// @param status HTTP response status, or zero if no response was received
    @NotNullByDefault
    public record Attempt(String url, String host, int status) { }

    /// Public dependency failure, with the actual hosts attempted and a bounded diagnostic.
    /// @param path relative index destination
    /// @param attempts immutable actual request history
    /// @param reason bounded public diagnostic, without account information
    @NotNullByDefault
    public record Failure(String path, @Unmodifiable List<Attempt> attempts, String reason) {
        /// Takes an immutable snapshot while worker threads continue finishing other files.
        public Failure { attempts = List.copyOf(attempts); }
    }

    /// Copies failures so a snapshot can safely cross worker/UI threads.
    public SaoInstallProgress { failures = List.copyOf(failures); }

    /// Creates an unknown-byte snapshot for a non-transfer phase without inventing byte totals or speed.
    public static SaoInstallProgress phase(Stage stage, String label) {
        return new SaoInstallProgress(stage, label, 0, 0, 0, -1, 0, 0, List.of());
    }
}
