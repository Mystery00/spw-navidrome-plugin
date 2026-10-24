package vip.mystery0.spw.navidrome.host;

/** 验证解析依据结构，遇到歧义时拒绝选择。 */
public final class HostReflectionTest {
    static final class Renamed {
        private final String a;

        Renamed(String value) {
            a = value;
        }

        public String b() {
            return a;
        }

        public int c() {
            return 1;
        }

        public int d() {
            return 2;
        }
    }

    public static final class DescriptorFixture {
        public boolean a;
        public long b;

        public boolean a() {
            return true;
        }

        public long b() {
            return 7L;
        }
    }

    private static void fullDescriptor() throws Exception {
        String name = DescriptorFixture.class.getName();
        byte[] bytes;
        try (var in =
                DescriptorFixture.class.getResourceAsStream(
                        "/" + name.replace('.', '/') + ".class")) {
            bytes = in.readAllBytes();
        }
        // 合成合法 JVM 重载：Java 源码不允许同名字段或仅返回类型不同的方法。
        var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
        var buffer = new java.io.ByteArrayOutputStream();
        var output = new java.io.DataOutputStream(buffer);
        output.write(input.readNBytes(8));
        int count = input.readUnsignedShort();
        output.writeShort(count);
        for (int i = 1; i < count; i++) {
            int tag = input.readUnsignedByte();
            output.writeByte(tag);
            switch (tag) {
                case 1 -> {
                    String value = input.readUTF();
                    output.writeUTF(value.equals("b") ? "a" : value);
                }
                case 3, 4 -> output.write(input.readNBytes(4));
                case 5, 6 -> {
                    output.write(input.readNBytes(8));
                    i++;
                }
                case 7, 8, 16, 19, 20 -> output.write(input.readNBytes(2));
                case 9, 10, 11, 12, 17, 18 -> output.write(input.readNBytes(4));
                case 15 -> output.write(input.readNBytes(3));
                default -> throw new AssertionError("Unknown fixture constant pool tag");
            }
        }
        output.write(input.readAllBytes());
        byte[] renamed = buffer.toByteArray();
        class FixtureLoader extends ClassLoader {
            FixtureLoader() {
                super(HostReflectionTest.class.getClassLoader());
            }

            Class<?> define() {
                return defineClass(name, renamed, 0, renamed.length);
            }
        }
        var loader = new FixtureLoader();
        Class<?> fixture = loader.define();
        var reflection = new HostReflection(loader, java.util.List.of());
        for (var member : ClassFile.read(renamed).methods()) {
            if (!member.name().equals("a")) continue;
            Class<?> expected = member.descriptor().equals("()J") ? long.class : boolean.class;
            if (reflection.method(fixture, member).getReturnType() != expected)
                throw new AssertionError("Method declaration must match return descriptor");
            if (reflection
                            .referencedMethod(
                                    new ClassFile.MemberRef(
                                            name.replace('.', '/'), "a", member.descriptor()))
                            .getReturnType()
                    != expected)
                throw new AssertionError("Method reference must match return descriptor");
        }
        for (String descriptor : java.util.List.of("Z", "J")) {
            var field =
                    reflection.referencedField(
                            new ClassFile.MemberRef(name.replace('.', '/'), "a", descriptor));
            if (!field.getType().descriptorString().equals(descriptor))
                throw new AssertionError("Field reference must match type descriptor");
        }
    }

    public static void run() throws Exception {
        fullDescriptor();
        var file = ClassFile.read(Renamed.class);
        var getter =
                HostReflection.uniqueMethod(
                        Renamed.class,
                        m -> m.getReturnType() == String.class && m.getParameterCount() == 0,
                        "getter");
        var code = file.method(getter);
        if (code.instructions().stream()
                .noneMatch(i -> i.opcode() == 180 && i.member().name().equals("a")))
            throw new AssertionError("Getter field reference");
        try {
            HostReflection.uniqueMethod(
                    Renamed.class,
                    m -> m.getReturnType() == int.class && m.getParameterCount() == 0,
                    "ambiguous");
            throw new AssertionError("Ambiguity accepted");
        } catch (ReflectiveOperationException expected) {
        }
        try {
            HostReflection.uniqueField(Renamed.class, f -> f.getType() == long.class, "missing");
            throw new AssertionError("Missing field accepted");
        } catch (ReflectiveOperationException expected) {
        }
        System.out.println("PASS structural host reflection and ambiguity rejection");
    }

    public static void main(String[] args) throws Exception {
        run();
    }
}
