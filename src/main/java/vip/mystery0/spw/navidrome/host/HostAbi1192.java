package vip.mystery0.spw.navidrome.host;

import java.lang.reflect.*;
import java.util.List;

/** Steam 1.19.2 的实机签名；先检查全部入口，再取得宿主实例。 */
final class HostAbi1192 {
    static final String INTERCEPTOR = "androidx.compose.ui.\u09a3";
    static final String CURRENT_ITEM = "\u0788";
    static final String IS_PLAYING = "\u0560", POSITION_MS = "\u058f", PLAYER_STATE = "\u078a";
    static final String PLAYBACK_SOURCE = "\u052b", SOURCE_TYPE = "com.xuncorp.pisces.\u0787";
    static final String LYRICS_PARSER = "androidx.compose.ui.\u1054";
    static final String OPENER = "\u05ef", FORMAT_PROBE = "\u0782";

    private static Class<?> type(ClassLoader loader, String name) throws Exception {
        return Class.forName(name, false, loader);
    }

    static void verify(ClassLoader loader) throws Exception {
        Class<?> item = type(loader, "com.xuncorp.pisces.PiscesMediaItem");
        item.getConstructor(
                String.class, String.class, String.class, String.class, String.class, String.class);
        if (item.getMethod("\u052c").getReturnType() != String.class)
            throw new NoSuchMethodException("Media location");
        Class<?> player = type(loader, "com.xuncorp.pisces.\u0528");
        if (player.getMethod(CURRENT_ITEM).getReturnType() != item)
            throw new NoSuchMethodException("Current media");
        if (player.getMethod(IS_PLAYING).getReturnType() != boolean.class
                || player.getMethod(POSITION_MS).getReturnType() != long.class
                || player.getDeclaredField(PLAYBACK_SOURCE).getType() != type(loader, SOURCE_TYPE)
                || type(loader, SOURCE_TYPE).getMethod("\u037f").getReturnType() != item) {
            throw new NoSuchMethodException("Playback report snapshot");
        }
        Class<?> state = type(loader, "com.xuncorp.pisces.PiscesPlayer$State");
        if (player.getMethod(PLAYER_STATE).getReturnType() != state
                || !((Enum<?>) state.getField("\u052a").get(null)).name().equals("Ended")) {
            throw new NoSuchMethodException("Playback state");
        }
        Class<?> interceptor = type(loader, INTERCEPTOR);
        interceptor.getMethod(
                "createStream-_2kQ-ls",
                type(loader, "com.xuncorp.pisces.PlatformContext"),
                item,
                long.class,
                long.class,
                int.class,
                type(loader, "kotlin.coroutines.Continuation"));
        list(player.getDeclaredField("\u0528").getType(), "\u052f", interceptor);
        list(player.getDeclaredField(OPENER).getType(), "\u0529", interceptor);
        list(player.getDeclaredField(FORMAT_PROBE).getType(), "\u052a", interceptor);
        Class<?> ctrl = type(loader, "com.xuncorp.voxzen.service.PlaybackController");
        ctrl.getMethod("setPlaybackQueue", List.class, int.class);
        for (String name : new String[] {"prepare", "play", "stop"}) ctrl.getMethod(name);
        ctrl.getMethod("removeMediaItems", Array.newInstance(item, 0).getClass());
        Class<?> service = type(loader, "com.xuncorp.voxzen.service.PlaybackService");
        for (String name :
                new String[] {
                    "imageBitmapOwner",
                    "imageBitmapOwnerLock",
                    "lyricsEmitter",
                    "onLoadedJob",
                    "onCurrentMediaItemDataRefreshedJob"
                }) service.getDeclaredField(name);
        Class<?> document = type(loader, "com.xuncorp.spc.lyrics.\u0528");
        if (type(loader, LYRICS_PARSER).getMethod("\u037f", String.class).getReturnType()
                != document) throw new NoSuchMethodException("Lyrics parser");
        document.getMethod("\u052c");
        Class<?> monitor = type(loader, "com.xuncorp.voxzen.service.PlaybackMonitor");
        monitor.getMethod("updateLyricsDocument$Voxzen_composeApp", document);
        monitor.getMethod("getLyricsDocument");
        Class<?> emitter = service.getDeclaredField("lyricsEmitter").getType();
        emitter.getMethod("\u0528");
        emitter.getMethod("\u037f", document);
        emitter.getMethod("\u037f", emitter);
        Class<?> bitmapOwner = type(loader, "com.xuncorp.voxzen.image.RefCountedImageBitmap");
        bitmapOwner.getConstructor(type(loader, "org.jetbrains.skia.Bitmap"));
        type(loader, "com.xuncorp.voxzen.util.ImageBitmapUtil")
                .getMethod("decodeToExactSize", byte[].class, int.class, int.class, boolean.class);
        type(loader, "com.xuncorp.voxzen.service.PlaybackMonitorKt")
                .getMethod("updateImageBitmap", monitor, bitmapOwner);
        type(loader, "com.xuncorp.voxzen.image.ScaledImageBitmapCacheKt")
                .getMethod("getSharedScaledImageBitmapCache");
        type(loader, "com.xuncorp.voxzen.image.ScaledImageBitmapCache")
                .getMethod("onSourceChanged", bitmapOwner);
        type(loader, "com.xuncorp.voxzen.cast.HttpRangeBassStream")
                .getMethod(
                        "create-0E7RQCE",
                        String.class,
                        int.class,
                        type(loader, "com.xuncorp.voxzen.cast.HttpRangeStreamOwner"));
    }

    private static void list(Class<?> owner, String name, Class<?> element) throws Exception {
        Field f = owner.getDeclaredField(name);
        if (f.getType() != List.class
                || !(f.getGenericType() instanceof ParameterizedType p)
                || p.getActualTypeArguments()[0] != element)
            throw new NoSuchFieldException("Interceptor list signature changed");
    }
}
