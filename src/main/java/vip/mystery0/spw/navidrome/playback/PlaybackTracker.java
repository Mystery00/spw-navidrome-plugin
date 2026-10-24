package vip.mystery0.spw.navidrome.playback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 纯计时状态机；由宿主串行线程采样，不执行网络请求。 */
public final class PlaybackTracker {
    public record Report(String songId, long startedAtMs, boolean submission) {}

    private String songId;
    private Object incarnation;
    private long lastTick, lastPosition, listenedMs, startedAt, lastNowPlaying;
    private boolean wasPlaying, announced, submitted;

    public List<Report> sample(
            String id,
            Object playback,
            boolean playing,
            long positionMs,
            long durationMs,
            long monotonicMs,
            long epochMs) {
        if (id == null || playback == null) {
            reset();
            return List.of();
        }
        if (!Objects.equals(id, songId) || playback != incarnation) {
            reset();
            songId = id;
            incarnation = playback;
            lastTick = monotonicMs;
            lastPosition = positionMs;
        }
        List<Report> reports = new ArrayList<>(2);
        boolean active = playing && positionMs >= 0;
        long elapsed = monotonicMs - lastTick;
        long progress = positionMs - lastPosition;
        // 间隔过大视为休眠/调度停顿；明显跳转不作为收听时间。
        if (active
                && wasPlaying
                && elapsed > 0
                && elapsed <= 3000
                && progress > 0
                && progress <= elapsed + 1000) {
            listenedMs += Math.min(elapsed, progress);
        }
        if (active && (!announced || !wasPlaying || monotonicMs - lastNowPlaying >= 60000)) {
            if (!announced) startedAt = epochMs;
            announced = true;
            lastNowPlaying = monotonicMs;
            reports.add(new Report(id, startedAt, false));
        }
        long threshold =
                durationMs > 0 ? Math.min(durationMs / 2 + durationMs % 2, 240000) : 240000;
        if (active && !submitted && listenedMs >= threshold) {
            // 在派发前去重：超时无法判断服务器是否已记次，不自动重试。
            submitted = true;
            reports.add(new Report(id, startedAt, true));
        }
        wasPlaying = active;
        lastTick = monotonicMs;
        lastPosition = positionMs;
        return reports;
    }

    public void reset() {
        songId = null;
        incarnation = null;
        lastTick = lastPosition = listenedMs = startedAt = lastNowPlaying = 0;
        wasPlaying = announced = submitted = false;
    }

    /** 无法获得一致快照时不累计，也不清除已提交标记。 */
    public void suspend() {
        wasPlaying = false;
    }
}
