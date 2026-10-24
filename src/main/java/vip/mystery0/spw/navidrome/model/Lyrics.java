package vip.mystery0.spw.navidrome.model;

import vip.mystery0.spw.navidrome.net.Json;
import java.util.*;

/** OpenSubsonic 行级歌词转为宿主支持的 LRC；不为纯文本捏造时间。 */
public final class Lyrics {
    private Lyrics() {}

    public static String toLrc(Map<String, Object> lyrics) {
        boolean synced = Boolean.TRUE.equals(lyrics.get("synced"));
        long offset = Json.number(lyrics, "offset");
        StringBuilder result = new StringBuilder();
        for (var line : Json.objects(lyrics.get("line"))) {
            String text = Json.str(line, "value").replace('\r', ' ').replace('\n', ' ');
            if (synced) {
                long time = Math.max(0, Math.subtractExact(Json.number(line, "start"), offset));
                result.append(
                        String.format(
                                Locale.ROOT,
                                "[%02d:%02d.%03d]",
                                time / 60000,
                                (time / 1000) % 60,
                                time % 1000));
            }
            result.append(text).append('\n');
            if (result.length() > 262144) throw new IllegalArgumentException("歌词内容过大");
        }
        return result.toString();
    }
}
