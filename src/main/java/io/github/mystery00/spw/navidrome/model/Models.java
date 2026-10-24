package io.github.mystery00.spw.navidrome.model;

import io.github.mystery00.spw.navidrome.net.Json;
import java.util.Map;

public final class Models {
    private Models() { }
    public record Song(String id, String title, String artist, String album, long durationMs, String coverId) {
        public static Song from(Map<String,Object> m) {
            String id = Json.str(m,"id");
            if (id.isBlank()) throw new IllegalArgumentException("Missing song ID");
            long seconds = Json.number(m,"duration");
            return new Song(id, Json.str(m,"title"), Json.str(m,"artist"), Json.str(m,"album"), Math.multiplyExact(Math.max(0,seconds),1000), Json.str(m,"coverArt"));
        }
    }
    public record Collection(String id, String name, String artist, long count, String coverId) {
        public static Collection from(Map<String,Object> m) {
            return new Collection(Json.str(m,"id"), Json.str(m,"name"), Json.str(m,"artist"), Json.number(m,"songCount"), Json.str(m,"coverArt"));
        }
        @Override public String toString() { return name + (artist.isBlank() ? "" : " — " + artist) + "（" + count + " 首）"; }
    }
}
