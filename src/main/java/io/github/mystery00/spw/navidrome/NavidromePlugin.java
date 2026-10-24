package io.github.mystery00.spw.navidrome;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import io.github.mystery00.spw.navidrome.host.Host1192;
import io.github.mystery00.spw.navidrome.credentials.CredentialStore;
import java.nio.file.Path;

/** 生命周期入口；原生页面的依赖只在实际宿主中加载。 */
public final class NavidromePlugin extends SpwPlugin {
 public static final String ID="io.github.mystery00.spw.navidrome";
 private final PluginContext context;
 private volatile AppSession session;
 public NavidromePlugin(PluginContext context){super(context);this.context=context;}
 @Override public synchronized void start(){
  if(session!=null)return;
  try {
   String data=System.getenv("LOCALAPPDATA");if(data==null)data=System.getProperty("user.home");
   Path root=Path.of(data,"SPW-Navidrome");
   session=new AppSession(new Host1192(context.getSpwVersion(),getClass().getClassLoader()),root.resolve("audio-cache"));
   Class.forName("io.github.mystery00.spw.navidrome.ui.NativeUiHost").getMethod("install",String.class,AppSession.class,CredentialStore.class).invoke(null,context.getSpwVersion(),session,new CredentialStore(WorkshopApi.manager().createConfigManager().getConfig().getConfigPath().getParent()));
   System.out.println("[SPW Navidrome] Plugin started with native UI");
  }catch(Exception|LinkageError e){if(session!=null){session.close();session=null;}System.err.println("[SPW Navidrome] Native UI startup failed");throw new IllegalStateException("Native UI startup failed",e);}
 }
 @Override public synchronized void stop(){
  try{Class.forName("io.github.mystery00.spw.navidrome.ui.NativeUiHost").getMethod("uninstall").invoke(null);}catch(Exception|LinkageError e){System.err.println("[SPW Navidrome] UI cleanup failed");}
  AppSession old=session;session=null;if(old!=null)old.close();
  System.out.println("[SPW Navidrome] Plugin stopped");
 }
 @Override public void delete(){stop();}
}
