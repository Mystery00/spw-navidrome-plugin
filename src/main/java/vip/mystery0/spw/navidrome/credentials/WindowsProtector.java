package vip.mystery0.spw.navidrome.credentials;

import java.io.IOException;

/** 复用宿主提供的 JNA 调用当前 Windows 用户 DPAPI，不启动外部进程。 */
public class WindowsProtector {
    public byte[] protect(byte[] input) throws IOException { return transform(input, "cryptProtectData"); }
    public byte[] unprotect(byte[] input) throws IOException { return transform(input, "cryptUnprotectData"); }

    private byte[] transform(byte[] input, String operation) throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows")) throw new IOException("Windows DPAPI required");
        try {
            Class<?> crypt = Class.forName("com.sun.jna.platform.win32.Crypt32Util");
            // 仅设置 CRYPTPROTECT_UI_FORBIDDEN=1；不设置 LOCAL_MACHINE=4，保持当前用户范围。
            return (byte[]) crypt.getMethod(operation, byte[].class, int.class).invoke(null, input, 1);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            // 原生库和反射异常不向上传递，避免日志输出原始载荷或底层诊断。
            throw new IOException("Windows DPAPI operation failed");
        }
    }
}
