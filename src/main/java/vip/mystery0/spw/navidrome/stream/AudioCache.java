package vip.mystery0.spw.navidrome.stream;

import vip.mystery0.spw.navidrome.net.SubsonicClient;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 缓存完整响应区间；从不创建稀疏文件。容量包含正在写入的保留空间。 */
public final class AudioCache implements AutoCloseable {
    public static final long MAX_SEGMENT = 16L * 1024 * 1024;
    private final Path root;
    private final long capacity;
    private long reserved;
    private long generation;
    private boolean closed;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public record Entry(
            Path path, int status, String type, String contentRange, boolean ranges, long length) {}

    public AudioCache(Path root, long capacity) throws IOException {
        this.root = root;
        this.capacity = capacity;
        Files.createDirectories(root);
        // 旧会话区间不跨重启复用，避免服务器文件更换后误用过期内容。
        try (var files = Files.list(root)) {
            for (Path p : files.toList())
                if (p.getFileName().toString().matches("[a-f0-9-]+\\.(part|cache)"))
                    Files.deleteIfExists(p);
        }
    }

    public static String key(String identity, String song, String range) {
        return SubsonicClient.hash(
                identity
                        + "\n"
                        + song.length()
                        + ":"
                        + song
                        + "\nraw\n0\n"
                        + (range == null ? "full" : range));
    }

    public synchronized Entry get(String key) {
        Entry e = entries.get(key);
        if (e != null && Files.isRegularFile(e.path())) return e;
        entries.remove(key);
        return null;
    }

    public synchronized long size() {
        return entries.values().stream().mapToLong(Entry::length).sum() + reserved;
    }

    public synchronized Writer begin(
            String key, int status, String type, String range, boolean ranges, long length)
            throws IOException {
        if (closed || length <= 0 || length > MAX_SEGMENT || length > capacity) return null;
        while (size() + length > capacity && !entries.isEmpty()) {
            String oldest = entries.keySet().iterator().next();
            Entry old = entries.remove(oldest);
            Files.deleteIfExists(old.path());
        }
        if (size() + length > capacity) return null;
        Path temp = root.resolve(UUID.randomUUID() + ".part");
        OutputStream out = Files.newOutputStream(temp);
        reserved += length;
        return new Writer(
                key, temp, out, new Entry(null, status, type, range, ranges, length), generation);
    }

    public synchronized void clear() throws IOException {
        generation++;
        IOException failure = null;
        for (Entry e : entries.values())
            try {
                Files.deleteIfExists(e.path());
            } catch (IOException ex) {
                failure = ex;
            }
        entries.clear();
        if (failure != null) throw failure;
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        clear();
    }

    public final class Writer implements AutoCloseable {
        private final String key;
        private final Path temp;
        private final OutputStream out;
        private final Entry meta;
        private final long epoch;
        private long written;
        private boolean finished;

        private Writer(String key, Path temp, OutputStream out, Entry meta, long epoch) {
            this.key = key;
            this.temp = temp;
            this.out = out;
            this.meta = meta;
            this.epoch = epoch;
        }

        public void write(byte[] b, int offset, int count) throws IOException {
            out.write(b, offset, count);
            written += count;
        }

        public void commit() throws IOException {
            out.close();
            synchronized (AudioCache.this) {
                if (finished) return;
                if (!closed && epoch == generation && written == meta.length()) {
                    Path dst = root.resolve(UUID.randomUUID() + ".cache");
                    Files.move(temp, dst, StandardCopyOption.ATOMIC_MOVE);
                    Entry old =
                            entries.put(
                                    key,
                                    new Entry(
                                            dst,
                                            meta.status(),
                                            meta.type(),
                                            meta.contentRange(),
                                            meta.ranges(),
                                            written));
                    if (old != null) Files.deleteIfExists(old.path());
                }
            }
            close();
        }

        @Override
        public void close() throws IOException {
            try {
                out.close();
            } finally {
                synchronized (AudioCache.this) {
                    if (!finished) {
                        reserved -= meta.length();
                        finished = true;
                    }
                }
                Files.deleteIfExists(temp);
            }
        }
    }
}
