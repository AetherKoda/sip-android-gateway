package io.github.sparkcyf.voicesipagent.privileged.bootstrap;

import java.util.HashMap;

/** Main-thread discovery ownership and per-announcement generations. */
final class DiscoveryRegistry {
    boolean runtime;
    private final java.util.Set<Object> screens = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private long generation;
    private final HashMap<String, Long> present = new HashMap<>();
    private String pairingKey, connectKey;
    private volatile int pairingPort, connectPort;
    void acquire(Object owner) { screens.add(owner); }
    void release(Object owner) { screens.remove(owner); }
    boolean needed() { return runtime || !screens.isEmpty(); }
    int pairingPort() { return pairingPort; }
    int connectPort() { return connectPort; }
    long found(String key) { long value = ++generation; present.put(key, value); return value; }
    void lost(String key) {
        present.remove(key);
        if (key.equals(pairingKey)) { pairingKey = null; pairingPort = 0; }
        if (key.equals(connectKey)) { connectKey = null; connectPort = 0; }
    }
    boolean current(String key, long value) { return Long.valueOf(value).equals(present.get(key)); }
    boolean resolved(String key, long value, int port, boolean pairing) {
        if (!current(key, value) || port <= 0 || port > 65535) return false;
        if (pairing) { pairingKey = key; pairingPort = port; }
        else { connectKey = key; connectPort = port; }
        return true;
    }
    void clear() {
        present.clear(); pairingKey = null; connectKey = null;
        pairingPort = 0; connectPort = 0;
    }
}
