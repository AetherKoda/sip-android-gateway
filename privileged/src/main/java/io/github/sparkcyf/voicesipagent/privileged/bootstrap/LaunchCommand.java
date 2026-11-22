package io.github.sparkcyf.voicesipagent.privileged.bootstrap;

final class LaunchCommand {
    private static String entryPoint() {
        String protectedEntry = "io.github.sparkcyf.voicesipagent.protection.ShellEntry";
        try {
            Class.forName(protectedEntry, false, LaunchCommand.class.getClassLoader());
            return protectedEntry;
        } catch (ClassNotFoundException unavailable) {
            return "io.github.sparkcyf.voicesipagent.privileged.bootstrap.LocalHelperMain";
        }
    }

    static String create(String apk, String appPackage, String nonce) {
        if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~\\-]+/base\\.apk")
                || appPackage == null || !appPackage.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                || nonce == null || !nonce.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid local launch input");
        return "CLASSPATH='" + apk + "' /system/bin/nohup /system/bin/app_process /system/bin "
            + "--nice-name=yx-local-helper " + entryPoint() + " "
            + appPackage + " " + nonce + " </dev/null >/dev/null 2>&1";
    }
}
