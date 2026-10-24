package vip.mystery0.spw.navidrome.host;

import java.net.URLClassLoader;
import java.nio.file.Path;

/** 对实际安装包做不启动播放服务的能力检查，不启动音频引擎。 */
public final class HostBindingsTest {
    public static void main(String[] args) throws Exception {
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {Path.of(args[0]).toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            Class<?> verifier;
            try {
                verifier = Class.forName("vip.mystery0.spw.navidrome.host.HostBindings");
            } catch (ClassNotFoundException e) {
                throw new AssertionError("Dynamic host verifier missing");
            }
            verifier.getDeclaredMethod("verify", ClassLoader.class).invoke(null, loader);
            System.out.println("PASS dynamic host bindings against supplied host");
        }
    }
}
