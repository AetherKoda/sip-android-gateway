package io.github.sparkcyf.voicesipagent.protection;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Shizuku creates user services directly, without the application's component factory. */
public final class EncryptedPrivilegedService extends Binder {
    private final IBinder service;

    public EncryptedPrivilegedService(Context context) throws Exception {
        if (android.os.Process.myUid() != 2000 && android.os.Process.myUid() != 0) {
            throw new SecurityException("Shizuku shell or root UID required");
        }
        String apk = context.getApplicationInfo().sourceDir;
        ClassLoader loader = EncryptedCode.load(apk,
                EncryptedCode.nativePath(apk, context.getApplicationInfo().nativeLibraryDir),
                EncryptedPrivilegedService.class.getClassLoader());
        service = (IBinder) loader.loadClass("io.github.sparkcyf.voicesipagent.privileged.shell.PrivilegedService")
                .getConstructor(Context.class).newInstance(context);
        attachInterface(null, service.getInterfaceDescriptor());
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        return service.transact(code, data, reply, flags);
    }
}
