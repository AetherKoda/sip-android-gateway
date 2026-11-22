package io.github.sparkcyf.voicesipagent.protection;

/** app_process does not instantiate an Application or its component factory. */
public final class ShellEntry {
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("Shell UID required");
        String apk = System.getenv("CLASSPATH");
        if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~\\-]+/base\\.apk")) {
            throw new SecurityException("Invalid owning APK");
        }
        String nativePath = EncryptedCode.nativePath(apk, null);
        ClassLoader loader = EncryptedCode.load(apk, nativePath, ShellEntry.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        loader.loadClass("io.github.sparkcyf.voicesipagent.privileged.bootstrap.LocalHelperMain")
                .getMethod("main", String[].class).invoke(null, (Object) args);
    }
}
