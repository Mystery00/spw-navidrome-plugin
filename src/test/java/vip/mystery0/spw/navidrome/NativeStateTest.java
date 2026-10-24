package vip.mystery0.spw.navidrome;

import vip.mystery0.spw.navidrome.credentials.*;
import vip.mystery0.spw.navidrome.host.HostAdapter;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.function.BooleanSupplier;

/** 合成凭据验证恢复、分页与退出；不连接用户真实账户。 */
public final class NativeStateTest {
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory("navidrome-ui-test");
  var slow=new java.util.concurrent.atomic.AtomicBoolean();
  var restoreRelease=new java.util.concurrent.CountDownLatch(1);
  var saving=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  var protector=new WindowsProtector(){public byte[] protect(byte[] v){if(slow.get()){saving.countDown();try{release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}return v.clone();}public byte[] unprotect(byte[] v){try{restoreRelease.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return v.clone();}};
  var store=new CredentialStore(root,protector);
  HostAdapter host=(HostAdapter)Proxy.newProxyInstance(NativeStateTest.class.getClassLoader(),new Class<?>[]{HostAdapter.class},(p,m,a)->null);
  try(MockServer server=new MockServer();AppSession session=new AppSession(host,root.resolve("cache"))){
   server.mode="browse";store.save(server.address(),"synthetic-user","synthetic-password".toCharArray(),true);
   Class<?> type=Class.forName("vip.mystery0.spw.navidrome.ui.NativeState");
   Object state=type.getConstructor(AppSession.class,CredentialStore.class).newInstance(session,store);
   try {
    try {if((boolean)type.getMethod("getShowAccount").invoke(state))throw new AssertionError("Restore must not flash account form");}finally{restoreRelease.countDown();}
    waitFor(()->session.connected(),"Saved account reconnect");
    waitFor(()->size(type,state,"getCollections")==1,"Album response");
    try {
     if((boolean)type.getMethod("getShowAccount").invoke(state))throw new AssertionError("Successful restore must collapse account form");
     type.getMethod("setAccountExpanded",boolean.class).invoke(state,true);
     if(!(boolean)type.getMethod("getShowAccount").invoke(state))throw new AssertionError("Account management must expand form");
    }catch(NoSuchMethodException e){throw new AssertionError("Account form visibility state missing",e);}
    server.mode="range";
    type.getMethod("setQuery",String.class).invoke(state,"song");
    type.getMethod("search",boolean.class).invoke(state,false);
    waitFor(()->size(type,state,"getSongs")==1,"Search response");
    type.getMethod("search",boolean.class).invoke(state,true);
    waitFor(()->size(type,state,"getSongs")==2,"Search pagination append");
    if(!server.query.get("songOffset").equals("1"))throw new AssertionError("Search offset must track fetched rows");
    slow.set(true);
    type.getMethod("setUsername",String.class).invoke(state,"second-synthetic-user");
    type.getMethod("setPassword",String.class).invoke(state,"second-synthetic-password");
    type.getMethod("login").invoke(state);
    if(!saving.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Successful login must save credentials");
    long started=System.nanoTime();
    type.getMethod("logout").invoke(state);
    long elapsed=System.nanoTime()-started;
    ((AutoCloseable)state).close();release.countDown();
    if(elapsed>500_000_000L)throw new AssertionError("Logout must not wait for credential IO");
    waitFor(()->!Files.exists(root.resolve("credentials.json")),"Forget saved account");
    if(session.connected() || size(type,state,"getSongs")!=0)throw new AssertionError("Logout must clear session and rows");
   }finally{((AutoCloseable)state).close();}
  }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
  System.out.println("PASS native state restore, pagination, nonblocking save and forget");
 }
 private static int size(Class<?> type,Object state,String method){try{return ((java.util.List<?>)type.getMethod(method).invoke(state)).size();}catch(Exception e){throw new RuntimeException(e);}}
 private static void waitFor(BooleanSupplier condition,String label)throws Exception {long end=System.nanoTime()+5_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(20);if(!condition.getAsBoolean())throw new AssertionError(label);}
}
