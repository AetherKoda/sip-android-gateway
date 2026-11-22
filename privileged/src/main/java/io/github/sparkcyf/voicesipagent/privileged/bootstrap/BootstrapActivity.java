package io.github.sparkcyf.voicesipagent.privileged.bootstrap;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.widget.*;

/** Pairing stays inside the same installed package; Android supplies the authorization screen. */
public final class BootstrapActivity extends Activity {
    static final String CHANNEL = "local-call-service";
    static final int NOTIFICATION = 4202;
    private LocalBootstrap bootstrap;
    private TextView status;
    private Button pair, start;
    private int notifiedPort;
    private boolean resumed;
    private final LocalBootstrap.Listener listener = (state, service, detail) -> {
        status.setText(detail + "\n\n本機偵錯服務：" + (bootstrap.getConnectPort() > 0 ? "已找到" : "尚未找到"));
        boolean busy = state == LocalBootstrap.State.PAIRING || state == LocalBootstrap.State.STARTING;
        pair.setEnabled(!busy && service == null);
        start.setEnabled(!busy && service == null);
        if (bootstrap.getPairingPort() > 0 && bootstrap.getPairingPort() != notifiedPort) {
            notifiedPort = bootstrap.getPairingPort(); notifyPairing();
        }
        if (bootstrap.getPairingPort() == 0) notifiedPort = 0;
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        bootstrap = LocalBootstrap.get(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
        TextView title = new TextView(this); title.setText("啟動 QQ-SIP 通話服務"); title.setTextSize(24); root.addView(title);
        TextView guide = new TextView(this); guide.setTextSize(17);
        guide.setText("第一次設定\n1. 開啟系統的開發人員選項 → 無線偵錯。\n2. 選擇「使用配對碼配對裝置」。\n3. 保持配對視窗，拉下通知，在 QQ-SIP 通知輸入六位數配對碼。\n4. 回到這裡，按「啟動通話服務」。\n\n已配對時可直接啟動；手機重開機後仍需重新啟動服務。\n");
        root.addView(guide);
        pair = new Button(this); pair.setText("開啟系統設定並準備配對"); root.addView(pair);
        pair.setOnClickListener(view -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1); return;
            }
            if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); return;
            }
            notifyPairing();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        start = new Button(this); start.setText("啟動通話服務"); root.addView(start);
        start.setOnClickListener(view -> bootstrap.start());
        Button done = new Button(this); done.setText("返回 QQ-SIP"); root.addView(done); done.setOnClickListener(view -> finish());
        status = new TextView(this); status.setTextSize(17); root.addView(status);
        bootstrap.addListener(listener); bootstrap.discover(this);
    }
    private void notifyPairing() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "QQ-SIP 通話服務配對", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent reply = PendingIntent.getBroadcast(this, NOTIFICATION,
            new Intent(this, BootstrapReplyReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        Notification.Action action = new Notification.Action.Builder(null, "輸入配對碼", reply)
            .addRemoteInput(new RemoteInput.Builder("pair_code").setLabel("六位數配對碼").build()).build();
        manager.notify(NOTIFICATION, new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("QQ-SIP 通話服務配對").setContentText(bootstrap.getPairingPort() > 0
                ? "已找到本機配對服務，請輸入六位數" : "請先開啟系統的配對碼視窗")
            .addAction(action).setOngoing(true).build());
    }
    @Override public void onResume() {
        super.onResume();
        if (resumed) bootstrap.refreshDiscovery();
        resumed = true;
    }
    @Override public void onDestroy() {
        bootstrap.removeListener(listener); bootstrap.stopDiscovery(this);
        getSystemService(NotificationManager.class).cancel(NOTIFICATION);
        super.onDestroy();
    }
}
