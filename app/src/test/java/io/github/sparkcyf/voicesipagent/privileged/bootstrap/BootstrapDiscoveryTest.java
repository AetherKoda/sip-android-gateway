package io.github.sparkcyf.voicesipagent.privileged.bootstrap;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.net.InetAddress;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Runs the production callbacks with a deterministic main queue, including late callbacks. */
public class BootstrapDiscoveryTest {
    @Test public void notificationDoesNotHoldBootstrapMonitor() throws Exception {
        try (Fixture f = new Fixture()) {
            boolean[] called = {false};
            f.bootstrap.addListener((state, service, detail) -> {
                assertFalse(Thread.holdsLock(f.bootstrap)); called[0] = true;
            });
            f.advance(0); assertTrue(called[0]);
        }
    }
    @Test public void failedDiscoveryStartRestartsAfterDelay() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start();
            f.discoveries.get(0).onStartDiscoveryFailed("_adb-tls-connect._tcp.", 0);
            f.advance(0); assertEquals(2, f.discoveries.size());
            f.advance(2_000); assertEquals(4, f.discoveries.size());
        }
    }
    @Test public void failedResolutionIsRediscovered() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start(); f.found(0);
            f.resolvers.get(0).onResolveFailed(f.info, 0);
            f.advance(0); f.advance(2_000);
            assertEquals(4, f.discoveries.size());
            f.found(2); f.resolvers.get(1).onServiceResolved(f.info); f.advance(0);
            assertEquals(37001, f.bootstrap.getConnectPort());
        }
    }
    @Test public void missingResolutionCallbackRecoversAndRejectsLateResult() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start(); f.found(0);
            f.advance(8_000); f.advance(2_000);
            assertEquals(4, f.discoveries.size());
            f.found(2);
            f.resolvers.get(0).onServiceResolved(f.info); f.advance(0);
            assertEquals(0, f.bootstrap.getConnectPort());
            f.resolvers.get(1).onServiceResolved(f.info); f.advance(0);
            assertEquals(37001, f.bootstrap.getConnectPort());
        }
    }
    @Test public void closingScreenCancelsPendingRetry() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start(); f.discoveries.get(0).onStartDiscoveryFailed("connect", 0); f.advance(0);
            f.bootstrap.stopDiscovery(f); f.advance(0); f.advance(2_000);
            assertEquals(2, f.discoveries.size());
        }
    }
    private static final class Fixture implements AutoCloseable {
        record Task(long at, long order, Runnable run) { }
        long now, sequence;
        final PriorityQueue<Task> tasks = new PriorityQueue<>(Comparator.comparingLong(Task::at).thenComparingLong(Task::order));
        final List<AutoCloseable> scopes = new ArrayList<>();
        final List<NsdManager.DiscoveryListener> discoveries = new ArrayList<>();
        final List<NsdManager.ResolveListener> resolvers = new ArrayList<>();
        final NsdServiceInfo info = mock(NsdServiceInfo.class);
        final LocalBootstrap bootstrap;
        Fixture() throws Exception {
            scopes.add(mockStatic(Looper.class));
            scopes.add(mockConstruction(Binder.class));
            scopes.add(mockConstruction(Handler.class, (handler, ignored) -> {
                when(handler.post(any(Runnable.class))).thenAnswer(call -> {
                    tasks.add(new Task(now, sequence++, call.getArgument(0))); return true;
                });
                when(handler.postDelayed(any(Runnable.class), anyLong())).thenAnswer(call -> {
                    tasks.add(new Task(now + (long) call.getArgument(1), sequence++, call.getArgument(0))); return true;
                });
            }));
            MockedStatic<LocalBootstrap> statics = mockStatic(LocalBootstrap.class, CALLS_REAL_METHODS);
            scopes.add(statics); statics.when(LocalBootstrap::supported).thenReturn(true);
            Context context = mock(Context.class);
            NsdManager nsd = mock(NsdManager.class);
            when(context.getSystemService(NsdManager.class)).thenReturn(nsd);
            doAnswer(call -> { discoveries.add(call.getArgument(2)); return null; })
                .when(nsd).discoverServices(anyString(), anyInt(), any(NsdManager.DiscoveryListener.class));
            doAnswer(call -> { resolvers.add(call.getArgument(1)); return null; })
                .when(nsd).resolveService(any(NsdServiceInfo.class), any(NsdManager.ResolveListener.class));
            when(info.getServiceType()).thenReturn("_adb-tls-connect._tcp.");
            when(info.getServiceName()).thenReturn("adb-test");
            when(info.getPort()).thenReturn(37001);
            when(info.getHost()).thenReturn(InetAddress.getLoopbackAddress());
            var ctor = LocalBootstrap.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true); bootstrap = ctor.newInstance(context);
        }
        void start() { bootstrap.discover(this); advance(0); }
        void found(int index) { discoveries.get(index).onServiceFound(info); advance(0); }
        void advance(long millis) {
            now += millis;
            int limit = 1000;
            while (!tasks.isEmpty() && tasks.peek().at <= now) {
                assertTrue("queue must settle", --limit > 0); tasks.remove().run.run();
            }
        }
        public void close() throws Exception {
            Collections.reverse(scopes);
            for (AutoCloseable scope : scopes) scope.close();
        }
    }
}
