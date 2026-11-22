package io.github.sparkcyf.voicesipagent.privileged.bootstrap;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class BootstrapLifecycleTest {
    @Test public void closingPairingWindowKeepsSameNamedConnectService() {
        DiscoveryRegistry registry = new DiscoveryRegistry();
        String connect = "_adb-tls-connect._tcp:adb-phone";
        String pair = "_adb-tls-pairing._tcp:adb-phone";
        assertTrue(registry.resolved(connect, registry.found(connect), 37001, false));
        assertTrue(registry.resolved(pair, registry.found(pair), 38001, true));
        registry.lost(pair);
        assertEquals(0, registry.pairingPort());
        assertEquals(37001, registry.connectPort());
        registry.lost(connect);
        assertEquals(0, registry.connectPort());
    }
    @Test public void lostOrPreviousDiscoveryCannotRestoreOldPort() {
        DiscoveryRegistry registry = new DiscoveryRegistry();
        String key = "_adb-tls-connect._tcp:adb-phone";
        long old = registry.found(key);
        registry.lost(key);
        assertFalse(registry.resolved(key, old, 37001, false));
        long fresh = registry.found(key);
        assertTrue(registry.resolved(key, fresh, 37002, false));
        assertFalse(registry.resolved(key, old, 37001, false));
        assertEquals(37002, registry.connectPort());
        registry.clear();
        assertEquals(0, registry.connectPort());
        assertFalse(registry.resolved(key, fresh, 37002, false));
    }
    @Test public void cancelledConnectClosesWithoutLaunching() throws Exception { lateConnect(false); }
    @Test public void timedOutConnectClosesWithoutLaunching() throws Exception { lateConnect(true); }
    private void lateConnect(boolean timedOut) throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean poisoned = new AtomicBoolean();
        AtomicInteger closes = new AtomicInteger(), launches = new AtomicInteger();
        CountDownLatch connecting = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = worker.submit(() -> TransportAttempt.run(
                () -> { connecting.countDown(); if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("test timeout"); return true; },
                closes::incrementAndGet, () -> active.get() && !poisoned.get(),
                () -> { launches.incrementAndGet(); return "connected"; }));
            assertTrue(connecting.await(2, TimeUnit.SECONDS));
            if (timedOut) poisoned.set(true); else active.set(false);
            release.countDown();
            assertNull(result.get(2, TimeUnit.SECONDS));
            assertEquals(0, launches.get()); assertEquals(1, closes.get());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test public void cleanupRunsOnLaunchFailureAndBeforeSuccessfulReturn() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        assertThrows(IOException.class, () -> TransportAttempt.run(() -> true, closes::incrementAndGet,
            () -> true, () -> { throw new IOException("launch failed"); }));
        assertEquals(1, closes.get());
        assertEquals("helper", TransportAttempt.run(() -> true, closes::incrementAndGet, () -> true, () -> "helper"));
        assertEquals(2, closes.get());
    }
    @Test public void lostQueuedOrResolvingServiceCannotPublish() {
        DiscoveryRegistry registry = new DiscoveryRegistry();
        long resolving = registry.found("pair:a"), queued = registry.found("connect:b");
        registry.lost("pair:a"); registry.lost("connect:b");
        assertFalse(registry.current("pair:a", resolving));
        assertFalse(registry.current("connect:b", queued));
    }
    @Test public void sameNameRediscoveryRejectsPreviousResolution() {
        DiscoveryRegistry registry = new DiscoveryRegistry();
        long old = registry.found("pair:a"); registry.lost("pair:a");
        long fresh = registry.found("pair:a");
        assertFalse(registry.current("pair:a", old)); assertTrue(registry.current("pair:a", fresh));
        registry.clear(); assertFalse(registry.current("pair:a", fresh));
    }
    @Test public void discoveryLivesOnlyWhileRuntimeOrPairingScreenNeedsIt() {
        DiscoveryRegistry registry = new DiscoveryRegistry();
        registry.runtime = true; assertTrue(registry.needed());
        registry.runtime = false; assertFalse(registry.needed());
        Object screen = new Object();
        registry.runtime = true; registry.acquire(screen);
        registry.runtime = false; assertTrue(registry.needed());
        registry.release(screen); assertFalse(registry.needed());
        registry.runtime = true; registry.acquire(screen);
        registry.release(screen); assertTrue(registry.needed());
    }
    @Test public void destroyedOldScreenDoesNotStopItsReplacementDiscovery() {
        DiscoveryRegistry registry = new DiscoveryRegistry();
        Object old = new Object(), replacement = new Object();
        registry.acquire(old); registry.acquire(replacement);
        registry.release(old); assertTrue(registry.needed());
        registry.release(old); assertTrue(registry.needed());
        registry.release(replacement); assertFalse(registry.needed());
    }
}
