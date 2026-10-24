package vip.mystery0.spw.navidrome;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.PluginPermission;
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException;
import vip.mystery0.spw.navidrome.host.ReflectiveHost;
import vip.mystery0.spw.navidrome.credentials.CredentialStore;
import java.nio.file.Path;

/** 生命周期入口；原生页面的依赖只在实际宿主中加载。 */
public final class NavidromePlugin extends SpwPlugin {
    public static final String ID = "vip.mystery0.spw.navidrome";
    private final PluginContext context;
    private volatile AppSession session;

    public NavidromePlugin(PluginContext context) {
        super(context);
        this.context = context;
    }

    @Override
    public synchronized void start() {
        if (session != null) return;
        // 拒绝权限时不创建会话、恢复凭据或注册宿主拦截器。
        if (!WorkshopApi.manager().isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            showConfigurationHelp();
            System.out.println(
                    "[SPW Navidrome] Native integration disabled: permission not granted");
            return;
        }
        try {
            String data = System.getenv("LOCALAPPDATA");
            if (data == null) data = System.getProperty("user.home");
            Path root = Path.of(data, "SPW-Navidrome");
            session =
                    new AppSession(
                            new ReflectiveHost(
                                    context.getSpwVersion(), getClass().getClassLoader()),
                            root.resolve("audio-cache"));
            Class.forName("vip.mystery0.spw.navidrome.ui.NativeUiHost")
                    .getMethod("install", String.class, AppSession.class, CredentialStore.class)
                    .invoke(
                            null,
                            context.getSpwVersion(),
                            session,
                            new CredentialStore(
                                    WorkshopApi.manager()
                                            .createConfigManager()
                                            .getConfig()
                                            .getConfigPath()
                                            .getParent()));
            System.out.println("[SPW Navidrome] Plugin started with native UI");
        } catch (Exception | LinkageError e) {
            if (session != null) {
                session.close();
                session = null;
            }
            Throwable cause = e;
            while (cause instanceof java.lang.reflect.InvocationTargetException
                    && cause.getCause() != null) cause = cause.getCause();
            // 查询后仍可能拒绝注册，不能只依赖提前检查。
            if (cause instanceof PluginPermissionDeniedException) {
                showConfigurationHelp();
                System.out.println(
                        "[SPW Navidrome] Native integration disabled: registration denied");
                return;
            }
            System.err.println("[SPW Navidrome] Native UI startup failed");
            throw new IllegalStateException("Native UI startup failed", e);
        }
    }

    /** 无 UI Hook 时仍可通过宿主标准配置按钮查看恢复办法。 */
    public static void showConfigurationHelp() {
        WorkshopApi.ui()
                .toast(
                        "Navidrome 页面需要“允许修改应用行为与界面”权限。请停用后重新启用并勾选；已授权仍无页面时，请检查宿主适配错误并反馈 SPW 版本。",
                        WorkshopApi.Ui.ToastType.Warning);
    }

    @Override
    public synchronized void stop() {
        if (session == null) return;
        try {
            Class.forName("vip.mystery0.spw.navidrome.ui.NativeUiHost")
                    .getMethod("uninstall")
                    .invoke(null);
        } catch (Exception | LinkageError e) {
            System.err.println("[SPW Navidrome] UI cleanup failed");
        }
        AppSession old = session;
        session = null;
        if (old != null) old.close();
        System.out.println("[SPW Navidrome] Plugin stopped");
    }

    @Override
    public void delete() {
        stop();
    }
}
