package vip.mystery0.spw.navidrome.host;

import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 使用真实 Kotlin 编译结果重命名后的夹具，验证 JVM 名称与 Metadata 语义分离。 */
public final class MetadataRenamingTest {
    private static final String PACKAGE = "vip.mystery0.spw.navidrome.host.fixtures.";

    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args[0]);
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {fixture.toUri().toURL()},
                        MetadataRenamingTest.class.getClassLoader())) {
            var reflection = new HostReflection(loader, List.of(fixture));
            Class<?> colors = reflection.type(PACKAGE + "MetadataColors");
            check(!colors.getName().equals(PACKAGE + "MetadataColors"), "JVM class was renamed");
            check(
                    reflection.type(PACKAGE + "MetadataColors") == colors,
                    "Type resolution is cached");
            check(
                    reflection
                            .classFile(colors)
                            .metadataStrings()
                            .containsAll(
                                    List.of(
                                            "highlight",
                                            "text",
                                            "subText",
                                            "background",
                                            "surface",
                                            "alternate",
                                            "border",
                                            "onHighlight",
                                            "success",
                                            "warning",
                                            "error")),
                    "Metadata property semantics survive remapping");
            Class<?>[] parameters = new Class<?>[11];
            java.util.Arrays.fill(parameters, long.class);
            Object[] values = new Object[11];
            for (int i = 0; i < values.length; i++) values[i] = (long) i + 101;
            Object palette = colors.getConstructor(parameters).newInstance(values);
            Set<Long> actual = new HashSet<>();
            for (var method : colors.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || method.getReturnType() != long.class)
                    continue;
                check(!method.getName().startsWith("get"), "Getter JVM names were renamed");
                actual.add((Long) method.invoke(palette));
            }
            check(
                    actual.size() == 11 && actual.contains(101L) && actual.contains(111L),
                    "Remapped getter bytecode preserves constructor values");
            try {
                HostReflection.uniqueMethod(
                        colors,
                        method ->
                                method.getParameterCount() == 0
                                        && method.getReturnType() == long.class,
                        "ambiguous fixture colors");
                throw new AssertionError("Ambiguous getters must be rejected");
            } catch (ReflectiveOperationException expected) {
                check(expected.getMessage().contains("11"), "All ambiguous getters were counted");
            }
            Class<?> dynamic = reflection.type(PACKAGE + "MetadataDynamicColors");
            Object pair = dynamic.getConstructor(colors, colors).newInstance(palette, palette);
            int getters = 0;
            for (var method : dynamic.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                        && method.getReturnType() == colors
                        && method.getParameterCount() == 0) {
                    check(method.invoke(pair) == palette, "Remapped class references remain valid");
                    getters++;
                }
            }
            check(getters == 2, "Both color variants remain available");
            Class<?> distractor = reflection.type(PACKAGE + "MetadataDistractor");
            check(distractor != colors, "Shared property tokens do not confuse type identity");
            try {
                reflection.type(PACKAGE + "MissingColors");
                throw new AssertionError("Missing type must be rejected");
            } catch (ClassNotFoundException expected) {
                // 缺失目标不得回退为其他具有相似字段的类。
            }
        }
        System.out.println("PASS renamed JVM classes and members with preserved Kotlin Metadata");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
