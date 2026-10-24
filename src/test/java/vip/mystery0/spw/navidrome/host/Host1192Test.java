package vip.mystery0.spw.navidrome.host;

import java.net.URLClassLoader;
import java.nio.file.Path;

/** 对实际安装包做无初始化的 ABI 检查，不启动音频引擎。 */
public final class Host1192Test {
    public static void main(String[] args) throws Exception {
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {Path.of(args[0]).toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            Class<?> verifier;
            try {
                verifier = Class.forName("vip.mystery0.spw.navidrome.host.HostAbi1192");
            } catch (ClassNotFoundException e) {
                throw new AssertionError("SPW 1.19.2 ABI verifier missing");
            }
            verifier.getDeclaredMethod("verify", ClassLoader.class).invoke(null, loader);
            System.out.println("HOST 1.19.2 ABI TEST OK");
        }
    }
}
