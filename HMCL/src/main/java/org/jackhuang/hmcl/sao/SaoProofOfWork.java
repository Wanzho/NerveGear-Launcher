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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.net.IDN;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/// Solves one bounded SHA-256 mail challenge on the calling account worker, with no additional threads.
@NotNullByDefault
final class SaoProofOfWork {
    /// Highest accepted leading-zero-bit target; prevents an untrusted endpoint selecting unbounded work.
    static final int MAX_DIFFICULTY = 26;
    /// Monotonic work limit independent of server time or later wall-clock changes.
    private static final long MAX_SOLVE_NANOS = 120_000_000_000L;
    /// Maximum accepted challenge lifetime measured from the start of local verification.
    private static final long MAX_LIFETIME_MILLIS = 15 * 60 * 1000L;

    /// Utility class with no retained challenge, identity or response state.
    private SaoProofOfWork() { }

    /// Matches the server's case-insensitive names and IDN-to-ASCII replacement-mailbox normalization.
    static String normalize(String purpose, String identity) throws SaoAccountException {
        String normalized = identity.strip().toLowerCase(Locale.ROOT);
        if (!purpose.equals("email")) return normalized;
        int at = normalized.lastIndexOf('@');
        if (at < 1) throw new SaoAccountException("请检查邮箱地址", "invalidInput");
        try { return normalized.substring(0, at + 1) + IDN.toASCII(normalized.substring(at + 1)); }
        catch (IllegalArgumentException invalid) { throw new SaoAccountException("请检查邮箱地址", "invalidInput"); }
    }

    /// Validates a challenge, hashes nonce + purpose + normalized identity + decimal counter, and returns its proof.
    /// The signature is opaque and is verified by the issuing server when the protected operation is submitted.
    static JsonObject solve(JsonObject challenge, String purpose, String identity, BooleanSupplier cancelled) throws SaoAccountException {
        checkCancelled(cancelled);
        if (!(purpose.equals("register") || purpose.equals("resend") || purpose.equals("forgot") || purpose.equals("email"))) throw invalid();
        String nonce = string(challenge, "nonce", 256);
        String signedPurpose = string(challenge, "purpose", 32);
        String signedIdentity = string(challenge, "identity", 254);
        String signature = string(challenge, "signature", 256);
        int difficulty = integer(challenge, "difficulty");
        long expiresAt = integerLong(challenge, "expiresAt");
        long now = System.currentTimeMillis();
        if (!signedPurpose.equals(purpose) || difficulty < 0 || difficulty > MAX_DIFFICULTY
                || expiresAt <= now || expiresAt - now > MAX_LIFETIME_MILLIS)
            throw invalid();
        String normalized = normalize(purpose, identity);
        if (normalized.isEmpty() || normalized.length() > 254 || !signedIdentity.equals(normalized)) throw invalid();
        byte[] prefix = (nonce + purpose + normalized).getBytes(StandardCharsets.UTF_8);
        byte[] decimal = new byte[19];
        byte[] hash = new byte[32];
        long started = System.nanoTime();
        long allowance = Math.min(MAX_SOLVE_NANOS, (expiresAt - now) * 1_000_000L);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (long counter = 0; counter < Long.MAX_VALUE; counter++) {
                if ((counter & 1023) == 0) {
                    checkCancelled(cancelled);
                    if (System.nanoTime() - started >= allowance) throw expired();
                }
                int offset = decimal(counter, decimal);
                digest.update(prefix);
                digest.update(decimal, offset, decimal.length - offset);
                digest.digest(hash, 0, hash.length);
                if (leadingZeroBits(hash, difficulty)) {
                    checkCancelled(cancelled);
                    if (System.nanoTime() - started >= allowance || System.currentTimeMillis() >= expiresAt) throw expired();
                    JsonObject proof = new JsonObject();
                    proof.addProperty("nonce", nonce);
                    proof.addProperty("difficulty", difficulty);
                    proof.addProperty("expiresAt", expiresAt);
                    proof.addProperty("purpose", purpose);
                    proof.addProperty("identity", normalized);
                    proof.addProperty("signature", signature);
                    proof.addProperty("counter", Long.toString(counter));
                    System.getLogger(SaoProofOfWork.class.getName()).log(System.Logger.Level.INFO,
                            "Mail proof solved purpose=" + purpose + " difficulty=" + difficulty
                                    + " elapsedMillis=" + (System.nanoTime() - started) / 1_000_000);
                    return proof;
                }
            }
            throw expired();
        } catch (NoSuchAlgorithmException | DigestException impossible) { throw new AssertionError(impossible); }
        finally { Arrays.fill(prefix, (byte) 0); Arrays.fill(decimal, (byte) 0); Arrays.fill(hash, (byte) 0); }
    }

    /// Writes a nonnegative counter into a reusable ASCII buffer and returns its first digit's offset.
    private static int decimal(long counter, byte[] target) {
        int offset = target.length;
        do { target[--offset] = (byte) ('0' + counter % 10); counter /= 10; } while (counter != 0);
        return offset;
    }

    /// Checks the most significant bits directly, including targets that are not whole bytes.
    static boolean leadingZeroBits(byte[] hash, int bits) {
        int full = bits / 8;
        for (int index = 0; index < full; index++) if (hash[index] != 0) return false;
        int remaining = bits % 8;
        return remaining == 0 || ((hash[full] & 255) >>> (8 - remaining)) == 0;
    }

    /// Checks both engine cancellation and the interrupt left by executor shutdown without clearing either.
    static void checkCancelled(BooleanSupplier cancelled) throws SaoAccountException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
            throw new SaoAccountException("账号操作已取消", "cancelled");
    }

    /// Reads a bounded literal JSON string without formatting untrusted challenge values in failures.
    private static String string(JsonObject value, String key, int maximum) throws SaoAccountException {
        @Nullable JsonElement field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) throw invalid();
        String text = field.getAsString();
        if (text.isEmpty() || text.length() > maximum) throw invalid();
        return text;
    }

    /// Reads a JSON integer without accepting truncation, string coercion, exponents or overflow.
    private static long integerLong(JsonObject value, String key) throws SaoAccountException {
        @Nullable JsonElement field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber()) throw invalid();
        String text = field.getAsString();
        if (!text.matches("[0-9]{1,19}")) throw invalid();
        try { return Long.parseLong(text); }
        catch (NumberFormatException overflow) { throw invalid(); }
    }

    /// Narrows a protocol integer only when its exact value fits the JVM integer range.
    private static int integer(JsonObject value, String key) throws SaoAccountException {
        long number = integerLong(value, key);
        if (number > Integer.MAX_VALUE) throw invalid();
        return (int) number;
    }

    /// Keeps malformed or excessive server-selected work distinct from credentials and mailbox state.
    private static SaoAccountException invalid() { return new SaoAccountException("操作验证无效，请稍后重试", "powInvalid"); }

    /// Requests an explicit user retry without silently repeating any mail-producing operation.
    private static SaoAccountException expired() { return new SaoAccountException("操作验证已过期，请重试", "powExpired"); }
}
