package io.github.sparkcyf.voicesipagent.privileged.bootstrap;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

public final class BootstrapProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        // ContentProvider.call must enforce its own caller authorization.
        if (Binder.getCallingUid() != 2000 || !"attach".equals(method) || extras == null)
            throw new SecurityException("Shell handshake only");
        return LocalBootstrap.get(getContext()).attach(arg, extras.getBinder("service"));
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
