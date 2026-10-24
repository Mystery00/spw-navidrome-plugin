package vip.mystery0.spw.navidrome;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 测试专用模块，绝不进入插件安装包。 */
final class MockServer implements AutoCloseable {
    final HttpServer server;
    volatile String mode = "range";
    volatile String lastPath;
    volatile Map<String,String> query = Map.of();
    volatile String lastRange;
    volatile int requests;
    volatile boolean lyricSupport=true;
    volatile int structuredRequests;
    final byte[] audio = new byte[4096];
    MockServer() throws Exception {
        Arrays.fill(audio,(byte)65); audio[0]='I'; audio[1]='D'; audio[2]='3';
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x -> {
            try {
                requests++; lastPath = x.getRequestURI().getPath();
                Map<String,String> q = new HashMap<>();
                for (String part : x.getRequestURI().getRawQuery().split("&")) { String[] kv=part.split("=",2); q.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv.length>1?kv[1]:"",StandardCharsets.UTF_8)); }
                query=q;
                if (!lastPath.endsWith("/stream")) {
                    String result = mode.equals("auth") ? "{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":40,\"message\":\"secret server text\"}}}" : "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\",\"searchResult3\":{\"song\":[{\"id\":\"uuid/中文&42\",\"title\":\"曲目\",\"duration\":12}]}}}";
                    if(mode.equals("browse")) {
                        String payload=switch(lastPath.substring(lastPath.lastIndexOf('/')+1)) {
                            case "getAlbumList2" -> "\"albumList2\":{\"album\":[{\"id\":\"album-uuid\",\"name\":\"专辑\",\"songCount\":2}]}";
                            case "getPlaylists" -> "\"playlists\":{\"playlist\":[{\"id\":\"playlist-uuid\",\"name\":\"歌单\",\"songCount\":2}]}";
                            case "getAlbum" -> "\"album\":{\"song\":[{\"id\":\"s1\",\"title\":\"一\"},{\"id\":\"s2\",\"title\":\"二\"}]}";
                            case "getPlaylist" -> "\"playlist\":{\"entry\":[{\"id\":\"s2\",\"title\":\"二\"},{\"id\":\"s1\",\"title\":\"一\"}]}";
                            default -> "\"song\":{\"id\":\"s1\",\"title\":\"一\"}";
                        };
                        result="{\"subsonic-response\":{\"status\":\"ok\","+payload+"}}";
                    }
                    if(mode.equals("lyrics")) {
                        String payload=switch(lastPath.substring(lastPath.lastIndexOf('/')+1)) {
                            case "getOpenSubsonicExtensions" -> "\"openSubsonicExtensions\":"+(lyricSupport?"[{\"name\":\"songLyrics\",\"versions\":[1]}]":"[]");
                            case "getLyricsBySongId" -> {structuredRequests++;yield "\"lyricsList\":{\"structuredLyrics\":[{\"synced\":true,\"offset\":-100,\"line\":[{\"start\":2000,\"value\":\"测试\"}]}]}";}
                            case "getLyrics" -> "\"lyrics\":{\"value\":\"纯文本\"}";
                            default -> "\"version\":\"1.16.1\"";
                        };
                        result="{\"subsonic-response\":{\"status\":\"ok\","+payload+"}}";
                    }
                    byte[] body=result.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type","application/json"); x.sendResponseHeaders(200,body.length); x.getResponseBody().write(body); return;
                }
                lastRange=x.getRequestHeaders().getFirst("Range");
                if (mode.equals("xml") || mode.equals("bom")) {
                    byte[] b=((mode.equals("bom")?"\ufeff":"")+"<subsonic-response status=\"failed\"><error code=\"40\"/></subsonic-response>").getBytes(StandardCharsets.UTF_8);
                    x.getResponseHeaders().set("Content-Type","application/octet-stream"); x.sendResponseHeaders(200,b.length); x.getResponseBody().write(b); return;
                }
                x.getResponseHeaders().set("Content-Type","audio/mpeg");
                int start=0,end=audio.length-1,status=200;
                if (lastRange != null && !mode.equals("ignore")) {
                    String[] r=lastRange.substring(6).split("-",-1);
                    if (r[0].isEmpty()) start=Math.max(0,audio.length-Integer.parseInt(r[1]));
                    else { start=Integer.parseInt(r[0]); if (!r[1].isEmpty()) end=Math.min(end,Integer.parseInt(r[1])); }
                    if (start>=audio.length) { x.getResponseHeaders().set("Content-Range","bytes */4096"); x.sendResponseHeaders(416,-1); return; }
                    status=206; x.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/4096");
                    x.getResponseHeaders().set("Accept-Ranges","bytes");
                }
                x.sendResponseHeaders(status,end-start+1);
                x.getResponseBody().write(audio,start,mode.equals("truncate")?20:end-start+1);
            } finally { x.close(); }
        }); server.start();
    }
    String address() { return "http://127.0.0.1:"+server.getAddress().getPort()+"/navidrome/"; }
    public void close() { server.stop(0); }
}
