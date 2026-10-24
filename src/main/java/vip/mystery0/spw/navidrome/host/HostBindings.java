package vip.mystery0.spw.navidrome.host;

import java.lang.reflect.*;
import java.util.*;

/** 按 Metadata 身份、参数结构与调用关系解析宿主能力，所有入口在安装前校验。 */
public final class HostBindings {
    final HostReflection reflection;
    final Class<?> item, player, basePlayer, interceptor, source, controller, service;
    final Constructor<?> itemConstructor;
    final Method location, currentItem, isPlaying, position, playerState, sourceItem, createStream;
    final Field playbackSource;
    final List<Holder> holders;

    record Holder(Field holder, Field list) {}

    public static void verify(ClassLoader loader) throws Exception {
        new HostBindings(loader);
    }

    HostBindings(ClassLoader loader) throws Exception {
        service = Class.forName("com.xuncorp.voxzen.service.PlaybackService", false, loader);
        reflection = new HostReflection(loader, service);
        item = reflection.type("com.xuncorp.pisces.PiscesMediaItem");
        basePlayer = reflection.type("com.xuncorp.pisces.PiscesPlayer");
        player = reflection.type("com.xuncorp.pisces.BassPiscesPlayer");
        source = reflection.type("com.xuncorp.pisces.PiscesPlaybackSource");
        interceptor = reflection.type("com.xuncorp.pisces.bass.BassStreamInterceptor");
        controller = reflection.type("com.xuncorp.voxzen.service.PlaybackController");
        itemConstructor =
                item.getConstructor(
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class);
        if (!reflection
                .constructorParameterNames(item, 6)
                .equals(List.of("id", "title", "artist", "album", "albumArtist", "path")))
            throw new ReflectiveOperationException("Unsupported media constructor semantics");
        // 用不含用户数据的合成媒体验证路径 getter，而非依赖多个 String getter 的枚举顺序。
        Object sample =
                itemConstructor.newInstance(
                        "probe-id",
                        "probe-title",
                        "probe-artist",
                        "probe-album",
                        "probe-album-artist",
                        "probe-path");
        List<Method> paths = new ArrayList<>();
        for (Method method : item.getDeclaredMethods())
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 0
                    && method.getReturnType() == String.class) {
                method.setAccessible(true);
                if ("probe-path".equals(method.invoke(sample))) paths.add(method);
            }
        location = only(paths, "media path getter");
        currentItem =
                HostReflection.uniqueMethod(
                        basePlayer,
                        m -> m.getParameterCount() == 0 && m.getReturnType() == item,
                        "current media getter");
        Class<?> state = reflection.type("com.xuncorp.pisces.PiscesPlayer$State");
        playerState =
                HostReflection.uniqueMethod(
                        basePlayer,
                        m -> m.getParameterCount() == 0 && m.getReturnType() == state,
                        "player state getter");
        Set<String> states = new HashSet<>();
        for (Object value : state.getEnumConstants()) states.add(((Enum<?>) value).name());
        if (!states.containsAll(List.of("Ready", "Ended", "Idle")))
            throw new ReflectiveOperationException("Unsupported player states");
        isPlaying = playbackCallback(boolean.class);
        position = playbackCallback(long.class);
        sourceItem =
                HostReflection.uniqueMethod(
                        source,
                        m -> m.getParameterCount() == 0 && m.getReturnType() == item,
                        "source media getter");
        Method sourceAccessor =
                HostReflection.uniqueMethod(
                        player,
                        m ->
                                Modifier.isStatic(m.getModifiers())
                                        && Arrays.equals(
                                                m.getParameterTypes(), new Class<?>[] {player})
                                        && m.getReturnType() == source,
                        "current source accessor");
        List<ClassFile.MemberRef> fields =
                reflection.classFile(player).method(sourceAccessor).instructions().stream()
                        .filter(
                                i ->
                                        i.opcode() == 180
                                                && i.member()
                                                        .descriptor()
                                                        .equals(source.descriptorString()))
                        .map(ClassFile.Instruction::member)
                        .distinct()
                        .toList();
        playbackSource = reflection.referencedField(only(fields, "current source field"));
        Class<?> context = reflection.type("com.xuncorp.pisces.PlatformContext"),
                continuation = reflection.type("kotlin.coroutines.Continuation");
        HostReflection.uniqueMethod(
                interceptor,
                m ->
                        Arrays.equals(
                                        m.getParameterTypes(),
                                        new Class<?>[] {
                                            context,
                                            item,
                                            long.class,
                                            long.class,
                                            int.class,
                                            continuation
                                        })
                                && m.getReturnType() == Object.class,
                "stream interceptor");
        Class<?> range = reflection.type("com.xuncorp.voxzen.cast.HttpRangeBassStream"),
                owner = reflection.type("com.xuncorp.voxzen.cast.HttpRangeStreamOwner");
        createStream =
                HostReflection.uniqueMethod(
                        range,
                        m ->
                                Arrays.equals(
                                                m.getParameterTypes(),
                                                new Class<?>[] {String.class, int.class, owner})
                                        && m.getReturnType() == Object.class,
                        "range stream factory");
        List<Holder> found = new ArrayList<>();
        for (Class<?> current = player;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field outer : current.getDeclaredFields()) {
                if (Modifier.isStatic(outer.getModifiers())
                        || outer.getType().isPrimitive()
                        || outer.getType().getName().startsWith("java.")) continue;
                for (Field nested : outer.getType().getDeclaredFields()) {
                    if (Modifier.isStatic(nested.getModifiers())
                            || nested.getType() != List.class
                            || !(nested.getGenericType() instanceof ParameterizedType generic))
                        continue;
                    if (generic.getActualTypeArguments().length == 1
                            && generic.getActualTypeArguments()[0] == interceptor) {
                        outer.setAccessible(true);
                        nested.setAccessible(true);
                        found.add(new Holder(outer, nested));
                    }
                }
            }
        }
        if (found.size() != 3)
            throw new ReflectiveOperationException(
                    "Expected three stream interceptor owners, found " + found.size());
        holders = List.copyOf(found);
        controller.getMethod("setPlaybackQueue", List.class, int.class);
        for (String name : List.of("prepare", "play", "stop")) controller.getMethod(name);
        controller.getMethod("removeMediaItems", Array.newInstance(item, 0).getClass());
        for (String name :
                List.of(
                        "imageBitmapOwner",
                        "imageBitmapOwnerLock",
                        "lyricsEmitter",
                        "onLoadedJob",
                        "onCurrentMediaItemDataRefreshedJob")) service.getDeclaredField(name);
        NativePresentation.verify(reflection, service);
    }

    private Method playbackCallback(Class<?> returns) throws Exception {
        Method lambda =
                HostReflection.uniqueMethod(
                        service,
                        m ->
                                m.getName().startsWith("lyricsEmitter$lambda$")
                                        && Modifier.isStatic(m.getModifiers())
                                        && m.getReturnType() == returns
                                        && Arrays.equals(
                                                m.getParameterTypes(), new Class<?>[] {service}),
                        "lyrics playback callback");
        String descriptor = "()" + returns.descriptorString();
        List<ClassFile.MemberRef> calls =
                reflection.classFile(service).method(lambda).instructions().stream()
                        .filter(
                                i ->
                                        i.member() != null
                                                && i.opcode() >= 182
                                                && i.opcode() <= 185
                                                && i.member()
                                                        .owner()
                                                        .equals(
                                                                basePlayer
                                                                        .getName()
                                                                        .replace('.', '/'))
                                                && i.member().descriptor().equals(descriptor))
                        .map(ClassFile.Instruction::member)
                        .distinct()
                        .toList();
        return reflection.referencedMethod(only(calls, "playback callback target"));
    }

    static <T> T only(List<T> matches, String label) throws ReflectiveOperationException {
        if (matches.size() != 1)
            throw new ReflectiveOperationException(
                    "Expected unique " + label + ", found " + matches.size());
        return matches.getFirst();
    }
}
