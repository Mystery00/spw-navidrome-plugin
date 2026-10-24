package vip.mystery0.spw.navidrome.host;
import java.net.*;
import java.nio.file.*;
/** 直接探测实际宿主主题 ABI，不读取用户配置。 */
public final class HostThemeAbiTest {
 public static void main(String[] args)throws Exception {
  try(var loader=new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()},ClassLoader.getPlatformClassLoader())){
   Class<?> theme=loader.loadClass("androidx.compose.ui.ʶ"),configs=loader.loadClass("androidx.compose.ui.ˤ"),colors=loader.loadClass("androidx.compose.ui.ʹ"),dynamic=loader.loadClass("androidx.compose.ui.ʴ");
   for(String name:new String[]{"Ϳ","Ԩ"}){
    var method=theme.getMethod(name);
    if(!method.getReturnType().getName().equals("androidx.compose.runtime.ProvidableCompositionLocal"))throw new AssertionError("Theme local ABI");
    if(method.invoke(null)==null)throw new AssertionError("Missing theme local");
   }
   if(configs.getMethod("Ϳ").getReturnType()!=boolean.class)throw new AssertionError("Dark config ABI");
   for(String name:new String[]{"Ϳ","Ԩ"})if(dynamic.getMethod(name).getReturnType()!=colors)throw new AssertionError("Dynamic colors ABI");
   for(String name:new String[]{"Ϳ","Ԩ","ԩ","Ԫ","ԫ","Ԭ","ԭ","Ԯ","ԯ","ՠ","ֈ"})if(colors.getMethod(name).getReturnType()!=long.class)throw new AssertionError("Packed color ABI");
   Class<?> indication=loader.loadClass("androidx.compose.foundation.Indication");
   Object effect=java.lang.reflect.Proxy.newProxyInstance(loader,new Class[]{indication},(p,m,a)->null);
   for(boolean dark:new boolean[]{false,true}){
    Object value=configs.getConstructor(boolean.class,indication).newInstance(dark,effect);
    if((boolean)configs.getMethod("Ϳ").invoke(value)!=dark)throw new AssertionError("Host-selected dark mode");
   }
  }
  System.out.println("PASS SPW 1.19.2 SaltTheme locals, dark config and 11 packed colors");
 }
}
