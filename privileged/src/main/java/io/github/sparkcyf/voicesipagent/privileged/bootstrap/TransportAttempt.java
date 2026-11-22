package io.github.sparkcyf.voicesipagent.privileged.bootstrap;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/** A late successful connect still owns a transport that must be closed. */
final class TransportAttempt {
    interface Close { void run() throws Exception; }
    static <T> T run(Callable<Boolean> connect, Close close, BooleanSupplier active, Callable<T> launch) throws Exception {
        try {
            if (!connect.call()) throw new IOException("Not connected");
            return active.getAsBoolean() ? launch.call() : null;
        } finally { close.run(); }
    }
}
