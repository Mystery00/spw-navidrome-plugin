package vip.mystery0.spw.navidrome;

import java.util.ArrayList;
import java.util.List;
import vip.mystery0.spw.navidrome.playback.PlaybackTracker;
import vip.mystery0.spw.navidrome.net.SubsonicClient;

/** 使用虚拟时钟验证收听计次，不等待真实歌曲播放。 */
public final class PlaybackReportingTest {
    public static void run() throws Exception {
        protocol();
        thresholds();
        interruptionAndSeek();
        repeatAndReset();
        sessionIntegration(false);
        sessionIntegration(true);
        System.out.println("PASS playback reporting protocol and listening lifecycle");
    }

    private static void protocol() throws Exception {
        try (var server = new MockServer();
                var client =
                        new SubsonicClient(
                                server.address(), "listener", "test".toCharArray(), true)) {
            client.scrobble("uuid/中文&42", 1700000000123L, false);
            check(server.lastPath.endsWith("/scrobble"), "Scrobble endpoint");
            check("uuid/中文&42".equals(server.query.get("id")), "Opaque song ID");
            check("1700000000123".equals(server.query.get("time")), "Epoch milliseconds");
            check(
                    "false".equals(server.query.get("submission")),
                    "Now playing is not a play count");
            check(
                    server.query.containsKey("t") && !server.query.containsKey("p"),
                    "Token authentication");
            client.scrobble("uuid/中文&42", 1700000000123L, true);
            check("true".equals(server.query.get("submission")), "Play count submission");
            server.mode = "auth";
            try {
                client.scrobble("x", 1, true);
                throw new AssertionError("Rejected scrobble accepted");
            } catch (SubsonicClient.ApiException expected) {
                check(!expected.getMessage().contains("secret server text"), "Sanitized response");
            }
        }
    }

    private static void thresholds() {
        Harness h = new Harness();
        h.sample("a", true, 0, 120000);
        check(h.events.size() == 1 && !h.events.getFirst().submission(), "Now playing once");
        h.advance("a", 59, 120000);
        check(h.submissions() == 0, "Below half duration");
        h.advance("a", 1, 120000);
        check(h.submissions() == 1, "Half duration reached");
        h.advance("a", 120, 120000);
        check(h.submissions() == 1, "One submission per play");
        h.tracker.suspend();
        h.advance("a", 60, 120000);
        check(h.submissions() == 1, "Inconsistent snapshots do not duplicate a submission");
        h = new Harness();
        h.sample("a", true, 0, 3600000);
        h.advance("a", 239, 3600000);
        check(h.submissions() == 0, "Below four minutes");
        h.advance("a", 1, 3600000);
        check(h.submissions() == 1, "Four minute cap");
        h = new Harness();
        h.sample("unknown", true, 0, 0);
        h.advance("unknown", 240, 0);
        check(h.submissions() == 1, "Unknown duration uses four minutes");
    }

    private static void interruptionAndSeek() {
        Harness h = new Harness();
        h.sample("a", false, 0, 120000);
        check(h.events.isEmpty(), "Loading is not playing");
        h.sample("a", true, 0, 120000);
        h.advance("a", 20, 120000);
        h.sample("a", false, h.position, 120000);
        h.time += 600000;
        h.sample("a", false, h.position, 120000);
        h.sample("a", true, h.position, 120000);
        h.time += 1000;
        h.sample("a", true, 110000, 120000);
        check(h.submissions() == 0, "Pause and seek do not count");
        h.time += 1000;
        h.sample("a", true, 10000, 120000);
        h.advance("a", 39, 120000);
        check(h.submissions() == 0, "Backward seek does not reset or count");
        h.advance("a", 1, 120000);
        check(h.submissions() == 1, "Resumed listening accumulates");
        h = new Harness();
        h.sample("a", true, 0, 120000);
        h.time += 600000;
        h.sample("a", true, 600000, 120000);
        check(h.submissions() == 0, "Sleep gap is not listening");
    }

    private static void repeatAndReset() {
        Harness h = new Harness();
        h.sample("a", true, 0, 10000);
        h.advance("a", 5, 10000);
        h.sample("b", true, 0, 10000);
        h.sample("a", true, 0, 10000);
        h.advance("a", 5, 10000);
        check(h.submissions() == 2, "Returning after another song starts a new play");
        h.tracker.reset();
        h.sample("a", true, 0, 10000);
        h.advance("a", 5, 10000);
        check(h.submissions() == 3, "Account reset forgets old playback");
        h.sample(null, false, 0, 0);
        h.sample("a", true, 0, 10000);
        h.advance("a", 5, 10000);
        check(h.submissions() == 4, "Stopped playback can restart");
        h.events.addAll(
                h.tracker.sample(
                        "a", new Object(), true, 0, 10000, h.time, 1700000000000L + h.time));
        check(!h.events.getLast().submission(), "New source session announces replay");
    }

