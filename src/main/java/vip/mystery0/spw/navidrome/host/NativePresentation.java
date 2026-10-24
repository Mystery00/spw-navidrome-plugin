package vip.mystery0.spw.navidrome.host;

import java.lang.reflect.*;

/** 动态解析封面所有权与歌词发射器；仅由串行宿主线程调用。 */
final class NativePresentation {
    @FunctionalInterface
    interface Current {
        boolean check() throws Exception;
    }

    private final ClassLoader loader;
    private final Object service, monitor, cache, lock;
    private final Field imageOwner;
    private final Class<?> ownerType, monitorType;
    private final Method sourceChanged, updateImage;
    private Object ours, ourDocument;
    private final LyricsBindings lyrics;
    private final Field emitterField;

    private static final class LyricsBindings {
        final Class<?> document;
        final Method empty, parse, stop, set, refresh, update;

        LyricsBindings(HostReflection reflection, Class<?> service) throws Exception {
            document = reflection.type("com.xuncorp.spc.lyrics.LyricsDocument");
            empty =
                    HostReflection.uniqueMethod(
                            document,
                            m ->
                                    Modifier.isStatic(m.getModifiers())
                                            && m.getParameterCount() == 0
                                            && m.getReturnType() == document,
                            "empty lyrics document");
            Class<?> parser = reflection.type("com.xuncorp.spc.lyrics.spl.SplDecoder");
            parse =
                    HostReflection.uniqueMethod(
                            parser,
                            m ->
                                    Modifier.isStatic(m.getModifiers())
                                            && java.util.Arrays.equals(
                                                    m.getParameterTypes(),
                                                    new Class<?>[] {String.class})
                                            && m.getReturnType() == document,
                            "lyrics parser");
            Class<?> emitter = service.getDeclaredField("lyricsEmitter").getType();
            set =
                    HostReflection.uniqueMethod(
                            emitter,
                            m ->
                                    !Modifier.isStatic(m.getModifiers())
                                            && java.util.Arrays.equals(
                                                    m.getParameterTypes(),
                                                    new Class<?>[] {document})
                                            && m.getReturnType() == void.class,
                            "lyrics document setter");
            ClassFile bytecode = reflection.classFile(emitter);
            java.util.List<Method> stops = new java.util.ArrayList<>(),
                    refreshes = new java.util.ArrayList<>();
            var setterCalls =
                    bytecode.method(set).instructions().stream()
                            .filter(
                                    i ->
                                            i.opcode() == 183
                                                    && i.member()
                                                            .owner()
                                                            .equals(
                                                                    emitter.getName()
                                                                            .replace('.', '/')))
                            .map(ClassFile.Instruction::member)
                            .toList();
            var tick = HostBindings.only(setterCalls, "lyrics emitter update target");
            for (Method method : emitter.getDeclaredMethods()) {
                if (method.getReturnType() != void.class) continue;
                var instructions = bytecode.method(method).instructions();
                if (!Modifier.isStatic(method.getModifiers())
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterCount() == 0
                        && instructions.stream()
                                .anyMatch(
                                        i ->
                                                i.member() != null
                                                        && i.member()
                                                                .owner()
                                                                .equals(
                                                                        "kotlinx/coroutines/Job$DefaultImpls")
                                                        && i.member()
                                                                .name()
                                                                .equals("cancel$default")))
                    stops.add(method);
                if (Modifier.isStatic(method.getModifiers())
                        && java.util.Arrays.equals(
                                method.getParameterTypes(), new Class<?>[] {emitter})
                        && instructions.stream().anyMatch(i -> tick.equals(i.member())))
                    refreshes.add(method);
            }
            stop = HostBindings.only(stops, "lyrics emitter cancellation");
            refresh = HostBindings.only(refreshes, "lyrics emitter refresh");
            stop.setAccessible(true);
            refresh.setAccessible(true);
            Class<?> monitor = reflection.type("com.xuncorp.voxzen.service.PlaybackMonitor");
            update = monitor.getMethod("updateLyricsDocument$Voxzen_composeApp", document);
        }
    }

    static void verify(HostReflection reflection, Class<?> service) throws Exception {
        new LyricsBindings(reflection, service);
        Class<?> owner = reflection.type("com.xuncorp.voxzen.image.RefCountedImageBitmap");
        owner.getConstructor(reflection.type("org.jetbrains.skia.Bitmap"));
        reflection
                .type("com.xuncorp.voxzen.util.ImageBitmapUtil")
                .getMethod("decodeToExactSize", byte[].class, int.class, int.class, boolean.class);
        reflection
                .type("com.xuncorp.voxzen.service.PlaybackMonitorKt")
                .getMethod(
                        "updateImageBitmap",
                        reflection.type("com.xuncorp.voxzen.service.PlaybackMonitor"),
                        owner);
    }

