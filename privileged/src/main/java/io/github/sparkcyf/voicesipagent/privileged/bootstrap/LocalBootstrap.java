package io.github.sparkcyf.voicesipagent.privileged.bootstrap;

import android.content.Context;
import android.net.nsd.*;
import android.os.*;
import java.io.IOException;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.muntashirakon.adb.AdbStream;
import io.github.sparkcyf.voicesipagent.privileged.IPrivilegedService;

/** Owns the local bootstrap only. All telephony and audio work stays in PrivilegedService. */
public final class LocalBootstrap {
    public static final String AUTHORITY_SUFFIX = ".localbootstrap";
    public enum State { STOPPED, NEEDS_START, PAIRING, STARTING, CONNECTED, ERROR }
    public interface Listener { void changed(State state, IPrivilegedService service, String detail); }
    private static LocalBootstrap instance;
    public static synchronized LocalBootstrap get(Context context) {
        if (instance == null) instance = new LocalBootstrap(context.getApplicationContext());
        return instance;
    }
    public static boolean supported() { return Build.VERSION.SDK_INT >= 31 && android.os.Process.myUid() / 100000 == 0; }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LaunchGate gate = new LaunchGate(SystemClock::elapsedRealtime);
    private final IBinder owner = new Binder();
    private volatile IPrivilegedService service, candidate;
    private volatile State state = State.NEEDS_START;
    private volatile String detail = "請先完成本機配對，再啟動通話服務。";
    private LocalAdb adb;
    private long operation;
    private boolean busy, poisoned;
    private int discoveryEpoch;
    private final ArrayList<NsdManager.DiscoveryListener> discoveries = new ArrayList<>();
    private final DiscoveryRegistry discovery = new DiscoveryRegistry();
    private record Resolution(NsdServiceInfo info, String key, long generation) { }
    private final ArrayDeque<Resolution> resolveQueue = new ArrayDeque<>();
    private boolean resolving;
    private long resolutionId;
    private boolean retryScheduled;
    private LocalBootstrap(Context context) { this.context = context; }
    public State getState() { return state; }
    public String getDetail() { return detail; }
    public IPrivilegedService getService() { return service; }
    public int getPairingPort() { return discovery.pairingPort(); }
    public int getConnectPort() { return discovery.connectPort(); }
    public void addListener(Listener listener) { listeners.addIfAbsent(listener); main.post(() -> deliver(listener)); }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public record Snapshot(State state, IPrivilegedService service, String detail) { }
    public synchronized Snapshot snapshot() { return new Snapshot(state, service, detail); }
    private void deliver(Listener listener) {
        Snapshot latest = snapshot();
        // Never call a consumer while holding the bootstrap monitor (connector stop takes it).
        if (listeners.contains(listener)) listener.changed(latest.state(), latest.service(), latest.detail());
    }
    private synchronized void report(State next, String message) {
        state = next; detail = message;
        main.post(() -> { for (Listener listener : listeners) deliver(listener); });
    }
    private synchronized long begin(State next, String message) {
        if (!supported()) { report(State.ERROR, "本機啟動需 Android 12 以上的主要使用者。 "); return -1; }
        if (poisoned) { report(State.ERROR, "請到系統設定 → 應用程式 → QQ-SIP → 強制停止，再重新開啟 QQ-SIP。"); return -1; }
        if (busy) return -1;
        busy = true; long id = ++operation;
        report(next, message);
        main.postDelayed(() -> timeout(id), 30_000);
        return id;
    }
    private synchronized boolean current(long id) { return id == operation && busy && !poisoned; }
    private synchronized void timeout(long id) {
        if (!current(id)) return;
        // Upstream native/socket waits are not all interruptible. Do not spawn replacement workers.
        poisoned = true; busy = false; operation++; gate.cancel();
        runtimeDiscovery(false);
        destroy(candidate); candidate = null; service = null;
        report(State.ERROR, "操作逾時。請到系統設定 → 應用程式 → QQ-SIP → 強制停止，再重新開啟 QQ-SIP。");
    }
    private synchronized void finish(long id, State next, String message) {
        if (!current(id)) return;
        busy = false; runtimeDiscovery(false); report(next, message);
    }
    private LocalAdb connection() throws Exception { if (adb == null) adb = new LocalAdb(context); return adb; }
    public synchronized void pair(String code) {
        if (service != null || busy) return;
        final int port = getPairingPort();
        if (port <= 0 || code == null || !code.matches("[0-9]{6}")) {
            report(State.ERROR, "請保持系統配對碼視窗開啟，再輸入六位數配對碼。"); return;
        }
        final long id = begin(State.PAIRING, "正在與這支手機配對…");
        if (id < 0) return;
        io.execute(() -> {
            try {
                boolean ok = connection().pair("127.0.0.1", port, code);
                finish(id, ok ? State.NEEDS_START : State.ERROR,
                    ok ? "配對成功。請回到 QQ-SIP 按「啟動通話服務」。" : "配對失敗，請重新取得配對碼。");
                if (ok) refreshDiscovery();
            } catch (Exception error) { finish(id, State.ERROR, "配對失敗，請重新開啟系統配對碼視窗。"); }
        });
    }
    public synchronized void start() {
        if (service != null && service.asBinder().pingBinder()) { report(State.CONNECTED, "通話服務已連接。"); return; }
        if (busy) return;
        if (poisoned) { begin(State.STARTING, ""); return; }
        if (getConnectPort() <= 0) { runtimeDiscovery(true); refreshDiscovery(); report(State.NEEDS_START, "請開啟無線偵錯，等待找到本機服務後再按啟動。"); return; }
        final int port = getConnectPort();
        final long id = begin(State.STARTING, "正在啟動通話服務…");
        if (id < 0) return;
        final LaunchGate.Ticket ticket = gate.begin();
        io.execute(() -> {
            IPrivilegedService attached = null;
            try {
                LocalAdb local = connection();
                attached = TransportAttempt.run(() -> local.connect("127.0.0.1", port), local::disconnect,
                    () -> current(id), () -> {
                        String command = LaunchCommand.create(context.getApplicationInfo().sourceDir, context.getPackageName(), ticket.nonce);
                        try (AdbStream stream = local.openStream("shell:" + command)) {
                            long end = SystemClock.elapsedRealtime() + 15_000;
                            while (candidate == null && current(id) && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
                            IPrivilegedService result = candidate;
                            if (result == null || result.getUid() != 2000) throw new IOException("No verified helper");
                            return result;
                        }
                    });
                synchronized (this) {
                    if (attached != null && current(id) && gate.current(ticket.generation) && attached.asBinder().pingBinder()) {
                        service = attached; candidate = null;
                        runtimeDiscovery(false);
                        finish(id, State.CONNECTED, "通話服務已連接。");
                        attached = null; // Ownership transferred to the session.
                    }
                }
            } catch (Exception error) {
                synchronized (this) {
                    if (current(id)) { gate.cancel(); destroy(candidate); candidate = null; }
                }
                // TransportAttempt has already cleaned up, or the watchdog has failed closed.
                finish(id, State.ERROR, "服務未能啟動，請確認無線偵錯與配對；若配對已移除，請重新配對。");
            } finally { destroy(attached); }
        });
    }
    synchronized Bundle attach(String nonce, IBinder binder) {
        if (binder == null || !binder.pingBinder() || poisoned || !busy
                || !gate.consume(Binder.getCallingUid(), nonce)) throw new SecurityException("No authorized launch");
        IPrivilegedService incoming = IPrivilegedService.Stub.asInterface(binder);
        try {
            binder.linkToDeath(() -> {
                synchronized (LocalBootstrap.this) {
                    if ((service != null && service.asBinder() == binder) || (candidate != null && candidate.asBinder() == binder)) {
                        service = null; candidate = null;
                        report(State.NEEDS_START, "通話服務已停止，請重新啟動。");
                    }
                }
            }, 0);
        } catch (RemoteException error) { throw new IllegalStateException("Dead helper"); }
        candidate = incoming;
        Bundle reply = new Bundle(); reply.putBinder("owner", owner); return reply;
    }
    public synchronized void stop() {
        runtimeDiscovery(false);
        gate.cancel(); operation++;
        if (busy) poisoned = true; // A cancelled upstream network wait may still be running.
        busy = false;
        IPrivilegedService old = service; service = null;
        destroy(candidate); candidate = null; destroy(old);
        report(State.STOPPED, "通話服務已停止。");
    }
    private void destroy(IPrivilegedService value) {
        if (value != null) cleanup.execute(() -> { try { value.destroy(); } catch (RemoteException ignored) { } });
    }
    public void discover(Object owner) { main.post(() -> { discovery.acquire(owner); updateDiscovery(); }); }
    public void refreshDiscovery() { main.post(() -> {
        synchronized (LocalBootstrap.this) {
            if (busy || poisoned || service != null || !discovery.needed() || getConnectPort() > 0) return;
        }
        // Pairing completion need not publish a new connect announcement. Re-query it.
        stopDiscoveryOnMain(); updateDiscovery();
    }); }
    private void runtimeDiscovery(boolean needed) { main.post(() -> { discovery.runtime = needed; updateDiscovery(); }); }
    private void updateDiscovery() { if (discovery.needed()) discoverOnMain(); else stopDiscoveryOnMain(); }
    private static String discoveryKey(NsdServiceInfo info) {
        return info.getServiceType().replaceAll("\\.+$", "") + ":" + info.getServiceName();
    }
    private void discoverOnMain() {
        if (!supported() || !discoveries.isEmpty()) return;
        final int epoch = ++discoveryEpoch;
        NsdManager nsd = context.getSystemService(NsdManager.class);
        for (String type : new String[]{"_adb-tls-connect._tcp.", "_adb-tls-pairing._tcp."}) {
            NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onStartDiscoveryFailed(String t, int error) { main.post(() -> retryDiscovery(epoch)); }
                @Override public void onStopDiscoveryFailed(String t, int error) { main.post(() -> retryDiscovery(epoch)); }
                @Override public void onServiceLost(NsdServiceInfo info) { main.post(() -> {
                    if (epoch != discoveryEpoch) return;
                    String key = discoveryKey(info);
                    boolean hadConnect = getConnectPort() > 0;
                    discovery.lost(key); resolveQueue.removeIf(item -> item.key.equals(key));
                    report(state, detail);
                    if (hadConnect && getConnectPort() == 0) main.postDelayed(() -> {
                        if (epoch == discoveryEpoch) refreshDiscovery();
                    }, 500);
                }); }
                @Override public void onServiceFound(NsdServiceInfo info) { main.post(() -> {
                    if (epoch == discoveryEpoch) {
                        String key = discoveryKey(info);
                        resolveQueue.add(new Resolution(info, key, discovery.found(key))); resolveNext(nsd, epoch);
                    }
                }); }
            };
            discoveries.add(listener);
            try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener); }
            catch (RuntimeException error) { retryDiscovery(epoch); }
        }
    }
    private void retryDiscovery(int epoch) {
        if (epoch != discoveryEpoch || !discovery.needed() || retryScheduled) return;
        retryScheduled = true;
        main.postDelayed(() -> {
            if (epoch != discoveryEpoch) return;
            retryScheduled = false;
            synchronized (LocalBootstrap.this) {
                if (busy) { retryDiscovery(epoch); return; }
                if (poisoned || service != null) return;
            }
            stopDiscoveryOnMain(); updateDiscovery();
        }, 2_000);
    }
    private void resolveNext(NsdManager nsd, int epoch) {
        if (resolving || resolveQueue.isEmpty() || epoch != discoveryEpoch) return;
        resolving = true;
        final long id = ++resolutionId;
        Resolution request = resolveQueue.remove();
        NsdManager.ResolveListener resolver = new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo info, int error) { done(null); }
            @Override public void onServiceResolved(NsdServiceInfo info) { done(info); }
            private void done(NsdServiceInfo info) { main.post(() -> {
                if (id != resolutionId) return;
                resolutionId++;
                resolving = false;
                // A cancelled discovery may still have a resolver in Android. Release it
                // before resolving the new epoch, rather than racing two resolveService calls.
                if (epoch != discoveryEpoch) { resolveNext(nsd, discoveryEpoch); return; }
                try {
                    if (discovery.current(request.key, request.generation) && info != null && info.getPort() > 0 && info.getHost() != null
                            && (info.getHost().isLoopbackAddress() || NetworkInterface.getByInetAddress(info.getHost()) != null)) {
                        if (discovery.resolved(request.key, request.generation, info.getPort(),
                                request.key.startsWith("_adb-tls-pairing._tcp:"))) report(state, detail);
                    }
                } catch (Exception ignored) { }
                if (info == null) retryDiscovery(epoch);
                resolveNext(nsd, epoch);
            }); }
        };
        main.postDelayed(() -> {
            if (id != resolutionId || !resolving) return;
            resolutionId++; resolving = false;
            // Older Android has no cancellation API. Ignore its late result and retry discovery;
            // ALREADY_ACTIVE is handled by the same bounded delay, never a busy loop.
            if (Build.VERSION.SDK_INT >= 34) {
                try { nsd.stopServiceResolution(resolver); } catch (RuntimeException ignored) { }
            }
            retryDiscovery(discoveryEpoch);
        }, 8_000);
        try { nsd.resolveService(request.info, resolver); }
        catch (RuntimeException error) { resolver.onResolveFailed(request.info, -1); }
    }
    public void stopDiscovery(Object owner) { main.post(() -> { discovery.release(owner); updateDiscovery(); }); }
    private void stopDiscoveryOnMain() {
        discoveryEpoch++; retryScheduled = false; resolveQueue.clear();
        discovery.clear();
        NsdManager nsd = context.getSystemService(NsdManager.class);
        for (NsdManager.DiscoveryListener listener : discoveries) try { nsd.stopServiceDiscovery(listener); } catch (RuntimeException ignored) { }
        discoveries.clear();
    }
}
