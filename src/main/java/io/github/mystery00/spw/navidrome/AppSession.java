package io.github.mystery00.spw.navidrome;

import io.github.mystery00.spw.navidrome.host.HostAdapter;
import io.github.mystery00.spw.navidrome.model.Models.Song;
import io.github.mystery00.spw.navidrome.net.SubsonicClient;
import io.github.mystery00.spw.navidrome.stream.*;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 账户代次隔离迟到结果；宿主调用只在单独串行线程上执行。 */
public final class AppSession implements AutoCloseable {
    private final HostAdapter host;
    private final AudioCache cache;
    private final AtomicLong epoch=new AtomicLong();
    private final ExecutorService network=Executors.newFixedThreadPool(3,r->daemon(r,"navidrome-network"));
    private final ScheduledExecutorService hostWorker=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"navidrome-host"));
    private final Map<String,Song> queueSongs=new HashMap<>();
    private final LinkedHashMap<String,NativeMetadata> metadata=new LinkedHashMap<>(32,0.75f,true);
    private record NativeMetadata(byte[] cover,String lyrics) { }
    private Future<?> metadataRequest;
    private String metadataPublished,metadataObserved;
    private Object metadataObservedRevision;
    private volatile SubsonicClient client,pending;
    private volatile LoopbackServer streams;
    private volatile boolean closed;
    private volatile String compatibility="正在检查宿主兼容性";
    private Consumer<String> notices=s->{};
    public AppSession(HostAdapter host,Path cachePath) throws Exception {
        this.host=host;this.cache=new AudioCache(cachePath,512L*1024*1024);
        hostWorker.execute(()->{
            try {host.check();compatibility="SPW 1.19.2 原生播放适配已就绪";}
            catch(Exception|LinkageError e) {compatibility="原生播放不可用："+safe(e);}
            notice(compatibility);
        });
        hostWorker.scheduleWithFixedDelay(this::refreshMetadata,500,500,TimeUnit.MILLISECONDS);
    }
    /** 只为当前曲目取元数据；下载不占宿主线程，账户失效后丢弃迟到结果。 */
    private synchronized void refreshMetadata() {
        if(closed || client==null)return;
        try {
            String url=host.currentLocation();
            Object revision=host.metadataRevision();
            if(!Objects.equals(url,metadataObserved) || !Objects.equals(revision,metadataObservedRevision)){
                metadataObserved=url;metadataObservedRevision=revision;metadataPublished=null;
            }
            Song song=queueSongs.get(url);
            if(song==null){metadataPublished=null;return;}
            if(url.equals(metadataPublished))return;
            NativeMetadata ready=metadata.get(url);
            if(ready!=null) {
                if(host.publishMetadata(url,revision,ready.cover(),ready.lyrics()))metadataPublished=url;
                return;
            }
            if(metadataRequest!=null && !metadataRequest.isDone())return;
            SubsonicClient current=client;long generation=epoch.get();
            metadataRequest=network.submit(()->{
                byte[] cover=null;String lyrics="";
                try{if(!song.coverId().isBlank())cover=current.cover(song.coverId());}
                catch(Exception e){System.err.println("[SPW Navidrome] Native cover fetch failed");}
                try{lyrics=current.lyrics(song);}
                catch(Exception e){System.err.println("[SPW Navidrome] Native lyrics fetch failed");}
                synchronized(AppSession.this) {
                    if(closed || epoch.get()!=generation || !queueSongs.containsKey(url))return;
                    metadata.put(url,new NativeMetadata(cover,lyrics));
                    // 每项封面最多 2 MiB、歌词最多 256 KiB，最多保留 24 首。
                    while(metadata.size()>24)metadata.remove(metadata.keySet().iterator().next());
                }
            });
        }catch(Exception|LinkageError e) {
            System.err.println("[SPW Navidrome] Native metadata update failed: "+e.getClass().getSimpleName());
            // 避免不兼容时每半秒反复调用宿主接口，重新提交队列可再尝试。
            queueSongs.clear();notice("原生封面或歌词接入失败，音频播放不受影响");
        }
    }
    private static Thread daemon(Runnable r,String name){Thread t=new Thread(r,name);t.setDaemon(true);return t;}
    public void onNotice(Consumer<String> listener){notices=listener;}
    private void notice(String text){SwingUtilities.invokeLater(()->{if(!closed)notices.accept(text);});}
    public String compatibility(){return compatibility;}
    public boolean connected(){return client!=null;}
    public long epoch(){return epoch.get();}
    public long cacheBytes(){return cache.size();}
    public void connect(String address,String username,char[] password,boolean http,Consumer<String> success,Consumer<String> failure) {
        disconnect();long generation=epoch.get();
        SubsonicClient candidate;
        try{candidate=new SubsonicClient(address,username,password,http);}catch(Exception e){failure.accept(safe(e));return;}
        finally{Arrays.fill(password,'\0');}
        pending=candidate;
        network.execute(()->{
            LoopbackServer server=null;
            try {
                String version=candidate.ping();
                server=new LoopbackServer(candidate,cache,message->{if(epoch.get()==generation)notice(message);});
                synchronized(this){
                    if(closed || epoch.get()!=generation){server.close();candidate.close();return;}
                    client=candidate;streams=server;pending=null;
                }
                deliver(generation,()->success.accept("已连接："+version));
            }catch(Exception e){if(server!=null)server.close();candidate.close();if(pending==candidate)pending=null;deliver(generation,()->failure.accept(safe(e)));}
        });
    }
    public synchronized void disconnect(){
        host.invalidateMetadata();
        queueSongs.clear();metadata.clear();metadataPublished=null;metadataObserved=null;
        epoch.incrementAndGet();SubsonicClient old=client;client=null;SubsonicClient waiting=pending;pending=null;
        LoopbackServer server=streams;streams=null;
        if(waiting!=null)waiting.close();if(old!=null)old.close();
        // 先停旧原生流，再关闭本机入口；后续播放在同一宿主线程排队。
        if(!hostWorker.isShutdown())hostWorker.execute(()->{try{host.detach();}catch(Exception e){System.err.println("[SPW Navidrome] Host detach failed: "+e.getClass().getSimpleName());}finally{if(server!=null)server.close();}});
        try{cache.clear();}catch(Exception e){notice("缓存清理失败，请检查磁盘权限");}
    }
    @FunctionalInterface public interface Request<T>{T run(SubsonicClient c)throws Exception;}
    public <T> Future<?> request(Request<T> work,Consumer<T> success,Consumer<String> failure){
        SubsonicClient current=client;long generation=epoch.get();
        if(closed || current==null){failure.accept("请先连接服务器");return CompletableFuture.completedFuture(null);}
        return network.submit(()->{
            try{T value=work.run(current);deliver(generation,()->success.accept(value));}
            catch(Exception e){deliver(generation,()->failure.accept(safe(e)));}
        });
    }
    private void deliver(long generation,Runnable callback){SwingUtilities.invokeLater(()->{if(!closed && generation==epoch.get())callback.run();});}
    public void play(List<Song> songs,int index,Consumer<String> status){
        SubsonicClient current=client;LoopbackServer server=streams;long generation=epoch.get();
        if(current==null || server==null){status.accept("请先连接服务器");return;}
        List<Song> snapshot=List.copyOf(songs);status.accept("正在提交原生播放队列…");
        hostWorker.execute(()->{
            // 提交与失效共享屏障；退出返回后不会再有旧队列的 prepare/play。
            synchronized(AppSession.this){
                if(closed || epoch.get()!=generation)return;
                try{host.play(snapshot,index,current.identity(),server);
                    queueSongs.clear();metadataPublished=null;
                    for(Song song:snapshot)queueSongs.put(server.url(song.id()),song);
                    deliver(generation,()->status.accept("已提交 SPW 原生队列（"+snapshot.size()+" 首）；请在原生播放栏控制播放"));}
                catch(Exception|LinkageError e){deliver(generation,()->status.accept("播放失败："+safe(e)));}
            }
        });
    }
    public void clearCache(Consumer<String> result){network.execute(()->{try{cache.clear();notice("音频缓存已清理");}catch(Exception e){notice("缓存清理失败，请检查磁盘权限");}});}
    public static String safe(Throwable error){
        if(error instanceof SubsonicClient.ApiException || error instanceof IllegalArgumentException || error instanceof IllegalStateException)return error.getMessage();
        if(error instanceof java.io.IOException e)return SubsonicClient.connectionError(e).getMessage();
        return "操作失败（"+error.getClass().getSimpleName()+"），请检查宿主版本或重试";
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;disconnect();network.shutdownNow();
        hostWorker.execute(()->{try{host.close();}catch(Exception e){System.err.println("[SPW Navidrome] Host cleanup failed");}});hostWorker.shutdown();
        try{cache.close();}catch(Exception ignored){}
    }
}
