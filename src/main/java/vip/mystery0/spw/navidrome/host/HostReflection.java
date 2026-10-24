package vip.mystery0.spw.navidrome.host;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

/** 宿主结构索引；只按原始 Metadata 语义和唯一结构选择，不保存混淆名称映射。 */
public final class HostReflection {
    private record Entry(String name, List<String> metadata, int kind) {}

    private final ClassLoader loader;
    private final List<Entry> entries;
    private final Map<Class<?>, ClassFile> code = new HashMap<>();
    private final Map<String, Class<?>> types = new HashMap<>();
    private static final ClassValue<List<Entry>> INDEX =
            new ClassValue<>() {
                protected List<Entry> computeValue(Class<?> anchor) {
                    try {
                        return index(
                                Path.of(
                                        anchor.getProtectionDomain()
                                                .getCodeSource()
                                                .getLocation()
                                                .toURI()));
                    } catch (Exception e) {
                        throw new IllegalStateException("Cannot index host metadata", e);
                    }
                }
            };

    public HostReflection(ClassLoader loader, Class<?> anchor) {
        this.loader = loader;
        entries = INDEX.get(anchor);
    }

    public HostReflection(ClassLoader loader, Collection<Path> archives) throws IOException {
        this.loader = loader;
        List<Entry> all = new ArrayList<>();
        for (Path p : archives) all.addAll(index(p));
        entries = List.copyOf(all);
    }

    private static List<Entry> index(Path path) throws IOException {
        List<Entry> result = new ArrayList<>();
        if (Files.isDirectory(path)) {
            try (var files = Files.walk(path)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList())
                    add(result, Files.readAllBytes(p));
            }
        } else
            try (var zip = new ZipFile(path.toFile())) {
                var files = zip.entries();
                while (files.hasMoreElements()) {
                    var e = files.nextElement();
                    String n = e.getName();
                    if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                    try (var in = zip.getInputStream(e)) {
                        add(result, in.readAllBytes());
                    }
                }
            }
        return List.copyOf(result);
    }

    private static void add(List<Entry> entries, byte[] bytes) throws IOException {
        ClassFile file = ClassFile.metadata(bytes);
        if (!file.metadataStrings().isEmpty())
            entries.add(
                    new Entry(
                            file.name().replace('/', '.'),
                            file.metadataStrings(),
                            file.metadataKind()));
    }

    public synchronized Class<?> type(String originalName) throws ReflectiveOperationException {
        String name = originalName.replace('/', '.');
        Class<?> cached = types.get(name);
        if (cached != null) return cached;
        String descriptor = "L" + name.replace('.', '/') + ";";
        List<Entry> matches =
                entries.stream()
                        .filter(e -> e.kind == 1 && e.metadata.getFirst().equals(descriptor))
                        .toList();
        Class<?> result;
        if (matches.size() == 1) result = Class.forName(matches.getFirst().name, false, loader);
        else if (matches.isEmpty()) result = Class.forName(name, false, loader);
        else throw new ReflectiveOperationException("Ambiguous host type: " + name);
        types.put(name, result);
        return result;
    }

    public List<Class<?>> typesWithMetadata(String... tokens) throws ReflectiveOperationException {
        List<Class<?>> result = new ArrayList<>();
        for (Entry e : entries)
            if (e.metadata.containsAll(List.of(tokens)))
                result.add(Class.forName(e.name, false, loader));
        return List.copyOf(result);
    }

    /** 读取声明的构造参数名；同参数数量的构造器不唯一时拒绝猜测。 */
    public List<String> constructorParameterNames(Class<?> owner, int parameterCount)
            throws Exception {
        ClassFile metadata = classFile(owner);
        Class<?> decoder =
                Class.forName(
                        "kotlin.reflect.jvm.internal.impl.metadata.jvm.deserialization.JvmProtoBufUtil",
                        false,
                        loader);
        Object pair =
                decoder.getMethod("readClassDataFrom", String[].class, String[].class)
                        .invoke(
                                null,
                                metadata.metadataData().toArray(String[]::new),
                                metadata.metadataStrings().toArray(String[]::new));
        Object resolver = pair.getClass().getMethod("getFirst").invoke(pair);
        Object proto = pair.getClass().getMethod("getSecond").invoke(pair);
        List<?> constructors =
                (List<?>) proto.getClass().getMethod("getConstructorList").invoke(proto);
        List<List<String>> matches = new ArrayList<>();
        for (Object constructor : constructors) {
            List<?> parameters =
                    (List<?>)
                            constructor
                                    .getClass()
                                    .getMethod("getValueParameterList")
                                    .invoke(constructor);
            if (parameters.size() != parameterCount) continue;
            List<String> names = new ArrayList<>();
            for (Object parameter : parameters) {
                int index = (Integer) parameter.getClass().getMethod("getName").invoke(parameter);
                names.add(
                        (String)
                                resolver.getClass()
                                        .getMethod("getString", int.class)
                                        .invoke(resolver, index));
            }
            matches.add(List.copyOf(names));
        }
        if (matches.size() != 1)
            throw new ReflectiveOperationException(
                    "Ambiguous constructor metadata: " + owner.getName());
        return matches.getFirst();
    }

    public synchronized ClassFile classFile(Class<?> type) throws IOException {
        ClassFile file = code.get(type);
        if (file == null) {
            file = ClassFile.read(type);
            code.put(type, file);
        }
        return file;
    }

    public static Method uniqueMethod(Class<?> owner, Predicate<Method> predicate, String label)
            throws ReflectiveOperationException {
        List<Method> methods = Arrays.stream(owner.getDeclaredMethods()).filter(predicate).toList();
        if (methods.size() != 1)
            throw new ReflectiveOperationException(
                    "Expected unique " + label + ", found " + methods.size());
        Method result = methods.getFirst();
        result.setAccessible(true);
        return result;
    }

    public static Field uniqueField(Class<?> owner, Predicate<Field> predicate, String label)
            throws ReflectiveOperationException {
        List<Field> fields = Arrays.stream(owner.getDeclaredFields()).filter(predicate).toList();
        if (fields.size() != 1)
            throw new ReflectiveOperationException(
                    "Expected unique " + label + ", found " + fields.size());
        Field result = fields.getFirst();
        result.setAccessible(true);
        return result;
    }

    public Method method(Class<?> owner, ClassFile.Member member)
            throws ReflectiveOperationException {
        return exactMethod(owner, member.name(), member.descriptor());
    }

    public Method referencedMethod(ClassFile.MemberRef ref) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(ref.owner().replace('/', '.'), false, loader);
        return exactMethod(owner, ref.name(), ref.descriptor());
    }

    private static Method exactMethod(Class<?> owner, String name, String descriptor)
            throws ReflectiveOperationException {
        // JVM 允许同名同参但返回类型不同；getDeclaredMethod 无法表达完整的匹配条件。
        return uniqueMethod(
                owner,
                method ->
                        method.getName().equals(name)
                                && java.lang.invoke.MethodType.methodType(
                                                method.getReturnType(), method.getParameterTypes())
                                        .descriptorString()
                                        .equals(descriptor),
                "method " + name + descriptor);
    }

    public Field referencedField(ClassFile.MemberRef ref) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(ref.owner().replace('/', '.'), false, loader);
        return uniqueField(
                owner,
                field ->
                        field.getName().equals(ref.name())
                                && field.getType().descriptorString().equals(ref.descriptor()),
                "field " + ref.name() + ref.descriptor());
    }
}
