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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// Headless loopback contracts for mail proof submission, old endpoints, bounds and engine shutdown cancellation.
@NotNullByDefault
class SaoProofOfWorkTest {
    /// Contains only disposable account caches and encrypted synthetic credentials.
    @TempDir Path temporary;

    /// All four engine mail actions solve before submission, retain entered spelling and preserve bearer rules.
    @Test void engineSolvesAllMailPurposesBeforeSubmitting() throws Exception {
        try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = accounts(fixture)) {
            char[] password = "synthetic-mail-password".toCharArray();
            accounts.register("MiXeD_Name", "First@Example.TEST", password).get(10, TimeUnit.SECONDS);
            for (char item : password) assertEquals('\0', item);
            accounts.resend().get(10, TimeUnit.SECONDS);
            accounts.forgot("ReCoVeR_Name").get(10, TimeUnit.SECONDS);
            // C-6 (sao_auth 1.9.3): the mailbox change re-proves the current password and still solves the mail proof.
            char[] current = "synthetic-current-password".toCharArray();
            accounts.changeEmail("New+Tag@Example.TEST", current).get(10, TimeUnit.SECONDS);
            for (char item : current) assertEquals('\0', item);
            assertEquals(List.of("register:mixed_name", "resend:mixed_name", "forgot:recover_name", "email:new+tag@example.test"), fixture.challenges);
            assertEquals(List.of("register", "resend", "forgot", "email"), fixture.submissions);
            assertEquals(4, fixture.validProofs.get());
            assertEquals(0, fixture.invalidRequests.get());
            assertEquals("MiXeD_Name", fixture.registered);
            assertEquals("New+Tag@Example.TEST", fixture.changedEmail);
            assertEquals("synthetic-current-password", fixture.changedEmailPassword);
        }
    }

    /// Only absent/unsupported endpoints permit exactly one legacy submission; no challenge error is a success claim.
    @Test void missingEndpointFallbackAndRequiredProofStayClean() throws Exception {
        for (int missing : new int[]{404, 405, 501}) {
            try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = accounts(fixture)) {
                fixture.challengeStatus = missing;
                accounts.forgot("OldService").get(10, TimeUnit.SECONDS);
                assertEquals(List.of("forgot"), fixture.submissions);
                assertEquals(0, fixture.validProofs.get());
                fixture.operationCode = "powRequired";
                SaoAccountException error = failure(accounts.forgot("OldService"));
                assertEquals("powRequired", error.code());
                assertEquals("请更新启动器后重试", error.getMessage());
                assertEquals(2, fixture.submissions.size());
            }
        }
    }

    /// Busy, unauthorized, malformed and redirected challenge endpoints never trigger an unproved operation.
    @Test void challengeFailuresNeverDowngrade() throws Exception {
        for (int rejected : new int[]{400, 401, 403, 429, 500, 503, 302}) {
            try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = accounts(fixture)) {
                fixture.challengeStatus = rejected;
                failure(accounts.forgot("ProofFixture"));
                assertTrue(fixture.submissions.isEmpty());
            }
        }
        try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = accounts(fixture)) {
            fixture.wrongPurpose = true;
            assertEquals("powInvalid", failure(accounts.forgot("ProofFixture")).code());
            assertTrue(fixture.submissions.isEmpty());
        }
    }

    /// Closing the account engine terminates its existing worker during hard work and never submits the pending mail.
    @Test void engineCloseCancelsSolvingWithoutMailSubmission() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.difficulty = SaoProofOfWork.MAX_DIFFICULTY;
            fixture.nonce = hardNonce("forgot", "cancel_fixture");
            SaoOathAccounts accounts = accounts(fixture);
            try {
                CompletableFuture<Void> operation = accounts.forgot("Cancel_Fixture");
                assertTrue(fixture.challengeSent.await(5, TimeUnit.SECONDS));
                // The fixture's first million counters are independently known not to solve this challenge.
                // Wait until the worker is hashing so the assertion exercises CPU cancellation, not just HTTP shutdown.
                Thread worker = awaitSolverThread();
                accounts.close();
                assertTrue(List.of("closed", "cancelled").contains(failure(operation).code()));
                worker.join(5000);
                assertFalse(worker.isAlive(), "Engine shutdown must terminate its solving worker promptly");
                assertTrue(fixture.submissions.isEmpty());
            } finally { accounts.close(); }
        }
    }

    /// Protocol parsing rejects mismatched identity, fractional/oversize work and expired challenges before hashing.
    @Test void solverValidatesBoundsAndCancellation() throws Exception {
        JsonObject valid = challenge("nonce", "forgot", "proof_fixture", 5);
        for (String field : List.of("nonce", "purpose", "identity", "signature", "difficulty", "expiresAt")) {
            JsonObject missing = valid.deepCopy(); missing.remove(field);
            assertEquals("powInvalid", assertThrows(SaoAccountException.class,
                    () -> SaoProofOfWork.solve(missing, "forgot", "Proof_Fixture", () -> false)).code());
        }
        for (int invalid : new int[]{-1, 27, Integer.MAX_VALUE}) {
            JsonObject value = valid.deepCopy(); value.addProperty("difficulty", invalid);
            assertEquals("powInvalid", assertThrows(SaoAccountException.class,
                    () -> SaoProofOfWork.solve(value, "forgot", "Proof_Fixture", () -> false)).code());
        }
        JsonObject fractional = valid.deepCopy(); fractional.addProperty("difficulty", 2.5);
        assertEquals("powInvalid", assertThrows(SaoAccountException.class,
                () -> SaoProofOfWork.solve(fractional, "forgot", "Proof_Fixture", () -> false)).code());
        JsonObject wrongIdentity = valid.deepCopy(); wrongIdentity.addProperty("identity", "someone_else");
        assertEquals("powInvalid", assertThrows(SaoAccountException.class,
                () -> SaoProofOfWork.solve(wrongIdentity, "forgot", "Proof_Fixture", () -> false)).code());
        JsonObject expired = valid.deepCopy(); expired.addProperty("expiresAt", System.currentTimeMillis() - 1);
        assertEquals("powInvalid", assertThrows(SaoAccountException.class,
                () -> SaoProofOfWork.solve(expired, "forgot", "Proof_Fixture", () -> false)).code());
        assertEquals("cancelled", assertThrows(SaoAccountException.class,
                () -> SaoProofOfWork.solve(valid, "forgot", "Proof_Fixture", () -> true)).code());
        JsonObject zero = valid.deepCopy(); zero.addProperty("difficulty", 0);
        assertEquals("0", SaoProofOfWork.solve(zero, "forgot", "Proof_Fixture", () -> false).get("counter").getAsString());
        byte[] hash = new byte[32]; hash[1] = 0x10;
        assertTrue(SaoProofOfWork.leadingZeroBits(hash, 11));
        assertFalse(SaoProofOfWork.leadingZeroBits(hash, 12));
    }

    /// An endpoint change during challenge acquisition cannot redirect a password or proof to another origin.
    @Test void protectedRequestPinsOriginAndKeepsCallerJsonUnmodified() throws Exception {
        try (Fixture first = new Fixture(); Fixture second = new Fixture()) {
            java.util.concurrent.atomic.AtomicReference<URI> endpoint = new java.util.concurrent.atomic.AtomicReference<>(first.root());
            SaoAccountClient client = new SaoAccountClient(endpoint::get, () -> "zh_cn");
            first.afterChallenge = () -> endpoint.set(second.root());
            JsonObject input = new JsonObject(); input.addProperty("username", "ProofFixture");
            client.protectedPost("sao/forgot", "forgot", "ProofFixture", input, null, () -> false);
            assertEquals(List.of("forgot"), first.submissions);
            assertTrue(second.challenges.isEmpty()); assertTrue(second.submissions.isEmpty());
            assertEquals(1, input.size()); assertFalse(input.has("pow")); assertFalse(input.has("language"));
        }
    }

    /// Internationalized mailbox domains use the same canonical proof identity while preserving entered request text.
    @Test void emailIdentityNormalizesIdnWithoutChangingMailbox() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SaoAccountClient client = new SaoAccountClient(fixture::root, () -> "zh_cn");
            String email = "New+Tag@Bücher.TEST";
            JsonObject input = new JsonObject(); input.addProperty("email", email);
            client.protectedPost("sao/email", "email", email, input, "fixture-access", () -> false);
            assertEquals(List.of("email:new+tag@xn--bcher-kva.test"), fixture.challenges);
            assertEquals(email, fixture.changedEmail);
            assertEquals(1, fixture.validProofs.get()); assertEquals(0, fixture.invalidRequests.get());
        }
    }

    /// Creates a real account controller with synthetic protected storage, no JavaFX toolkit and no game integration.
    private SaoOathAccounts accounts(Fixture fixture) throws IOException {
        Path root = temporary.toRealPath().resolve("client-" + System.nanoTime());
        return new SaoOathAccounts(root, fixture::root, () -> "zh_cn", new SaoOathCredentialStore(root, false),
                () -> { throw new AssertionError("Unexpected Microsoft session"); },
                account -> fail("Mail tests must not launch"), message -> fail("Unexpected public error"));
    }

    /// Extracts a safe account failure from the operation future without displaying submitted fields.
    private static SaoAccountException failure(CompletableFuture<Void> operation) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> operation.get(10, TimeUnit.SECONDS));
        return assertInstanceOf(SaoAccountException.class, failure.getCause());
    }

    /// Waits for the existing account thread's solver frame rather than relying on an arbitrary sleep.
    private static Thread awaitSolverThread() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet())
                for (StackTraceElement frame : entry.getValue())
                    if (frame.getClassName().equals(SaoProofOfWork.class.getName()) && frame.getMethodName().equals("solve")) return entry.getKey();
            Thread.sleep(1);
        }
        throw new AssertionError("Account worker did not start solving");
    }

    /// Finds a deterministic fixture nonce whose first million counters cannot accidentally finish before cancellation.
    private static String hardNonce(String purpose, String identity) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int attempt = 0; attempt < 20; attempt++) {
            String nonce = "cancel-nonce-" + attempt;
            boolean hard = true;
            for (int counter = 0; counter < 1_000_000; counter++) {
                byte[] hash = digest.digest((nonce + purpose + identity + counter).getBytes(StandardCharsets.UTF_8));
                if (independentBits(hash) >= SaoProofOfWork.MAX_DIFFICULTY) { hard = false; break; }
            }
            if (hard) return nonce;
        }
        throw new AssertionError("No deterministic hard fixture nonce");
    }

    /// Counts zero bits independently of the production byte-mask implementation.
    private static int independentBits(byte[] digest) {
        int count = 0;
        for (byte value : digest) {
            for (int bit = 7; bit >= 0; bit--) { if ((value & (1 << bit)) != 0) return count; count++; }
        }
        return count;
    }

    /// Creates a signed-shaped synthetic challenge without reading any production secret.
    private static JsonObject challenge(String nonce, String purpose, String identity, int difficulty) {
        JsonObject response = new JsonObject();
        response.addProperty("nonce", nonce); response.addProperty("purpose", purpose); response.addProperty("identity", identity);
        response.addProperty("difficulty", difficulty); response.addProperty("expiresAt", System.currentTimeMillis() + 120_000);
        response.addProperty("signature", "fixture-signature");
        return response;
    }

    /// Minimal local account API that validates proofs independently and never sends mail or launches a window.
    @NotNullByDefault
    private static final class Fixture implements AutoCloseable {
        /// Loopback-only HTTP listener assigned an ephemeral port.
        private final HttpServer server;
        /// Observed challenge purpose and canonical identity, in operation order.
        private final List<String> challenges = new ArrayList<>();
        /// Mail submissions in operation order, including intentionally refused submissions.
        private final List<String> submissions = new ArrayList<>();
        /// Counts independently verified challenge solutions.
        private final AtomicInteger validProofs = new AtomicInteger();
        /// Records unexpected credential, locale or proof behavior without throwing inside the HTTP thread.
        private final AtomicInteger invalidRequests = new AtomicInteger();
        /// Signals that a challenge has been written before testing worker cancellation.
        private final CountDownLatch challengeSent = new CountDownLatch(1);
        /// Selects proof endpoint compatibility and failure cases.
        private volatile int challengeStatus = 200;
        /// Selects a fixture work target without modifying any global setting.
        private volatile int difficulty = 8;
        /// Provides a deterministic hard cancellation challenge.
        private volatile String nonce = "fixture-nonce";
        /// Injects a malformed challenge purpose for downgrade prevention.
        private volatile boolean wrongPurpose;
        /// Optional fixed public operation refusal code.
        private volatile String operationCode = "";
        /// Captures the original entered registration spelling.
        private volatile String registered = "";
        /// Captures the original entered replacement mailbox spelling.
        private volatile String changedEmail = "";
        /// Captures the current password sent with the mailbox change, or empty when the request carried none.
        private volatile String changedEmailPassword = "";
        /// Can change launcher settings after this origin has issued its challenge.
        private volatile Runnable afterChallenge = () -> { };
        /// Retains the last issued proof metadata only inside this disposable fixture.
        private @Nullable JsonObject issued;

        /// Opens a synthetic API with one server worker and no external connections.
        private Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle); server.start();
        }

        /// Resolves only this fixture's numeric loopback root.
        private URI root() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }

        /// Implements just the account/mail/profile state needed for real engine refreshes.
        private void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/sao/pow")) {
                    if (exchange.getRequestHeaders().getFirst("Authorization") != null || !exchange.getRequestMethod().equals("GET")) invalidRequests.incrementAndGet();
                    if (challengeStatus != 200) { send(exchange, challengeStatus, "{\"error\":\"fixture\",\"errorMessage\":\"private fixture text\"}"); return; }
                    Map<String, String> query = new HashMap<>();
                    for (String pair : Objects.requireNonNull(exchange.getRequestURI().getRawQuery()).split("&")) {
                        String[] parts = pair.split("=", 2);
                        query.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                    }
                    String purpose = Objects.requireNonNull(query.get("purpose")), identity = Objects.requireNonNull(query.get("identity"));
                    challenges.add(purpose + ":" + identity);
                    issued = challenge(nonce, wrongPurpose ? "wrong" : purpose, identity, difficulty);
                    afterChallenge.run(); send(exchange, 200, issued.toString()); challengeSent.countDown(); return;
                }
                if (List.of("/sao/register", "/sao/resend", "/sao/forgot", "/sao/email").contains(path)) {
                    String purpose = path.substring("/sao/".length());
                    JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    submissions.add(purpose);
                    if (!request.get("language").getAsString().equals("zh_cn")) invalidRequests.incrementAndGet();
                    @Nullable String bearer = exchange.getRequestHeaders().getFirst("Authorization");
                    if ((purpose.equals("resend") || purpose.equals("email")) != "Bearer fixture-access".equals(bearer)) invalidRequests.incrementAndGet();
                    if (challengeStatus == 200) {
                        if (!valid(request, purpose)) { invalidRequests.incrementAndGet(); send(exchange, 403, "{\"error\":\"powInvalid\"}"); return; }
                        validProofs.incrementAndGet();
                    } else if (request.has("pow")) invalidRequests.incrementAndGet();
                    if (!operationCode.isEmpty()) { send(exchange, 403, "{\"error\":\"" + operationCode + "\",\"errorMessage\":\"private fixture text\"}"); return; }
                    if (purpose.equals("register")) { registered = request.get("username").getAsString(); send(exchange, 200, "{\"accessToken\":\"fixture-access\",\"clientToken\":\"fixture-client\",\"selectedProfile\":{\"name\":\"MiXeD_Name\",\"id\":\"" + "a".repeat(32) + "\"}}"); }
                    else { if (purpose.equals("email")) {
                            changedEmail = request.get("email").getAsString();
                            changedEmailPassword = request.has("password") ? request.get("password").getAsString() : "";
                        } send(exchange, 200, "{}"); }
                    return;
                }
                if (path.equals("/sao/account")) send(exchange, 200, "{\"id\":\"" + "a".repeat(32) + "\",\"name\":\"MiXeD_Name\",\"state\":\"ready\",\"emailVerified\":true,\"graceSeconds\":0}");
                else if (path.startsWith("/sessionserver/session/minecraft/profile/")) send(exchange, 200, "{}");
                else send(exchange, 404, "{}");
            } catch (RuntimeException invalid) { invalidRequests.incrementAndGet(); send(exchange, 500, "{}"); }
            finally { exchange.close(); }
        }

        /// Independently reconstructs the hash and checks all echoed signed metadata before accepting a submission.
        private boolean valid(JsonObject request, String purpose) {
            try {
                JsonObject proof = request.getAsJsonObject("pow"); JsonObject expected = Objects.requireNonNull(issued);
                for (String field : List.of("nonce", "purpose", "identity", "difficulty", "expiresAt", "signature"))
                    if (!expected.get(field).equals(proof.get(field))) return false;
                String identity = purpose.equals("resend") ? "mixed_name" : request.get(purpose.equals("email") ? "email" : "username").getAsString().strip().toLowerCase(java.util.Locale.ROOT);
                if (purpose.equals("email")) {
                    int at = identity.lastIndexOf('@');
                    identity = identity.substring(0, at + 1) + java.net.IDN.toASCII(identity.substring(at + 1));
                }
                if (!identity.equals(proof.get("identity").getAsString()) || !purpose.equals(proof.get("purpose").getAsString())) return false;
                String counter = proof.get("counter").getAsString();
                if (!counter.matches("0|[1-9][0-9]{0,18}")) return false;
                byte[] hash = MessageDigest.getInstance("SHA-256").digest((proof.get("nonce").getAsString() + purpose + identity + counter).getBytes(StandardCharsets.UTF_8));
                return independentBits(hash) >= difficulty;
            } catch (Exception invalid) { return false; }
        }

        /// Writes one small synthetic JSON response with no redirects or real account content.
        private static void send(HttpExchange exchange, int status, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        }

        /// Releases the local listener even when an assertion or cancellation fails.
        @Override public void close() { server.stop(0); }
    }
}
