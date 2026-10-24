package vip.mystery0.spw.navidrome.host;

import vip.mystery0.spw.navidrome.model.Models.Song;
import vip.mystery0.spw.navidrome.stream.LoopbackServer;
import java.util.List;

/** 唯一宿主边界；协议与窗口不认识混淆类。 */
public interface HostAdapter extends AutoCloseable {
    void check() throws Exception;

    void play(List<Song> songs, int index, String identity, LoopbackServer server) throws Exception;

    void detach() throws Exception;

    default String currentLocation() throws Exception {
        return null;
    }

    record PlaybackSnapshot(
            String location, Object incarnation, boolean playing, long positionMs) {}

    default PlaybackSnapshot playbackSnapshot() throws Exception {
        return null;
    }

    default Object metadataRevision() throws Exception {
        return null;
    }

    default void invalidateMetadata() {}

    default boolean publishMetadata(String location, Object revision, byte[] cover, String lyrics)
            throws Exception {
        return false;
    }

    @Override
    void close() throws Exception;
}