    private static void sessionIntegration(boolean slow) throws Exception {
        var location = new java.util.concurrent.atomic.AtomicReference<String>();
        var active = new java.util.concurrent.atomic.AtomicBoolean();
        var start = new java.util.concurrent.atomic.AtomicLong();
        var incarnation = new Object();
        var connected = new java.util.concurrent.CountDownLatch(1);
        var queued = new java.util.concurrent.CountDownLatch(1);
        var host =
                new vip.mystery0.spw.navidrome.host.HostAdapter() {
                    public void check() {}

                    public void play(
                            List<vip.mystery0.spw.navidrome.model.Models.Song> songs,
                            int index,
                            String identity,
                            vip.mystery0.spw.navidrome.stream.LoopbackServer server) {
                        location.set(server.url(songs.get(index).id()));
                        start.set(System.nanoTime());
                        queued.countDown();
                    }

                    public PlaybackSnapshot playbackSnapshot() {
                        return new PlaybackSnapshot(
                                location.get(),
                                incarnation,
                                active.get(),
                                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                        System.nanoTime() - start.get()));
                    }

                    public void detach() {
                        active.set(false);
                    }

                    public void close() {}
                };
        try (var server = new MockServer();
                var session =
                        new AppSession(
                                host, java.nio.file.Files.createTempDirectory("reporting-test"))) {
            if (slow) server.scrobbleGate = new java.util.concurrent.CountDownLatch(1);
            session.connect(
                    server.address(),
                    "a",
                    "b".toCharArray(),
                    true,
                    s -> connected.countDown(),
                    s -> {
                        throw new AssertionError(s);
                    });
            check(connected.await(3, java.util.concurrent.TimeUnit.SECONDS), "Connected");
            session.play(
                    List.of(
                            new vip.mystery0.spw.navidrome.model.Models.Song(
                                    "a", "a", "", "", 2000, "")),
                    0,
                    s -> {});
            check(queued.await(3, java.util.concurrent.TimeUnit.SECONDS), "Queue submitted");
            Thread.sleep(650);
            check(server.scrobbles.isEmpty(), "Queue submission alone does not scrobble");
            active.set(true);
            if (slow) {
                long waitUntil =
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while (server.scrobbles.isEmpty() && System.nanoTime() < waitUntil)
                    Thread.sleep(20);
                check(!server.scrobbles.isEmpty(), "Slow request started");
                Thread.sleep(1800);
                long disconnectStart = System.nanoTime();
                try {
                    session.disconnect();
                    check(
                            System.nanoTime() - disconnectStart
                                    < java.util.concurrent.TimeUnit.SECONDS.toNanos(2),
                            "Disconnect does not wait for a slow response");
                } finally {
                    server.scrobbleGate.countDown();
                }
                Thread.sleep(650);
                check(server.scrobbles.size() == 1, "Disconnect cancels queued submissions");
                return;
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (server.scrobbles.stream().noneMatch(q -> "true".equals(q.get("submission")))
                    && System.nanoTime() < deadline) Thread.sleep(20);
            check(
                    server.scrobbles.stream()
                                    .filter(q -> "true".equals(q.get("submission")))
                                    .count()
                            == 1,
                    "Real session sends one qualified submission");
            check(
                    "false".equals(server.scrobbles.getFirst().get("submission")),
                    "Now playing precedes submission");
            session.disconnect();
            int before = server.scrobbles.size();
            Thread.sleep(650);
            check(server.scrobbles.size() == before, "Disconnect prevents later reports");
        }
    }

    private static final class Harness {
        final PlaybackTracker tracker = new PlaybackTracker();
        final List<PlaybackTracker.Report> events = new ArrayList<>();
        long time, position;

        void sample(String id, boolean playing, long pos, long duration) {
            position = pos;
            events.addAll(
                    tracker.sample(id, id, playing, pos, duration, time, 1700000000000L + time));
        }

        void advance(String id, int seconds, long duration) {
            for (int i = 0; i < seconds; i++) {
                time += 1000;
                sample(id, true, position + 1000, duration);
            }
        }

        long submissions() {
            return events.stream().filter(PlaybackTracker.Report::submission).count();
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
