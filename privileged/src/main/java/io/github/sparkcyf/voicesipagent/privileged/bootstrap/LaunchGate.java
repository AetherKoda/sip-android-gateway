package io.github.sparkcyf.voicesipagent.privileged.bootstrap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.function.LongSupplier;

/** Process-local authorization for exactly one shell helper attachment. */
final class LaunchGate {
    static final long LIFETIME_MS = 20_000;
    static final class Ticket {
        final long generation;
        final String nonce;
        Ticket(long generation, String nonce) { this.generation = generation; this.nonce = nonce; }
    }
    private final LongSupplier clock;
    private long generation, deadline;
    private String pending;
    LaunchGate(LongSupplier clock) { this.clock = clock; }
    synchronized Ticket begin() {
        if (pending != null && clock.getAsLong() < deadline) throw new IllegalStateException("Launch pending");
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        StringBuilder value = new StringBuilder(64);
        for (byte b : random) value.append(String.format(java.util.Locale.ROOT, "%02x", b));
        pending = value.toString(); deadline = clock.getAsLong() + LIFETIME_MS;
        return new Ticket(++generation, pending);
    }
    synchronized boolean consume(int uid, String nonce) {
        if (uid != 2000 || nonce == null || pending == null || clock.getAsLong() >= deadline) return false;
        if (!MessageDigest.isEqual(pending.getBytes(StandardCharsets.US_ASCII), nonce.getBytes(StandardCharsets.US_ASCII))) return false;
        pending = null; return true;
    }
    synchronized boolean current(long ticket) { return ticket == generation; }
    synchronized void cancel() { pending = null; generation++; }
}
