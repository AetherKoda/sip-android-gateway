package io.github.sparkcyf.voicesipagent.privileged.bootstrap;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BootstrapReplyReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Bundle input = RemoteInput.getResultsFromIntent(intent);
        if (input == null) return;
        CharSequence code = input.getCharSequence("pair_code");
        LocalBootstrap bootstrap = LocalBootstrap.get(context);
        PendingResult pending = goAsync();
        AtomicBoolean finished = new AtomicBoolean();
        Runnable finish = () -> { if (finished.compareAndSet(false, true)) pending.finish(); };
        new Handler(Looper.getMainLooper()).postDelayed(finish, 8000);
        LocalBootstrap.Listener listener = new LocalBootstrap.Listener() {
            @Override public void changed(LocalBootstrap.State state,
                    io.github.sparkcyf.voicesipagent.privileged.IPrivilegedService service, String detail) {
                if (state == LocalBootstrap.State.PAIRING) return;
                bootstrap.removeListener(this);
                context.getSystemService(NotificationManager.class).notify(BootstrapActivity.NOTIFICATION,
                    new Notification.Builder(context, BootstrapActivity.CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("QQ-SIP 配對結果").setContentText(detail).build());
                finish.run();
            }
        };
        bootstrap.pair(code == null ? null : code.toString().trim());
        bootstrap.addListener(listener);
    }
}
