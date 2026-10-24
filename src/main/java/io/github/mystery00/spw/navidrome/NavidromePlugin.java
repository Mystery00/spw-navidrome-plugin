package io.github.mystery00.spw.navidrome;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import io.github.mystery00.spw.navidrome.host.Host1185;
import io.github.mystery00.spw.navidrome.ui.LibraryWindow;
import javax.swing.SwingUtilities;
import java.nio.file.Path;

/** PF4J 生命周期入口；不在宿主启动线程上初始化播放控制器。 */
public final class NavidromePlugin extends SpwPlugin {
    public static final String ID="io.github.mystery00.spw.navidrome";
    private static volatile NavidromePlugin active;
    private final PluginContext context;
    private volatile AppSession session;
    private LibraryWindow window;
    public NavidromePlugin(PluginContext context){super(context);this.context=context;}
    @Override public synchronized void start(){
        if(session!=null)return;
        try {
            String data=System.getenv("LOCALAPPDATA");if(data==null)data=System.getProperty("user.home");
            session=new AppSession(new Host1185(context.getSpwVersion(),getClass().getClassLoader()),Path.of(data,"SPW-Navidrome","audio-cache"));
            active=this;System.out.println("[SPW Navidrome] Plugin started; credentials are session-only");
        } catch(Exception e){System.err.println("[SPW Navidrome] Startup failed: "+e.getClass().getSimpleName());}
    }
    public static void openLibrary(){
        NavidromePlugin plugin=active;if(plugin==null)return;
        SwingUtilities.invokeLater(()->{
            if(active!=plugin||plugin.session==null)return;
            if(plugin.window==null)plugin.window=new LibraryWindow(plugin.session);
            plugin.window.show();
        });
    }
    @Override public synchronized void stop(){
        if(active==this)active=null;AppSession old=session;session=null;if(old!=null)old.close();
        SwingUtilities.invokeLater(()->{if(window!=null){window.dispose();window=null;}});
        System.out.println("[SPW Navidrome] Plugin stopped");
    }
    @Override public void delete(){stop();}
}