    final class Prepared implements AutoCloseable {
        Object owner;
        final Object document;

        Prepared(Object owner, Object document) {
            this.owner = owner;
            this.document = document;
        }

        public void close() throws Exception {
            if (owner != null) {
                ((AutoCloseable) owner).close();
                owner = null;
            }
        }
    }

    NativePresentation(ClassLoader loader, Object service) throws Exception {
        this.loader = loader;
        this.service = service;
        lyrics =
                new LyricsBindings(
                        new HostReflection(loader, service.getClass()), service.getClass());
        emitterField = field("lyricsEmitter");
        ownerType = type("com.xuncorp.voxzen.image.RefCountedImageBitmap");
        monitorType = type("com.xuncorp.voxzen.service.PlaybackMonitor");
        monitor = monitorType.getField("INSTANCE").get(null);
        cache =
                type("com.xuncorp.voxzen.image.ScaledImageBitmapCacheKt")
                        .getMethod("getSharedScaledImageBitmapCache")
                        .invoke(null);
        sourceChanged = cache.getClass().getMethod("onSourceChanged", ownerType);
        updateImage =
                type("com.xuncorp.voxzen.service.PlaybackMonitorKt")
                        .getMethod("updateImageBitmap", monitorType, ownerType);
        imageOwner = field("imageBitmapOwner");
        lock = field("imageBitmapOwnerLock").get(service);
    }

    private Class<?> type(String name) throws Exception {
        return Class.forName(name, false, loader);
    }

    private Field field(String name) throws Exception {
        Field f = service.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    Prepared prepare(byte[] cover, String lyrics) throws Exception {
        Object owner = null;
        if (cover != null) {
            Class<?> util = type("com.xuncorp.voxzen.util.ImageBitmapUtil");
            Object image =
                    util.getMethod(
                                    "decodeToExactSize",
                                    byte[].class,
                                    int.class,
                                    int.class,
                                    boolean.class)
                            .invoke(util.getField("INSTANCE").get(null), cover, 1024, 1024, false);
            if (image != null) {
                Object bitmap =
                        type("androidx.compose.ui.graphics.SkiaImageAsset_skikoKt")
                                .getMethod(
                                        "asSkiaBitmap",
                                        type("androidx.compose.ui.graphics.ImageBitmap"))
                                .invoke(null, image);
                owner =
                        ownerType
                                .getConstructor(type("org.jetbrains.skia.Bitmap"))
                                .newInstance(bitmap);
            }
        }
        try {
            Object parsed =
                    lyrics == null || lyrics.isBlank()
                            ? this.lyrics.empty.invoke(null)
                            : this.lyrics.parse.invoke(null, lyrics);
            return new Prepared(owner, parsed);
        } catch (Exception | LinkageError e) {
            if (owner != null) ((AutoCloseable) owner).close();
            throw e;
        }
    }

    boolean publish(Prepared prepared, Current current) throws Exception {
        try {
            synchronized (lock) {
                if (!current.check()) return false;
                Object previous = imageOwner.get(service);
                imageOwner.set(service, prepared.owner);
                ours = prepared.owner;
                prepared.owner = null;
                sourceChanged.invoke(cache, ours);
                updateImage.invoke(null, monitor, ours);
                if (previous != null && previous != ours) ((AutoCloseable) previous).close();
            }
            if (!current.check()) return false;
            applyLyrics(prepared.document);
            ourDocument = prepared.document;
            System.out.println("[SPW Navidrome] Native metadata applied: cover=" + (ours != null));
            return true;
        } finally {
            prepared.close();
        }
    }

    private void applyLyrics(Object parsed) throws Exception {
        Object emitter = emitterField.get(service);
        lyrics.stop.invoke(emitter);
        lyrics.update.invoke(monitor, parsed);
        lyrics.set.invoke(emitter, parsed);
        lyrics.refresh.invoke(null, emitter);
    }

    void clearOwned() throws Exception {
        synchronized (lock) {
            if (ours != null && imageOwner.get(service) == ours) {
                imageOwner.set(service, null);
                sourceChanged.invoke(cache, new Object[] {null});
                updateImage.invoke(null, monitor, null);
                ((AutoCloseable) ours).close();
            }
            ours = null;
        }
        Object flow = monitorType.getMethod("getLyricsDocument").invoke(monitor);
        Object current =
                type("kotlinx.coroutines.flow.StateFlow").getMethod("getValue").invoke(flow);
        if (ourDocument != null && current == ourDocument) applyLyrics(lyrics.empty.invoke(null));
        ourDocument = null;
    }
}
