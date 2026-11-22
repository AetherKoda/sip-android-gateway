package io.github.sparkcyf.voicesipagent.privileged.bootstrap;

import android.content.AttributionSource;
import android.content.Context;
import android.os.*;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.sparkcyf.voicesipagent.privileged.shell.PrivilegedService;

/** Entry point loaded from the owning APK as shell; never constructs Application. */
public final class LocalHelperMain {
    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 2000 || args.length != 2
                    || !args[0].matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                    || !args[1].matches("[a-f0-9]{64}")) throw new SecurityException("Invalid launch");
            Looper.prepareMainLooper();
            Handler main = new Handler(Looper.getMainLooper());
            // A failed handshake must not leave a privileged orphan. Removed after ownership links.
            AtomicBoolean owned = new AtomicBoolean();
            Thread startupWatchdog = new Thread(() -> {
                try { Thread.sleep(LaunchGate.LIFETIME_MS); } catch (InterruptedException ignored) { return; }
                if (!owned.get()) System.exit(1);
            }, "yx-attach-deadline");
            startupWatchdog.setDaemon(true); startupWatchdog.start();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            Context app = system.createPackageContext(args[0], Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            if (!app.getApplicationInfo().sourceDir.equals(System.getenv("CLASSPATH"))) throw new SecurityException("APK mismatch");
            PrivilegedService service = new PrivilegedService(app);
            AtomicBoolean closing = new AtomicBoolean();
            Runnable shutdown = () -> {
                if (!closing.compareAndSet(false, true)) return;
                main.postDelayed(() -> System.exit(1), 3000);
                new Thread(() -> { try { service.destroy(); } finally { System.exit(0); } }, "yx-helper-close").start();
            };
            Class<?> amInterface = Class.forName("android.app.IActivityManager");
            Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            String authority = args[0] + LocalBootstrap.AUTHORITY_SUFFIX;
            IBinder token = new Binder();
            Object holder = amInterface.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                .invoke(am, authority, android.os.Process.myUid() / 100000, token, "yx-local-bootstrap");
            if (holder == null) throw new IllegalStateException("Provider unavailable");
            Bundle reply;
            try {
                Object provider = holder.getClass().getField("provider").get(holder);
                Bundle data = new Bundle(); data.putBinder("service", service);
                reply = (Bundle) Class.forName("android.content.IContentProvider").getMethod("call",
                    AttributionSource.class, String.class, String.class, String.class, Bundle.class)
                    .invoke(provider, new AttributionSource.Builder(2000).setPackageName("com.android.shell").build(),
                        authority, "attach", args[1], data);
            } finally {
                amInterface.getMethod("removeContentProviderExternal", String.class, IBinder.class).invoke(am, authority, token);
            }
            IBinder owner = reply == null ? null : reply.getBinder("owner");
            if (owner == null) throw new SecurityException("Missing owner");
            owner.linkToDeath(shutdown::run, 0);
            owned.set(true); startupWatchdog.interrupt();
            Log.i("QQ-SIPLocalHelper", "owner attached uid=2000");
            Looper.loop();
        } catch (Throwable error) {
            // Never include arguments, pairing material or the command in diagnostics.
            Log.e("QQ-SIPLocalHelper", "bootstrap failed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
