package io.github.mystery00.spw.navidrome.net;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import io.github.mystery00.spw.navidrome.model.Models;

public final class SubsonicClient implements AutoCloseable {
    private final URI base;
    private final String username;
    private char[] password;
    private final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile Boolean songLyrics;
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int PAGE_SIZE = 50;
    public static final class ApiException extends IOException {
        public ApiException(String message) { super(message); }
    }
    public SubsonicClient(String address, String username, char[] password, boolean allowHttp) {
        base = normalize(address, allowHttp);
        if (username.isBlank() || password.length == 0) throw new IllegalArgumentException("请输入用户名和密码");
        this.username = username; this.password = password.clone();
    }
    public static URI normalize(String address, boolean allowHttp) {
        try {
            URI u = URI.create(address.trim());
            if (u.getHost() == null || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null
                    || !("https".equalsIgnoreCase(u.getScheme()) || (allowHttp && "http".equalsIgnoreCase(u.getScheme()))))
                throw new IllegalArgumentException();
            String path = u.getRawPath(); if (path == null || path.isEmpty()) path = "/";
            if (!path.endsWith("/")) path += "/";
            int port = u.getPort();
            String host = u.getHost().toLowerCase(Locale.ROOT);
            return URI.create(u.getScheme().toLowerCase(Locale.ROOT)+"://"+host+((port == -1 || (port == 443 && u.getScheme().equalsIgnoreCase("https")) || (port == 80 && u.getScheme().equalsIgnoreCase("http"))) ? "" : ":"+port)+path).normalize();
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("服务器地址无效；请输入 HTTPS 地址，局域网 HTTP 需勾选允许，且不要包含账号、查询参数或片段"); }
    }
    public String identity() { return hash(base.toASCIIString()+"\n"+username); }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private synchronized URL endpoint(String method, Map<String,String> params) throws IOException {
        if (closed) throw new ApiException("连接已关闭，请重新连接");
        byte[] bytes = new byte[16]; RANDOM.nextBytes(bytes); String salt = HexFormat.of().formatHex(bytes);
        Map<String,String> q = new LinkedHashMap<>(params);
        q.put("u",username); q.put("s",salt); q.put("t",token(new String(password),salt));
        q.put("v","1.16.1"); q.put("c","spw-navidrome"); q.put("f","json");
        StringJoiner query = new StringJoiner("&");
        q.forEach((k,v) -> query.add(URLEncoder.encode(k,StandardCharsets.UTF_8)+"="+URLEncoder.encode(v,StandardCharsets.UTF_8)));
        return URI.create(base.toASCIIString()+"rest/"+method+"?"+query).toURL();
    }
    public HttpURLConnection open(String method, Map<String,String> params, String range) throws IOException {
        HttpURLConnection c = (HttpURLConnection)endpoint(method,params).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept-Encoding","identity"); c.setRequestProperty("User-Agent","spw-navidrome/0.1.0");
        if (range != null) c.setRequestProperty("Range",range);
        synchronized(this) { if (closed) { c.disconnect(); throw new ApiException("连接已关闭"); } connections.add(c); }
        return c;
    }
    public void release(HttpURLConnection c) { connections.remove(c); c.disconnect(); }
    public Map<String,Object> request(String method, Map<String,String> params) throws IOException {
        HttpURLConnection c = open(method,params,null);
        try {
            int status = c.getResponseCode();
            if (status != 200) throw httpError(status);
            byte[] bytes;
            try(InputStream in = c.getInputStream()) { bytes = readBounded(in, 8*1024*1024); }
            Map<String,Object> root;
            try { root = Json.object(Json.object(Json.parse(new String(bytes,StandardCharsets.UTF_8))).get("subsonic-response")); }
            catch (RuntimeException e) { throw new ApiException("协议错误：服务器没有返回合法的 Subsonic JSON，请核对地址子路径"); }
            if ("failed".equals(Json.str(root,"status"))) {
                long code = Json.number(Json.object(root.get("error")),"code");
                throw new ApiException(switch((int)code) {
                    case 40, 41 -> "认证失败：请检查用户名和密码（服务器代码 " + code + "）";
                    case 50 -> "无权访问该资源（服务器代码 50）";
                    case 70 -> "歌曲或资源已不存在（服务器代码 70）";
                    default -> "Subsonic 协议错误（代码 " + code + "），请检查服务器兼容性";
                });
            }
            if (!"ok".equals(Json.str(root,"status"))) throw new ApiException("协议错误：缺少成功状态，请核对服务器地址");
            return root;
        } catch (ApiException e) { throw e; }
        catch (IOException e) { throw connectionError(e); }
        finally { release(c); }
    }
    public String ping() throws IOException {
        var r = request("ping",Map.of());
        return Json.str(r,"type")+" "+Json.str(r,"serverVersion")+" / Subsonic "+Json.str(r,"version");
    }
    public List<Models.Collection> albums(int offset) throws IOException {
        return Json.objects(Json.object(request("getAlbumList2",Map.of("type","alphabeticalByName","size","50","offset",Integer.toString(offset))).get("albumList2")).get("album")).stream().map(Models.Collection::from).toList();
    }
    public List<Models.Song> album(String id) throws IOException { return songs("getAlbum","album","song",id); }
    public List<Models.Song> playlist(String id) throws IOException { return songs("getPlaylist","playlist","entry",id); }
    private List<Models.Song> songs(String method,String root,String list,String id) throws IOException {
        return Json.objects(Json.object(request(method,Map.of("id",id)).get(root)).get(list)).stream().map(Models.Song::from).toList();
    }
    public Models.Song song(String id) throws IOException { return Models.Song.from(Json.object(request("getSong",Map.of("id",id)).get("song"))); }
    public List<Models.Collection> playlists() throws IOException {
        return Json.objects(Json.object(request("getPlaylists",Map.of()).get("playlists")).get("playlist")).stream().map(Models.Collection::from).toList();
    }
    public List<Models.Song> search(String query,int offset) throws IOException {
        return Json.objects(Json.object(request("search3",Map.of("query",query,"songCount","50","songOffset",Integer.toString(offset),"albumCount","0","artistCount","0")).get("searchResult3")).get("song")).stream().map(Models.Song::from).toList();
    }
    public byte[] cover(String id) throws IOException {
        HttpURLConnection c = open("getCoverArt",Map.of("id",id,"size","1024"),null);
        try {
            if (c.getResponseCode() != 200) throw httpError(c.getResponseCode());
            String type = c.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("image/")) throw new ApiException("封面不可用");
            try(InputStream in = c.getInputStream()) { return readBounded(in,4*1024*1024); }
        } finally { release(c); }
    }
    public String lyrics(Models.Song song) throws IOException {
        if(songLyrics==null) {
            songLyrics=false;
            try {
                var extensions=request("getOpenSubsonicExtensions",Map.of());
                songLyrics=Json.objects(extensions.get("openSubsonicExtensions")).stream()
                        .anyMatch(e->"songLyrics".equals(Json.str(e,"name")));
            } catch(ApiException ignored) { /* 老服务器可能没有能力探测端点。 */ }
        }
        if(songLyrics) {
            var root=request("getLyricsBySongId",Map.of("id",song.id()));
            var tracks=Json.objects(Json.object(root.get("lyricsList")).get("structuredLyrics"));
            // 优先同步歌词，保留服务器顺序，不拼接不同语言的时间轴。
            for(boolean synced:new boolean[]{true,false})for(var track:tracks) {
                if(Boolean.TRUE.equals(track.get("synced"))!=synced)continue;
                String text=io.github.mystery00.spw.navidrome.model.Lyrics.toLrc(track);if(!text.isBlank())return text;
            }
            return "";
        }
        String text=Json.str(Json.object(request("getLyrics",Map.of("artist",song.artist(),"title",song.title())).get("lyrics")),"value");
        if(text.length()>262144)throw new ApiException("歌词内容过大");
        return text;
    }
    public static byte[] readBounded(InputStream in,int limit) throws IOException {
        byte[] b = in.readNBytes(limit+1); if (b.length > limit) throw new ApiException("服务器响应过大"); return b;
    }
    public static ApiException httpError(int status) {
        return new ApiException(status == 401 || status == 403 ? "认证失败或没有资源权限（HTTP "+status+"）" : "服务器返回 HTTP "+status+"，请核对地址、资源和服务器状态");
    }
    public static ApiException connectionError(IOException e) {
        if (e instanceof ApiException a) return a;
        if (e instanceof javax.net.ssl.SSLException) return new ApiException("TLS 校验失败：请检查服务器证书和系统时间");
        if (e instanceof SocketTimeoutException) return new ApiException("连接或读取超时，请检查网络后重试");
        return new ApiException("无法连接或读取中断，请检查服务器地址及网络后重试");
    }
    @Override public synchronized void close() {
        closed = true; Arrays.fill(password,'\0');
        connections.forEach(HttpURLConnection::disconnect); connections.clear();
    }
    public static String token(String password, String salt) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest((password + salt).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
