package vip.mystery0.spw.navidrome.host;

import vip.mystery0.spw.navidrome.model.Models.Song;
import vip.mystery0.spw.navidrome.net.SubsonicClient;
import vip.mystery0.spw.navidrome.stream.LoopbackServer;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 适配本机取证过的 Steam 1.19.2 ABI。
 * 调用者在专用串行宿主线程执行。
 * 直接使用宿主 HttpRangeBassStream 的同一入口，不修改全局音乐视频音源注册表。
 */
public final class Host1192 implements HostAdapter {
    private final String version;
    private final ClassLoader loader;
    private Object controller,player,proxy,service;
    private NativePresentation presentation;
    private final java.util.concurrent.atomic.AtomicLong metadataEpoch=new java.util.concurrent.atomic.AtomicLong();
    private final Object metadataCommitLock=new Object();
    private java.util.concurrent.FutureTask<Boolean> metadataTask;
    private Object pendingRevision;
    private String pendingLocation;
    /** 原生对象可能自定义 equals；加载代次必须比较引用身份。 */
    private static final class Revision {
        final Object item,loaded,refreshed;
        Revision(Object item,Object loaded,Object refreshed){this.item=item;this.loaded=loaded;this.refreshed=refreshed;}
        @Override public boolean equals(Object other){return other instanceof Revision r && item==r.item && loaded==r.loaded && refreshed==r.refreshed;}
        @Override public int hashCode(){return System.identityHashCode(item)^System.identityHashCode(loaded)^System.identityHashCode(refreshed);}
    }
    private Class<?> itemType,interceptorType;
    private Constructor<?> itemConstructor;
    private Method location,currentItem,create,box;
    private Object rangeFactory,owner;
    private volatile LoopbackServer streams;
    private volatile boolean closed;
    private final List<Slot> slots=new ArrayList<>();
    private final List<Object> ownedItems=new ArrayList<>();
    private record Slot(Object holder,Field field) { }
    public Host1192(String version,ClassLoader loader) { this.version=version;this.loader=loader; }
    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name,false,loader); }
    @Override public void check() throws Exception {
        if(!"1.19.2".equals(version)) throw new IllegalStateException("此构建仅适配 SPW Steam 1.19.2，当前为 "+version+"；请更新宿主适配层");
        HostAbi1192.verify(loader);
        itemType=type("com.xuncorp.pisces.PiscesMediaItem");
        itemConstructor=itemType.getConstructor(String.class,String.class,String.class,String.class,String.class,String.class);
        location=itemType.getMethod("\u052c");
        Class<?> ctrl=type("com.xuncorp.voxzen.service.PlaybackController");
        ctrl.getMethod("setPlaybackQueue",List.class,int.class);ctrl.getMethod("prepare");ctrl.getMethod("play");ctrl.getMethod("stop");
        interceptorType=type(HostAbi1192.INTERCEPTOR);
        interceptorType.getMethod("createStream-_2kQ-ls",type("com.xuncorp.pisces.PlatformContext"),itemType,long.class,long.class,int.class,type("kotlin.coroutines.Continuation"));
        Class<?> range=type("com.xuncorp.voxzen.cast.HttpRangeBassStream"),ownerType=type("com.xuncorp.voxzen.cast.HttpRangeStreamOwner");
        create=range.getMethod("create-0E7RQCE",String.class,int.class,ownerType);
        rangeFactory=range.getField("INSTANCE").get(null);owner=ownerType.getField("MusicVideo").get(null);
        box=type("kotlin.Result").getMethod("box-impl",Object.class);
        controller=ctrl.getField("INSTANCE").get(null);
        Field serviceField=ctrl.getDeclaredField("service");serviceField.setAccessible(true);Object svc=serviceField.get(null);service=svc;
        player=svc.getClass().getMethod("getPlayer").invoke(svc);
        if(!player.getClass().getName().equals("com.xuncorp.pisces.\u0528")) throw new IllegalStateException("播放器类型已变化，请重新核对适配层");
        currentItem=type("com.xuncorp.pisces.PiscesPlayer").getMethod(HostAbi1192.CURRENT_ITEM);
        if(currentItem.getReturnType()!=itemType) throw new IllegalStateException("当前媒体签名不匹配");
        // 解码、媒体分析、格式探测各持有一份列表，只添加本插件拦截器。
        slots.clear();
        Object props=field(player,"\u0528");Object opener=field(player,HostAbi1192.OPENER);
        slots.add(slot(props,"\u052f"));slots.add(slot(opener,"\u0529"));
        slots.add(slot(field(player,HostAbi1192.FORMAT_PROBE),"\u052a"));
        for(Slot s:slots) {
            if(!(s.field.get(s.holder) instanceof List<?> list) || list.stream().anyMatch(x->!interceptorType.isInstance(x))) throw new IllegalStateException("宿主拦截器列表签名不匹配");
        }
        System.out.println("[SPW Navidrome] Host ABI verified: Steam 1.19.2");
    }
    private static Object field(Object o,String name) throws Exception { Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o); }
    private static Slot slot(Object o,String name) throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);if(f.getType()!=List.class)throw new IllegalStateException("Invalid interceptor field");return new Slot(o,f);}
    @Override public void play(List<Song> songs,int index,String identity,LoopbackServer server) throws Exception {
        if(closed) throw new IllegalStateException("插件已停用");
        if(songs.isEmpty() || index<0 || index>=songs.size()) throw new IllegalArgumentException("请选择歌曲");
        if(controller==null) check();
        streams=server;
        if(proxy==null) {
            proxy=Proxy.newProxyInstance(interceptorType.getClassLoader(),new Class<?>[]{interceptorType},(p,m,args)->{
                if(m.getDeclaringClass()==Object.class) return switch(m.getName()){case "equals"->p==args[0];case "hashCode"->System.identityHashCode(p);default->"spw-navidrome-interceptor";};
                if(closed || args==null || args.length!=6) return null;
                String url=(String)location.invoke(args[1]);LoopbackServer active=streams;
                if(active==null || !active.owns(url)) return null;
                if(((Long)args[2])!=0L || ((Long)args[3])!=0L) return null;
                Object result=create.invoke(rangeFactory,url,args[4],owner);
                return box.invoke(null,result);
            });
            try {
                for(Slot s:slots) {List<Object> copy=new CopyOnWriteArrayList<>((List<?>)s.field.get(s.holder));copy.add(proxy);s.field.set(s.holder,copy);}
            } catch(Exception e) {removeProxy();throw e;}
        }
        List<Object> items=new ArrayList<>();
        for(Song song:songs) items.add(itemConstructor.newInstance("spw-navidrome-"+SubsonicClient.hash(identity+"\n"+song.id()),song.title(),song.artist(),song.album(),song.artist(),server.url(song.id())));
        controller.getClass().getMethod("setPlaybackQueue",List.class,int.class).invoke(controller,items,index);
        ownedItems.clear();ownedItems.addAll(items);
        controller.getClass().getMethod("prepare").invoke(controller);controller.getClass().getMethod("play").invoke(controller);
        System.out.println("[SPW Navidrome] Native queue submitted: count="+items.size()+", index="+index);
    }
    private void removeProxy() throws Exception {
        Exception failure=null;
        for(Slot s:slots) try {
            List<Object> remaining=new CopyOnWriteArrayList<>((List<?>)s.field.get(s.holder));
            remaining.removeIf(o->o==proxy);s.field.set(s.holder,remaining);
        } catch(Exception e) {failure=e;}
        proxy=null;if(failure!=null)throw failure;
    }
    @Override public String currentLocation() throws Exception {
        if(closed || streams==null)return null;
        Object item=currentItem.invoke(player);
        if(item==null)return null;
        String url=(String)location.invoke(item);
        if(!streams.owns(url))return null;
        // 等宿主本轮封面/歌词加载结束，避免随后被默认空值覆盖。
        Class<?> jobType=type("kotlinx.coroutines.Job");
        for(String name:new String[]{"onLoadedJob","onCurrentMediaItemDataRefreshedJob"}) {
            Object job=field(service,name);
            if(job!=null && !(Boolean)jobType.getMethod("isCompleted").invoke(job))return null;
        }
        return url;
    }
    @Override public Object metadataRevision() throws Exception {
        return service==null?null:new Revision(currentItem.invoke(player),field(service,"onLoadedJob"),field(service,"onCurrentMediaItemDataRefreshedJob"));
    }
    @Override public void invalidateMetadata(){synchronized(metadataCommitLock){metadataEpoch.incrementAndGet();}}
    @Override public boolean publishMetadata(String url,Object revision,byte[] cover,String lyrics) throws Exception {
        if(!url.equals(currentLocation()))return false;
        if(metadataTask!=null) {
            if(!metadataTask.isDone())return false;
            boolean applied=metadataTask.get();metadataTask=null;
            if(applied && url.equals(pendingLocation) && Objects.equals(revision,pendingRevision))return true;
        }
        if(presentation==null)presentation=new NativePresentation(loader,service);
        long generation=metadataEpoch.get();NativePresentation target=presentation;
        NativePresentation.Prepared prepared=target.prepare(cover,lyrics);
        pendingLocation=url;pendingRevision=revision;
        metadataTask=new java.util.concurrent.FutureTask<>(()->{
            NativePresentation.Current valid=()->!closed && metadataEpoch.get()==generation
                    && url.equals(currentLocation()) && Objects.equals(revision,metadataRevision());
            try {
                synchronized(metadataCommitLock) {
                    if(!valid.check())return false;
                    return target.publish(prepared,valid);
                }
            }finally{prepared.close();}
        });
        // 宿主切歌在 Main 上提交；校验与最终应用作为一个不挂起的 Main 任务。
        try {
            dispatchMain(metadataTask);
        }catch(Exception e){prepared.close();metadataTask=null;throw e;}
        return false;
    }
    private void dispatchMain(Runnable task)throws Exception {
        Object dispatcher=type("kotlinx.coroutines.Dispatchers").getMethod("getMain").invoke(null);
        Object context=type("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
        type("kotlinx.coroutines.CoroutineDispatcher").getMethod("dispatch",type("kotlin.coroutines.CoroutineContext"),Runnable.class)
                .invoke(dispatcher,context,task);
    }
    @Override public void detach() throws Exception {
        invalidateMetadata();metadataTask=null;
        try {
            NativePresentation oldPresentation=presentation;presentation=null;
            if(oldPresentation!=null)dispatchMain(()->{
                synchronized(metadataCommitLock){try{oldPresentation.clearOwned();}
                catch(Exception e){System.err.println("[SPW Navidrome] Native metadata cleanup failed");}}
            });
            if(controller!=null && streams!=null) {
                Object current=currentItem.invoke(player);
                if(current!=null && streams.owns((String)location.invoke(current))) controller.getClass().getMethod("stop").invoke(controller);
                // 仅删除本插件提交的条目，不清空宿主或其它模组队列。
                if(!ownedItems.isEmpty()) {
                    Object array=Array.newInstance(itemType,ownedItems.size());
                    for(int i=0;i<ownedItems.size();i++)Array.set(array,i,ownedItems.get(i));
                    controller.getClass().getMethod("removeMediaItems",array.getClass()).invoke(controller,array);
                }
            }
        } finally {streams=null;ownedItems.clear();if(proxy!=null)removeProxy();}
    }
    @Override public void close() throws Exception {closed=true;detach();}
}
