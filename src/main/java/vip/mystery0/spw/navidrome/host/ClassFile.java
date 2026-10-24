package vip.mystery0.spw.navidrome.host;

import java.io.*;
import java.lang.reflect.Method;
import java.lang.invoke.MethodType;
import java.util.*;

/** 只读 JVM 结构与 Kotlin Metadata；不加载或执行候选类。 */
public final class ClassFile {
    public record MemberRef(String owner, String name, String descriptor) {}

    public record Instruction(int offset, int opcode, Object operand, MemberRef member) {}

    public record Member(
            String name, String descriptor, int access, List<Instruction> instructions) {}

    private final boolean includeCode;
    private final Object[] pool;
    private final int[] tags;
    private final List<Member> methods = new ArrayList<>();
    private final List<Member> fields = new ArrayList<>();
    private String name;
    private List<String> metadata = List.of(), metadataData = List.of();
    private int metadataKind;

    public static ClassFile read(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) throw new FileNotFoundException(resource);
            return read(in.readAllBytes());
        }
    }

    public static ClassFile read(byte[] bytes) throws IOException {
        return new ClassFile(bytes, true);
    }

    public String name() {
        return name;
    }

    public List<String> metadataStrings() {
        return metadata;
    }

    public List<String> metadataData() {
        return metadataData;
    }

    public int metadataKind() {
        return metadataKind;
    }

    public List<Member> methods() {
        return List.copyOf(methods);
    }

    public List<Member> fields() {
        return List.copyOf(fields);
    }

    public Member method(Method method) {
        String descriptor =
                MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                        .descriptorString();
        return methods.stream()
                .filter(m -> m.name.equals(method.getName()) && m.descriptor.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Method bytecode missing"));
    }

    public static ClassFile metadata(byte[] bytes) throws IOException {
        return new ClassFile(bytes, false);
    }

    private ClassFile(byte[] bytes, boolean includeCode) throws IOException {
        this.includeCode = includeCode;
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xcafebabe) throw new IOException("Invalid class file");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        pool = new Object[count];
        tags = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 3 -> pool[i] = in.readInt();
                case 4 -> pool[i] = in.readFloat();
                case 5 -> {
                    pool[i] = in.readLong();
                    i++;
                }
                case 6 -> {
                    pool[i] = in.readDouble();
                    i++;
                }
                case 7, 8, 16, 19, 20 -> pool[i] = in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 ->
                        pool[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                case 15 -> pool[i] = new int[] {in.readUnsignedByte(), in.readUnsignedShort()};
                default -> throw new IOException("Unsupported constant pool tag: " + tag);
            }
        }
        in.readUnsignedShort();
        name = className(in.readUnsignedShort());
        in.readUnsignedShort();
        for (int n = in.readUnsignedShort(); n > 0; n--) in.readUnsignedShort();
        readMembers(in, fields);
        readMembers(in, methods);
        for (int n = in.readUnsignedShort(); n > 0; n--) {
            String attr = utf(in.readUnsignedShort());
            byte[] data = in.readNBytes(in.readInt());
            if (attr.equals("RuntimeVisibleAnnotations")) readAnnotations(data);
        }
    }

    private void readMembers(DataInputStream in, List<Member> target) throws IOException {
        for (int n = in.readUnsignedShort(); n > 0; n--) {
            int access = in.readUnsignedShort();
            String memberName = utf(in.readUnsignedShort()), desc = utf(in.readUnsignedShort());
            List<Instruction> code = List.of();
            for (int a = in.readUnsignedShort(); a > 0; a--) {
                String attr = utf(in.readUnsignedShort());
                byte[] data = in.readNBytes(in.readInt());
                if (includeCode && attr.equals("Code")) {
                    var c = new DataInputStream(new ByteArrayInputStream(data));
                    c.readUnsignedShort();
                    c.readUnsignedShort();
                    code = instructions(c.readNBytes(c.readInt()));
                }
            }
            target.add(new Member(memberName, desc, access, code));
        }
    }

    private String utf(int index) {
        return (String) pool[index];
    }

    private String className(int index) {
        return utf((Integer) pool[index]);
    }

    private MemberRef member(int index) {
        int[] ref = (int[]) pool[index], nt = (int[]) pool[ref[1]];
        return new MemberRef(className(ref[0]), utf(nt[0]), utf(nt[1]));
    }

    private Object constant(int index) {
        return tags[index] == 8 ? utf((Integer) pool[index]) : pool[index];
    }

    private List<Instruction> instructions(byte[] code) throws IOException {
        List<Instruction> result = new ArrayList<>();
        for (int p = 0; p < code.length; ) {
            int offset = p, op = code[p++] & 255;
            Object operand = null;
            MemberRef ref = null;
            switch (op) {
                case 18 -> {
                    operand = constant(code[p] & 255);
                    p++;
                }
                case 19, 20 -> {
                    operand = constant(u2(code, p));
                    p += 2;
                }
                case 178, 179, 180, 181, 182, 183, 184 -> {
                    ref = member(u2(code, p));
                    p += 2;
                }
                case 185 -> {
                    ref = member(u2(code, p));
                    p += 4;
                }
                case 186 -> p += 4;
                case 16, 21, 22, 23, 24, 25, 54, 55, 56, 57, 58, 169, 188 -> {
                    operand = code[p] & 255;
                    p++;
                }
                case 17,
                        132,
                        153,
                        154,
                        155,
                        156,
                        157,
                        158,
                        159,
                        160,
                        161,
                        162,
                        163,
                        164,
                        165,
                        166,
                        167,
                        168,
                        198,
                        199 ->
                        p += 2;
                case 187, 189, 192, 193 -> {
                    operand = className(u2(code, p));
                    p += 2;
                }
                case 197 -> {
                    operand = className(u2(code, p));
                    p += 3;
                }
                case 200, 201 -> p += 4;
                case 196 -> {
                    int wide = code[p++] & 255;
                    operand = u2(code, p);
                    p += wide == 132 ? 4 : 2;
                }
                case 170 -> {
                    p = (p + 3) & ~3;
                    int low = i4(code, p + 4), high = i4(code, p + 8);
                    p += 12 + (high - low + 1) * 4;
                }
                case 171 -> {
                    p = (p + 3) & ~3;
                    int pairs = i4(code, p + 4);
                    p += 8 + pairs * 8;
                }
                default -> {
                    if (op > 201) throw new IOException("Invalid bytecode opcode");
                }
            }
            if (p > code.length || p <= offset) throw new IOException("Invalid bytecode length");
            result.add(new Instruction(offset, op, operand, ref));
        }
        return List.copyOf(result);
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 255) << 8) | (b[p + 1] & 255);
    }

    private static int i4(byte[] b, int p) {
        return (u2(b, p) << 16) | u2(b, p + 2);
    }

    private void readAnnotations(byte[] bytes) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        for (int n = in.readUnsignedShort(); n > 0; n--) {
            String annotation = utf(in.readUnsignedShort());
            Map<String, Object> values = new HashMap<>();
            for (int a = in.readUnsignedShort(); a > 0; a--)
                values.put(utf(in.readUnsignedShort()), value(in));
            if (annotation.equals("Lkotlin/Metadata;")) {
                metadata = strings(values.get("d2"));
                metadataData = strings(values.get("d1"));
                metadataKind = values.get("k") instanceof Integer i ? i : 1;
            }
        }
    }

    private static List<String> strings(Object values) {
        if (!(values instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private Object value(DataInputStream in) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's' -> constant(in.readUnsignedShort());
            case 'e' -> {
                in.readUnsignedShort();
                yield utf(in.readUnsignedShort());
            }
            case 'c' -> utf(in.readUnsignedShort());
            case '@' -> {
                in.readUnsignedShort();
                for (int n = in.readUnsignedShort(); n > 0; n--) {
                    in.readUnsignedShort();
                    value(in);
                }
                yield null;
            }
            case '[' -> {
                List<Object> values = new ArrayList<>();
                for (int n = in.readUnsignedShort(); n > 0; n--) values.add(value(in));
                yield values;
            }
            default -> throw new IOException("Invalid annotation value");
        };
    }
}
