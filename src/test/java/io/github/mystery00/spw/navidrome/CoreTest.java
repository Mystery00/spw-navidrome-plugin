package io.github.mystery00.spw.navidrome;

/** 无测试框架依赖的行为回归入口。 */
public final class CoreTest {
    public static void main(String[] args) throws Exception {
        try {
            Class<?> client = Class.forName("io.github.mystery00.spw.navidrome.net.SubsonicClient");
            Object token = client.getMethod("token", String.class, String.class).invoke(null, "sesame", "c19b2d");
            check("26719a1196d2a940705a59634eb18eab".equals(token), "Subsonic authentication vector");
        } catch (ClassNotFoundException e) { throw new AssertionError("Subsonic authentication is not implemented", e); }
        System.out.println("PASS authentication vector");
        protocol();
        network();
        try { Class.forName("io.github.mystery00.spw.navidrome.stream.LoopbackServer"); }
        catch (ClassNotFoundException e) { throw new AssertionError("Loopback range server missing",e); }
        streaming();
        lifecycle();
        interceptorRemoval();
        lyrics();
        nativeMetadata();
        highResolutionCover();
    }
    /** 捕获缩略图请求和过小下载上限导致的原生大封面退化。 */
    private static void highResolutionCover() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/rest/getCoverArt",exchange->{
            String query=exchange.getRequestURI().getRawQuery();
            var size=java.util.regex.Pattern.compile("(?:^|&)size=(\\d+)(?:&|$)").matcher(query);
            int side=size.find()?Math.min(1024,Integer.parseInt(size.group(1))):1024;
            var image=new java.awt.image.BufferedImage(side,side,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var random=new java.util.Random(42);
            for(int y=0;y<side;y++)for(int x=0;x<side;x++)image.setRGB(x,y,random.nextInt());
            var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
            exchange.getResponseHeaders().set("Content-Type","image/png");
            exchange.sendResponseHeaders(200,bytes.size());
            try(var out=exchange.getResponseBody()){bytes.writeTo(out);}
        });
        server.start();
        try(var client=new io.github.mystery00.spw.navidrome.net.SubsonicClient(
                "http://127.0.0.1:"+server.getAddress().getPort(),"a","b".toCharArray(),true)) {
            byte[] bytes=client.cover("cover");
            var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            check(image.getWidth()==1024 && image.getHeight()==1024,"Native cover must retain 1024px source detail");
            check(bytes.length>2*1024*1024,"Detailed PNG must survive old thumbnail byte limit");
        }finally{server.stop(0);}
        System.out.println("PASS high resolution cover download");
    }
    private static void nativeMetadata() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("navidrome-metadata-test");
        var url=new java.util.concurrent.atomic.AtomicReference<String>();
        var delivered=new java.util.concurrent.CountDownLatch(1);
        var revision=new java.util.concurrent.atomic.AtomicInteger();
        var deliveries=new java.util.concurrent.atomic.AtomicInteger();
        var repeated=new java.util.concurrent.CountDownLatch(1);
        var host=(io.github.mystery00.spw.navidrome.host.HostAdapter)java.lang.reflect.Proxy.newProxyInstance(
                CoreTest.class.getClassLoader(),new Class<?>[]{io.github.mystery00.spw.navidrome.host.HostAdapter.class},(p,m,a)->{
                    switch(m.getName()) {
                        case "play" -> url.set(((io.github.mystery00.spw.navidrome.stream.LoopbackServer)a[3]).url("x"));
                        case "currentLocation" -> {return url.get();}
                        case "metadataRevision" -> {return revision.get();}
                        case "publishMetadata" -> {
                            check(url.get().equals(a[0]) && a[1].equals(revision.get()),"Metadata matches current revision");
                            check(a[3].equals("[00:02.100]测试\n"),"Actual lyrics payload reaches host");
                            if(deliveries.incrementAndGet()>1)repeated.countDown();delivered.countDown();return true;
                        }
                    }
                    return null;
                });
        try(MockServer server=new MockServer();AppSession session=new AppSession(host,root)) {
            var connected=new java.util.concurrent.CountDownLatch(1);
            session.connect(server.address(),"a","b".toCharArray(),true,s->connected.countDown(),s->{});
            check(connected.await(3,java.util.concurrent.TimeUnit.SECONDS),"Metadata session connection");
            server.mode="lyrics";
            session.play(java.util.List.of(new io.github.mystery00.spw.navidrome.model.Models.Song("x","t","a","b",1000,"")),0,s->{});
            check(delivered.await(4,java.util.concurrent.TimeUnit.SECONDS),"Current song metadata reaches host asynchronously");
            revision.incrementAndGet();
            check(repeated.await(3,java.util.concurrent.TimeUnit.SECONDS),"Same URL new host load must reapply metadata");
        } finally {java.nio.file.Files.delete(root);}
        System.out.println("PASS current native metadata delivery");
    }
    private static void lyrics() throws Exception {
        Class<?> cls=Class.forName("io.github.mystery00.spw.navidrome.model.Lyrics");
        var convert=cls.getMethod("toLrc",java.util.Map.class);
        var line=java.util.Map.<String,Object>of("start",62001L,"value","测试行");
        var timed=java.util.Map.<String,Object>of("synced",true,"offset",100L,"line",java.util.List.of(line));
        check(convert.invoke(null,timed).equals("[01:01.901]测试行\n"),"Lyrics milliseconds and positive offset");
        var plain=java.util.Map.<String,Object>of("synced",false,"line",java.util.List.of(line));
        check(convert.invoke(null,plain).equals("测试行\n"),"Plain lyrics must not gain fake timestamps");
        check(convert.invoke(null,java.util.Map.of()).equals(""),"Missing lyrics stay empty");
        try(MockServer server=new MockServer()) {
            server.mode="lyrics";
            var song=new io.github.mystery00.spw.navidrome.model.Models.Song("uuid/中文","title","artist","album",1000,"");
            try(var client=new io.github.mystery00.spw.navidrome.net.SubsonicClient(server.address(),"a","b".toCharArray(),true)) {
                check(client.lyrics(song).equals("[00:02.100]测试\n"),"Structured lyric endpoint and negative offset");
                check(server.query.get("id").equals(song.id()),"Lyrics opaque ID round trip");
            }
            server.lyricSupport=false;int before=server.structuredRequests;
            try(var client=new io.github.mystery00.spw.navidrome.net.SubsonicClient(server.address(),"a","b".toCharArray(),true)) {
                check(client.lyrics(song).equals("纯文本"),"Legacy lyrics fallback");
                check(server.structuredRequests==before,"No unsupported lyrics extension call");
            }
        }
        System.out.println("PASS lyrics timing and plain-text fallback");
    }
    public static final class Holder { public java.util.List<Object> list; Holder(java.util.List<Object> list){this.list=list;} }
    private static void interceptorRemoval() throws Exception {
        var adapter=new io.github.mystery00.spw.navidrome.host.Host1192("1.19.2",CoreTest.class.getClassLoader());
        Object ours=new Object(),other=new Object(),addedLater=new Object();Holder holder=new Holder(new java.util.ArrayList<>(java.util.List.of(other,ours,addedLater)));
        var proxy=adapter.getClass().getDeclaredField("proxy");proxy.setAccessible(true);proxy.set(adapter,ours);
        var slotsField=adapter.getClass().getDeclaredField("slots");slotsField.setAccessible(true);
        Class<?> slot=Class.forName("io.github.mystery00.spw.navidrome.host.Host1192$Slot");var constructor=slot.getDeclaredConstructors()[0];constructor.setAccessible(true);
        @SuppressWarnings("unchecked") var slots=(java.util.List<Object>)slotsField.get(adapter);
        slots.add(constructor.newInstance(holder,Holder.class.getField("list")));
        var remove=adapter.getClass().getDeclaredMethod("removeProxy");remove.setAccessible(true);remove.invoke(adapter);
        check(holder.list.size()==2 && holder.list.get(0)==other && holder.list.get(1)==addedLater,"Uninstall preserves both prior and later third party interceptors");
        System.out.println("PASS interceptor ownership cleanup");
    }
    private static void lifecycle() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("navidrome-session-test");
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var completed=new java.util.concurrent.CountDownLatch(1);
        var stopped=new java.util.concurrent.atomic.AtomicBoolean();var late=new java.util.concurrent.atomic.AtomicBoolean();
        var adapter=new io.github.mystery00.spw.navidrome.host.HostAdapter(){
            public void check(){}
            public void play(java.util.List<io.github.mystery00.spw.navidrome.model.Models.Song> songs,int index,String identity,io.github.mystery00.spw.navidrome.stream.LoopbackServer stream)throws Exception{entered.countDown();release.await(3,java.util.concurrent.TimeUnit.SECONDS);if(stopped.get())late.set(true);completed.countDown();}
            public void detach(){}public void close(){}
        };
        try(MockServer upstream=new MockServer();AppSession session=new AppSession(adapter,root)){
            var ready=new java.util.concurrent.CountDownLatch(1);
            session.connect(upstream.address(),"a","b".toCharArray(),true,s->ready.countDown(),s->{throw new AssertionError(s);});
            check(ready.await(3,java.util.concurrent.TimeUnit.SECONDS),"Session connection");
            session.play(java.util.List.of(new io.github.mystery00.spw.navidrome.model.Models.Song("x","t","a","b",1000,"")),0,s->{});
            check(entered.await(3,java.util.concurrent.TimeUnit.SECONDS),"Host task entered");
            Thread closer=new Thread(()->{session.close();stopped.set(true);});closer.start();
            Thread.sleep(150);release.countDown();closer.join(3000);
            check(completed.await(3,java.util.concurrent.TimeUnit.SECONDS),"Host call completed");
            check(!late.get(),"No host playback side effect after close returns");
        }finally{release.countDown();try(var files=java.nio.file.Files.list(root)){for(var f:files.toList())java.nio.file.Files.deleteIfExists(f);}java.nio.file.Files.delete(root);}
        System.out.println("PASS lifecycle playback barrier");
    }
    private static byte[] fetch(String url,String method,String range,int expected) throws Exception {
        var c=(java.net.HttpURLConnection)java.net.URI.create(url).toURL().openConnection(); c.setRequestMethod(method);c.setReadTimeout(3000);
        if(range!=null)c.setRequestProperty("Range",range);
        try {
            check(c.getResponseCode()==expected,"Expected HTTP "+expected+", got "+c.getResponseCode());
            if(expected==206)check(c.getHeaderField("Content-Range")!=null,"206 needs Content-Range");
            if(expected>=400)return new byte[0];
            try(var in=c.getInputStream()){return in.readAllBytes();}
        } finally {c.disconnect();}
    }
    private static void streaming() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("navidrome-cache-test");
        var errors=new java.util.concurrent.CopyOnWriteArrayList<String>();
        try (MockServer upstream=new MockServer();var client=new io.github.mystery00.spw.navidrome.net.SubsonicClient(upstream.address(),"a","b".toCharArray(),true);
             var cache=new io.github.mystery00.spw.navidrome.stream.AudioCache(root,8192);
             var proxy=new io.github.mystery00.spw.navidrome.stream.LoopbackServer(client,cache,errors::add)) {
            String url=proxy.url("uuid/音乐");
            upstream.audio[100]='{';
            check(fetch(url,"GET","bytes=100-199",206).length==100,"Range body size");
            check(upstream.query.get("id").equals("uuid/音乐"),"Route retains opaque song ID");
            check(fetch(url,"HEAD","bytes=100-199",206).length==0,"HEAD has no body");
            int count=upstream.requests;fetch(url,"GET","bytes=100-199",206);check(upstream.requests==count,"Verified range should hit cache");
            check(fetch(url,"GET","bytes=-10",206).length==10,"Suffix range");
            check(fetch(url,"GET","bytes=4000-",206).length==96,"Open ended range");
            fetch(url,"GET","bytes=4096-",416);fetch(url,"GET","bytes=1-2,4-5",416);
            upstream.mode="ignore";String ignored=proxy.url("ignored");check(fetch(ignored,"GET","bytes=100-199",200).length==4096,"Ignored Range remains honest 200");
            upstream.mode="xml";
            try { fetch(proxy.url("error"),"GET",null,502); } catch(java.io.IOException e) { throw new AssertionError("Audio error should return HTTP 502 instead of a broken HTTP exchange",e); }
            upstream.mode="bom";fetch(proxy.url("bom-error"),"GET",null,502);
            upstream.mode="truncate";
            String truncated=proxy.url("truncated");
            // 上游可能在响应头发出前就断开，此时代理应返回 502；已发出头则终止 200 正文。
            var broken=(java.net.HttpURLConnection)java.net.URI.create(truncated).toURL().openConnection();
            broken.setReadTimeout(3000);
            try {
                int status=broken.getResponseCode();check(status==200||status==502,"Truncated stream must fail before or during response");
                if(status==200)try(var body=broken.getInputStream()){
                    check(body.readAllBytes().length<upstream.audio.length,"Truncated stream cannot look complete");
                }catch(java.io.IOException expected){}
            }finally{broken.disconnect();}
            Thread.sleep(100);
            check(cache.get(io.github.mystery00.spw.navidrome.stream.AudioCache.key(client.identity(),"truncated",null))==null,"Truncated response must not enter cache");
            upstream.mode="range";
            try(var other=new io.github.mystery00.spw.navidrome.net.SubsonicClient(upstream.address(),"other","b".toCharArray(),true);
                var otherProxy=new io.github.mystery00.spw.navidrome.stream.LoopbackServer(other,cache,errors::add)){
                upstream.audio[150]='Z';byte[] bytes=fetch(otherProxy.url("uuid/音乐"),"GET","bytes=100-199",206);
                check(bytes[50]=='Z' && upstream.query.get("u").equals("other"),"Different account must request own audio instead of cached first account");
            }
            check(!io.github.mystery00.spw.navidrome.stream.AudioCache.key("a","x",null).equals(io.github.mystery00.spw.navidrome.stream.AudioCache.key("b","x",null)),"Account cache isolation");
            check(cache.size()<=8192,"Cache must honor capacity");cache.clear();check(cache.size()==0,"Clear cache");
        } finally {try(var f=java.nio.file.Files.list(root)){for(var p:f.toList())java.nio.file.Files.delete(p);}java.nio.file.Files.delete(root);}
        System.out.println("PASS real HTTP range, HEAD, cache, isolation, error payloads");
    }
    private static void network() throws Exception {
        try (MockServer server = new MockServer(); var client = new io.github.mystery00.spw.navidrome.net.SubsonicClient(server.address(),"用户 &x", "password".toCharArray(),true)) {
            var songs = client.search("中文 &/",50);
            check(songs.getFirst().id().equals("uuid/中文&42"),"String ID must remain unchanged");
            check(songs.getFirst().durationMs()==12000,"Seconds must convert to milliseconds");
            check(server.lastPath.equals("/navidrome/rest/search3"),"Subpath must survive request");
            check(server.query.get("query").equals("中文 &/") && server.query.get("u").equals("用户 &x"),"UTF-8 parameters must round trip");
            check(server.query.get("songOffset").equals("50"),"Search pagination offset");
            String salt = server.query.get("s"); client.ping(); check(!salt.equals(server.query.get("s")),"Fresh salt per request");
            server.mode="auth";
            try { client.ping(); throw new AssertionError("HTTP 200 protocol failure accepted"); }
            catch (io.github.mystery00.spw.navidrome.net.SubsonicClient.ApiException e) { check(e.getMessage().contains("认证失败") && !e.getMessage().contains("secret"),"Redacted actionable protocol error"); }
            server.mode="browse";
            check(client.albums(100).getFirst().id().equals("album-uuid") && server.query.get("offset").equals("100"),"Album paging");
            check(client.playlists().getFirst().id().equals("playlist-uuid"),"Playlist string ID");
            check(client.album("album-uuid").get(1).id().equals("s2"),"Album track order");
            check(client.playlist("playlist-uuid").getFirst().id().equals("s2"),"Playlist track order");
            check(client.song("s1").id().equals("s1"),"Song endpoint");
        }
        System.out.println("PASS encoding, IDs, time units, pagination, protocol errors");
    }
    private static void protocol() throws Exception {
        Class<?> cls = Class.forName("io.github.mystery00.spw.navidrome.net.SubsonicClient");
        try {
            var normalize = cls.getMethod("normalize", String.class, boolean.class);
            check(normalize.invoke(null, "https://music.example/navidrome", false).toString().equals("https://music.example/navidrome/"), "preserve server subpath");
            try { normalize.invoke(null, "http://music.example/", false); throw new AssertionError("HTTP requires explicit consent"); }
            catch (java.lang.reflect.InvocationTargetException expected) { }
        } catch (NoSuchMethodException e) { throw new AssertionError("Server address validation missing", e); }
    }
    public static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
