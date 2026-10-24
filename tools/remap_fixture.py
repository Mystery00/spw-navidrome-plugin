"""仅为合成测试夹具改写 JVM 符号，保留 Kotlin Metadata 和字符串语义。

不用于产品打包或任意宿主重混淆。输入 jar 应只含测试类，不含 Kotlin 运行时。
"""

import argparse
from pathlib import Path
import struct
import zipfile

U2 = struct.Struct(">H")
U4 = struct.Struct(">I")


def u2(data, offset):
    return U2.unpack_from(data, offset)[0]


class ClassFile:
    def __init__(self, data):
        if data[:4] != b"\xca\xfe\xba\xbe":
            raise ValueError("Invalid class file")
        self.header = data[:8]
        self.pool = [None]
        cursor = 10
        count = u2(data, 8)
        while len(self.pool) < count:
            tag = data[cursor]
            cursor += 1
            if tag == 1:
                length = u2(data, cursor)
                payload = data[cursor : cursor + 2 + length]
            else:
                length = {
                    3: 4,
                    4: 4,
                    5: 8,
                    6: 8,
                    7: 2,
                    8: 2,
                    9: 4,
                    10: 4,
                    11: 4,
                    12: 4,
                    15: 3,
                    16: 2,
                    17: 4,
                    18: 4,
                    19: 2,
                    20: 2,
                }.get(tag)
                if length is None:
                    raise ValueError(f"Unsupported constant pool tag: {tag}")
                payload = data[cursor : cursor + length]
            self.pool.append((tag, payload))
            cursor += len(payload)
            if tag in (5, 6):
                self.pool.append(None)
        self.body = bytearray(data[cursor:])
        self.original = list(self.pool)
        self.name = self.text(u2(self.pool[u2(self.body, 2)][1], 0))

    def text(self, index):
        entry = self.original[index]
        if entry[0] != 1:
            raise ValueError("Expected UTF8 constant")
        # Metadata 的二进制 d1 使用修改 UTF-8；这里只解码类与成员标识符。
        return entry[1][2:].decode("utf-8")

    def utf8(self, text):
        encoded = text.encode("utf-8")
        self.pool.append((1, U2.pack(len(encoded)) + encoded))
        return len(self.pool) - 1

    def rewrite(self, classes, member_names):
        def descriptor(value):
            for before, after in classes.items():
                value = value.replace("L" + before + ";", "L" + after + ";")
                value = value.replace("L" + before + "<", "L" + after + "<")
            return value

        def changed_index(index, transform):
            before = self.text(index)
            after = transform(before)
            return self.utf8(after) if after != before else index

        # 克隆 UTF8，不修改原常量；注解和字符串可能共享该常量。
        for index, entry in enumerate(self.original):
            if entry is None:
                continue
            tag, payload = entry
            if tag == 7:
                old = u2(payload, 0)
                new = changed_index(
                    old, lambda value: classes.get(value, descriptor(value))
                )
                self.pool[index] = (tag, U2.pack(new))
            elif tag in (9, 10, 11):
                owner_index, nt_index = struct.unpack(">HH", payload)
                owner = self.text(u2(self.original[owner_index][1], 0))
                _, nt = self.original[nt_index]
                name_index, desc_index = struct.unpack(">HH", nt)
                name = self.text(name_index)
                new_name = member_names.get(name, name) if owner in classes else name
                new_desc = descriptor(self.text(desc_index))
                self.pool.append(
                    (12, struct.pack(">HH", self.utf8(new_name), self.utf8(new_desc)))
                )
                self.pool[index] = (
                    tag,
                    struct.pack(">HH", owner_index, len(self.pool) - 1),
                )
            elif tag in (12, 16):
                # 动态调用和 MethodType 中的描述符也需同步更新。
                desc_offset = 2 if tag == 12 else 0
                desc_index = u2(payload, desc_offset)
                new_desc = changed_index(desc_index, descriptor)
                self.pool[index] = (tag, payload[:desc_offset] + U2.pack(new_desc))

        def attributes(cursor):
            count = u2(self.body, cursor)
            cursor += 2
            for _ in range(count):
                name = self.text(u2(self.body, cursor))
                length = U4.unpack_from(self.body, cursor + 2)[0]
                if name == "Signature":
                    old = u2(self.body, cursor + 6)
                    U2.pack_into(self.body, cursor + 6, changed_index(old, descriptor))
                cursor += 6 + length
            return cursor

        cursor = 8 + 2 * u2(self.body, 6)
        for _ in range(2):
            count = u2(self.body, cursor)
            cursor += 2
            for _ in range(count):
                name_index = u2(self.body, cursor + 2)
                if self.name in classes:
                    U2.pack_into(
                        self.body,
                        cursor + 2,
                        changed_index(name_index, lambda n: member_names.get(n, n)),
                    )
                desc_index = u2(self.body, cursor + 4)
                U2.pack_into(
                    self.body, cursor + 4, changed_index(desc_index, descriptor)
                )
                cursor = attributes(cursor + 6)
        attributes(cursor)
        if len(self.pool) >= 65536:
            raise ValueError("Fixture constant pool too large")
        return (
            self.header
            + U2.pack(len(self.pool))
            + b"".join(
                bytes([tag]) + payload
                for item in self.pool[1:]
                if item is not None
                for tag, payload in [item]
            )
            + self.body
        )

    def declared_names(self):
        cursor = 8 + 2 * u2(self.body, 6)
        names = set()
        for _ in range(2):
            count = u2(self.body, cursor)
            cursor += 2
            for _ in range(count):
                name = self.text(u2(self.body, cursor + 2))
                if not name.startswith("<"):
                    names.add(name)
                cursor += 6
                attributes = u2(self.body, cursor)
                cursor += 2
                for _ in range(attributes):
                    cursor += 6 + U4.unpack_from(self.body, cursor + 2)[0]
        return names


def remap_jar(source, destination, prefix):
    source, destination = Path(source).resolve(), Path(destination).resolve()
    if source == destination:
        raise ValueError("Fixture source must not be overwritten")
    if source.is_dir():
        files = {
            file.relative_to(source).as_posix(): file.read_bytes()
            for file in source.rglob("*.class")
            if file.relative_to(source).as_posix().startswith(prefix)
        }
    else:
        with zipfile.ZipFile(source) as archive:
            files = {name: archive.read(name) for name in archive.namelist()}
    parsed = {
        name: ClassFile(data) for name, data in files.items() if name.endswith(".class")
    }
    selected = sorted(
        value.name for value in parsed.values() if value.name.startswith(prefix)
    )
    if not selected:
        raise ValueError("No fixture classes selected")
    classes = {
        name: "androidx/compose/ui/testfixture/Fixture" + str(index)
        for index, name in enumerate(selected)
    }
    names = sorted(
        {
            name
            for value in parsed.values()
            if value.name in classes
            for name in value.declared_names()
        }
    )
    members = {name: "m" + str(index) for index, name in enumerate(names)}
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as out:
        for name, data in files.items():
            if name in parsed:
                value = parsed[name]
                name = classes.get(value.name, value.name) + ".class"
                data = value.rewrite(classes, members)
            out.writestr(name, data)
    return classes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--prefix", required=True, help="Internal JVM package prefix")
    args = parser.parse_args()
    mapping = remap_jar(args.source, args.destination, args.prefix)
    print(f"REMAPPED FIXTURE: {len(mapping)} classes -> {args.destination}")


if __name__ == "__main__":
    main()
