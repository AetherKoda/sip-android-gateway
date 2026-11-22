package io.github.sparkcyf.voicesipagent.privileged.bootstrap;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class LaunchGateTest {
    @Test public void acceptsOnlyShellWithLiveSingleUseToken() {
        AtomicLong time = new AtomicLong(100);
        LaunchGate gate = new LaunchGate(time::get);
        LaunchGate.Ticket ticket = gate.begin();
        assertFalse(gate.consume(10000, ticket.nonce));
        assertFalse(gate.consume(2000, "wrong"));
        assertTrue(gate.consume(2000, ticket.nonce));
        assertFalse(gate.consume(2000, ticket.nonce));
    }
    @Test public void cancellationRejectsLateHandshakeAndCompletion() {
        LaunchGate gate = new LaunchGate(() -> 0);
        LaunchGate.Ticket old = gate.begin();
        gate.cancel();
        LaunchGate.Ticket next = gate.begin();
        assertFalse(gate.current(old.generation));
        assertFalse(gate.consume(2000, old.nonce));
        assertTrue(gate.current(next.generation));
        assertTrue(gate.consume(2000, next.nonce));
    }
    @Test public void expiresAtDeadline() {
        AtomicLong time = new AtomicLong();
        LaunchGate gate = new LaunchGate(time::get);
        LaunchGate.Ticket ticket = gate.begin();
        time.set(LaunchGate.LIFETIME_MS);
        assertFalse(gate.consume(2000, ticket.nonce));
    }
    @Test public void concurrentBeginCannotReplacePendingOwner() {
        LaunchGate gate = new LaunchGate(() -> 0);
        LaunchGate.Ticket ticket = gate.begin();
        assertThrows(IllegalStateException.class, gate::begin);
        assertTrue(gate.consume(2000, ticket.nonce));
    }
    @Test public void expiredAttemptDoesNotPreventFreshStart() {
        AtomicLong time = new AtomicLong();
        LaunchGate gate = new LaunchGate(time::get);
        LaunchGate.Ticket old = gate.begin();
        time.set(LaunchGate.LIFETIME_MS + 1);
        LaunchGate.Ticket next = gate.begin();
        assertNotEquals(old.nonce, next.nonce);
        assertFalse(gate.current(old.generation));
        assertTrue(gate.consume(2000, next.nonce));
    }
    @Test public void commandAllowsAndroidRandomizedApkPathButRejectsShellInput() {
        String nonce = "a".repeat(64);
        String command = LaunchCommand.create("/data/app/~~abc==/io.example.app-X/base.apk", "io.example.app", nonce);
        assertTrue(command.contains("/system/bin/nohup /system/bin/app_process"));
        assertFalse(command.endsWith("&"));
        assertThrows(IllegalArgumentException.class, () -> LaunchCommand.create("/data/app/' ; id/base.apk", "io.example.app", nonce));
        assertThrows(IllegalArgumentException.class, () -> LaunchCommand.create("/data/app/x/base.apk", "io.example;id", nonce));
        assertThrows(IllegalArgumentException.class, () -> LaunchCommand.create("/data/app/x/base.apk", "io.example.app", "'"));
    }
}
