package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/// Full engine uninstall sequencing for protected native/fallback credentials and the per-user skin library.
@NotNullByDefault
class SaoOathUninstallTest {
    /// Only synthetic launcher roots are removed; no application package or native helper is executed.
    @TempDir Path temporary;

    /// Resolves platform temp aliases before exercising production path hardening.
    @org.junit.jupiter.api.BeforeEach void canonicalStorageRoot() throws Exception { temporary = temporary.toRealPath(); }

    /// Both native-preferred and fallback stores are forgotten before removal; late writes cannot recreate deleted roots.
    @Test void uninstallRemovesCredentialAndLibraryWithTerminalWriteFence() throws Exception {
        for (boolean nativeStore : new boolean[]{false, true}) {
            Path root = temporary.resolve(nativeStore ? "native" : "fallback");
            SaoOathCredentialStore credentials = new SaoOathCredentialStore(root, nativeStore);
            credentials.write("UninstallFixture", UUID.randomUUID().toString().toCharArray());
            AtomicBoolean quit = new AtomicBoolean();
            AtomicBoolean armed = new AtomicBoolean();
            SaoUninstaller plan = plan(root);
            SaoUninstallHelper.Factory helper = (roots, apps) -> new SaoUninstallHelper.Prepared() {
                /// The helper is inert and merely verifies primary removal completed first.
                @Override public void arm() { assertFalse(Files.exists(root)); armed.set(true); }
                /// Cancellation is unexpected on this successful synthetic uninstall.
                @Override public void cancel() { fail("Successful uninstall must not cancel"); }
            };
            try (SaoPackEngine engine = new SaoPackEngine(root, plan, helper, () -> {
                try { assertNull(credentials.read(), "Credentials must be forgotten before settings shutdown"); }
                catch (IOException failure) { throw new AssertionError("Credential cleanup could not be confirmed"); }
            }, () -> quit.set(true), credentials)) {
                awaitIdle(engine);
                Path saved = engine.saveSkin("Disposable", png(), true).file();
                assertTrue(Files.exists(saved));
                engine.uninstall(false);
                assertTimeoutPreemptively(Duration.ofSeconds(20), () -> { while (!quit.get() && engine.lastError().get() == null) Thread.sleep(10); });
                assertNull(engine.lastError().get());
                assertTrue(quit.get());
                assertTrue(armed.get());
                assertFalse(Files.exists(root));
                assertNull(credentials.read());
                assertThrows(IllegalArgumentException.class, () -> engine.saveSkin("Too late", png(), false));
                char[] password = UUID.randomUUID().toString().toCharArray();
                assertTrue(engine.oathLogin("UninstallFixture", password, true).isCompletedExceptionally());
                for (char value : password) assertTrue(value == '\0');
                assertFalse(Files.exists(root), "Shutdown and late calls must not recreate removed account files");
            } finally { if (credentials.read() != null) credentials.delete(); }
        }
    }

    /// Refusing native deletion cancels the inert helper and preserves all launcher/library files for a visible retry.
    @Test void credentialDeletionFailurePreservesDataAndDoesNotQuit() throws Exception {
        Path root = temporary.resolve("refused");
        AtomicBoolean cancelled = new AtomicBoolean();
        SaoOathCredentials credentials = new SaoOathCredentials() {
            /// No real or synthetic remembered pair is exposed by this refusal fixture.
            @Override public @Nullable Credential read() { return null; }
            /// Credential writes are not needed by this uninstall-only fixture.
            @Override public void write(String name, char[] password) { }
            /// Models a locked native keychain whose item cannot be removed silently.
            @Override public void delete() throws IOException { throw new IOException("Protected-store refusal"); }
            /// Reports no account information in diagnostics.
            @Override public String backend() { return "fixture-refusal"; }
        };
        SaoUninstallHelper.Factory helper = (roots, apps) -> new SaoUninstallHelper.Prepared() {
            /// A failed credential deletion must prevent native post-exit cleanup.
            @Override public void arm() { fail("Refused credential deletion must not arm cleanup"); }
            /// Records cancellation without executing a platform helper.
            @Override public void cancel() { cancelled.set(true); }
        };
        try (SaoPackEngine engine = new SaoPackEngine(root, plan(root), helper,
                () -> fail("Refusal must precede settings shutdown"), () -> fail("Refusal must not quit"), credentials)) {
            awaitIdle(engine);
            Path saved = engine.saveSkin("Keep on failure", png(), false).file();
            engine.uninstall(false);
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> { while (engine.lastError().get() == null) Thread.sleep(10); });
            assertTrue(cancelled.get());
            assertTrue(Files.isRegularFile(saved));
            assertTrue(Files.isDirectory(root));
        }
    }

    /// Creates a file-only removal plan with native Trash explicitly disabled for the synthetic fixture.
    private SaoUninstaller plan(Path root) {
        return new SaoUninstaller(List.of(root), root.resolve("minecraft/versions/sao-oath"), temporary.resolve("Desktop"), new SaoUninstaller.Trash() {
            /// Avoids AWT and native desktop initialization entirely.
            @Override public boolean supported() { return false; }
            /// Fails if a test accidentally attempts native desktop removal.
            @Override public void move(Path path) { throw new AssertionError("Unexpected native Trash"); }
        }, false);
    }

    /// Waits for background pack inspection and the associated busy flag to settle.
    private static void awaitIdle(SaoPackEngine engine) throws Exception {
        var field = SaoPackEngine.class.getDeclaredField("busy"); field.setAccessible(true);
        AtomicBoolean busy = (AtomicBoolean) field.get(engine);
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            while (engine.state().get() == SaoPackState.CHECKING || busy.get()) Thread.sleep(10);
        });
        assertNull(engine.lastError().get());
    }

    /// Encodes a disposable modern skin for storage/removal assertions.
    private static byte[] png() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }
}
