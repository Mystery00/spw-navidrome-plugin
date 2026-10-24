package io.github.mystery00.spw.navidrome.stream;

import io.github.mystery00.spw.navidrome.net.SubsonicClient;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** 只解析本实例登记的不透明路径；认证信息不离开上游客户端。 */
public final class LoopbackServer implements AutoCloseable {
    private final SubsonicClient client;
    private final AudioCache cache;
    private final Consumer<String> error;
    private final ServerSocket server;
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final Map<String,String> routes=new ConcurrentHashMap<>();
    private final Map<String,String> songs=new ConcurrentHashMap<>();
    private final ExecutorService pool=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"navidrome-stream");t.setDaemon(true);return t;});
    private volatile boolean closed;
    private static final Pattern CONTENT_RANGE=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    public LoopbackServer(SubsonicClient client,AudioCache cache,Consumer<String> error) throws IOException {
        this.client=client;this.cache=cache;this.error=error;
        server=new ServerSocket(); server.bind(new InetSocketAddress("127.0.0.1",0),16);
        Thread t=new Thread(this::accept,"navidrome-listener");t.setDaemon(true);t.start();
    }
    public synchronized String url(String id) {
        if(closed) throw new IllegalStateException("Stream server closed");
        return songs.computeIfAbsent(id,k->{String path="/audio/"+UUID.randomUUID();routes.put(path,k);return "http://127.0.0.1:"+server.getLocalPort()+path;});
    }
    public boolean owns(String url) { return songs.containsValue(url); }
    private void accept() {
        while(!closed) try {
            Socket s=server.accept();s.setSoTimeout(15000);sockets.add(s);
            try { pool.execute(()->serve(s)); } catch(RejectedExecutionException e) { sockets.remove(s);s.close(); }
        } catch(IOException e) { if(!closed) error.accept("本机流服务不可用，请重新连接"); }
    }
    private static String line(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        for(int i=0;i<limit;i++) { int c=in.read();if(c<0) throw new EOFException();if(c=='\n') return b.toString(StandardCharsets.ISO_8859_1).replace("\r","");b.write(c); }
        throw new IOException("Request header limit");
    }
    private void serve(Socket socket) {
        boolean sent=false;
        try {
            InputStream in=new BufferedInputStream(socket.getInputStream());OutputStream out=new BufferedOutputStream(socket.getOutputStream());
            String[] request=line(in,4096).split(" ");
            if(request.length!=3) { fail(out,400);return; }
            boolean head=request[0].equals("HEAD");
            if(!head && !request[0].equals("GET")) { fail(out,405);return; }
            Map<String,String> h=new HashMap<>(); int total=0;
            while(true) { String l=line(in,8192);total+=l.length();if(total>32768) throw new IOException("Header limit");if(l.isEmpty()) break;int sep=l.indexOf(':');if(sep<1) throw new IOException("Invalid header");String name=l.substring(0,sep).toLowerCase(Locale.ROOT);if(h.put(name,l.substring(sep+1).trim())!=null) throw new IOException("Duplicate header"); }
            String id=routes.get(request[1]);if(id==null) { fail(out,404);return; }
            // 不允许网页脚本通过跨源响应读取音频；也不接受代理型绝对地址。
            if(h.containsKey("origin")) { fail(out,403);return; }
            String range=h.get("range");ByteRange parsed;
            try { parsed=ByteRange.parse(range); } catch(IllegalArgumentException e) { fail(out,416);return; }
            String key=AudioCache.key(client.identity(),id,range);
            AudioCache.Entry hit=cache.get(key);
            if(hit!=null) {
                try(InputStream file=java.nio.file.Files.newInputStream(hit.path())) {
                    headers(out,hit.status(),hit.type(),hit.length(),hit.contentRange(),hit.ranges());sent=true;
                    if(!head) file.transferTo(out);out.flush();return;
                } catch(java.nio.file.NoSuchFileException ignored) { }
            }
            HttpURLConnection c=client.open("stream",Map.of("id",id,"format","raw"),range);
            try {
                // HEAD 使用同样的上游 GET 探测，避免服务器 HEAD 实现差异；绝不向下游发送正文。
                int status=c.getResponseCode();
                if(status==416) { String cr=c.getHeaderField("Content-Range");headers(out,416,"text/plain",0,cr!=null&&cr.matches("bytes \\*/\\d+")?cr:null,false);return; }
                if(status!=200 && status!=206) throw SubsonicClient.httpError(status);
                long length=c.getContentLengthLong();String cr=c.getHeaderField("Content-Range");long responseStart=0;
                boolean ranges="bytes".equalsIgnoreCase(c.getHeaderField("Accept-Ranges"));
                if(status==206) {
                    Matcher m=CONTENT_RANGE.matcher(cr==null?"":cr);
                    if(parsed==null || !m.matches()) throw new SubsonicClient.ApiException("音频协议错误：无效的部分响应");
                    long start=Long.parseLong(m.group(1)),end=Long.parseLong(m.group(2)),size=Long.parseLong(m.group(3));
                    long[] expected=parsed.resolve(size);
                    if(start!=expected[0] || end!=expected[1] || end<start || end>=size || (length>=0 && length!=end-start+1)) throw new SubsonicClient.ApiException("音频协议错误：响应区间与请求不一致");
                    length=end-start+1;ranges=true;responseStart=start;
                } else { cr=null; }
                String type=c.getContentType();if(type==null) type="application/octet-stream";
                String low=type.toLowerCase(Locale.ROOT);
                if(low.contains("json") || low.contains("xml") || low.startsWith("text/")) throw new SubsonicClient.ApiException("音频端点返回错误文档，请检查权限或歌曲是否存在");
                try(InputStream body=c.getInputStream()) {
                    byte[] prefix=body.readNBytes((int)(length>=0?Math.min(length,256):256));
                    String sample=new String(prefix,StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "").stripLeading();
                    // 非零偏移可恰好落在任意压缩字节上，不将片段起始字符当作文档头。
                    if(responseStart==0 && (sample.startsWith("<") || sample.startsWith("{") || sample.startsWith("["))) throw new SubsonicClient.ApiException("音频端点返回 XML/JSON 错误，已拒绝送入播放器");
                    if(length>0 && prefix.length==0) throw new EOFException();
                    headers(out,status,type,length,cr,ranges);sent=true;
                    if(head) return;
                    AudioCache.Writer writer=null;
                    try { writer=cache.begin(key,status,type,cr,ranges,length); } catch(IOException ignored) { }
                    try {
                        out.write(prefix);out.flush();if(writer!=null) writer.write(prefix,0,prefix.length);
                        long count=prefix.length;byte[] b=new byte[32768];int n;
                        while((n=body.read(b))!=-1) {
                            if(closed || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                            count+=n;if(length>=0 && count>length) throw new IOException("Unexpected response length");
                            out.write(b,0,n);out.flush();
                            if(writer!=null) try { writer.write(b,0,n); } catch(IOException ex) { writer.close();writer=null; }
                        }
                        if(length>=0 && count!=length) throw new EOFException();
                        if(writer!=null) writer.commit();
                    } finally { if(writer!=null) writer.close(); }
                }
            } finally { client.release(c); }
        } catch(Exception e) {
            if(!closed) {
                if(!sent) try { fail(socket.getOutputStream(),502); } catch(IOException ignored) { }
                error.accept(e instanceof SubsonicClient.ApiException ? e.getMessage() : "音频读取中断；请检查网络后重新点击播放");
                // socket 关闭会终止已发送的响应，绝不把错误正文拼到音频后面。
            }
        } finally { sockets.remove(socket);try{socket.close();}catch(IOException ignored){} }
    }
    private static void fail(OutputStream out,int code) throws IOException { headers(out,code,"text/plain",0,null,false); }
    private static void headers(OutputStream out,int status,String type,long length,String range,boolean ranges) throws IOException {
        StringBuilder s=new StringBuilder("HTTP/1.1 ").append(status).append(" ").append(switch(status){case 200->"OK";case 206->"Partial Content";case 416->"Range Not Satisfiable";default->"Error";}).append("\r\nConnection: close\r\nCache-Control: no-store\r\n");
        if(type.indexOf('\r')>=0 || type.indexOf('\n')>=0) type="application/octet-stream";
        s.append("Content-Type: ").append(type).append("\r\n");
        if(length>=0) s.append("Content-Length: ").append(length).append("\r\n");
        if(range!=null) s.append("Content-Range: ").append(range).append("\r\n");
        if(ranges) s.append("Accept-Ranges: bytes\r\n");
        out.write(s.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));out.flush();
    }
    public void cancelRequests() { sockets.forEach(s->{try{s.close();}catch(IOException ignored){}}); }
    @Override public synchronized void close() {
        closed=true;routes.clear();songs.clear();try{server.close();}catch(IOException ignored){}cancelRequests();pool.shutdownNow();
    }
}
