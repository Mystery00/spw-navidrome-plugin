"""验证测试夹具的 JVM 重命名不会改写 Metadata 语义。"""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from remap_fixture import remap_jar


class FixtureRemapTest(unittest.TestCase):
    def test_runtime_names_change_but_metadata_strings_remain(self):
        jdk = Path(os.environ["JAVA_HOME"])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "Sample.java"
            source.write_text(
                """package fixture;
@Semantic({"Lfixture/Sample;", "left", "getLeft", "right", "getRight"})
public final class Sample {
    private final long left;
    private final long right;
    public Sample(long left, long right) { this.left = left; this.right = right; }
    public long getLeft() { return left; }
    public long getRight() { return right; }
}
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@interface Semantic { String[] value(); }
""",
                encoding="utf-8",
            )
            subprocess.run(
                [str(jdk / "bin/javac.exe"), "-d", str(root), str(source)], check=True
            )
            input_jar = root / "input.jar"
            with zipfile.ZipFile(input_jar, "w") as archive:
                for file in root.rglob("*.class"):
                    archive.write(file, file.relative_to(root).as_posix())
            output = root / "output.jar"
            mapping = remap_jar(input_jar, output, "fixture/Sample")
            name = mapping["fixture/Sample"].replace("/", ".")
            result = subprocess.run(
                [
                    str(jdk / "bin/javap.exe"),
                    "-J-Dfile.encoding=UTF-8",
                    "-J-Dstdout.encoding=UTF-8",
                    "-p",
                    "-v",
                    "-cp",
                    str(output),
                    name,
                ],
                check=True,
                capture_output=True,
                encoding="utf-8",
            ).stdout
            self.assertNotIn("public long getLeft();", result)
            self.assertIn('"getLeft"', result)
            self.assertIn('"Lfixture/Sample;"', result)
            self.assertIn("public long m", result)
            # 实际加载并调用，避免仅靠常量池文本检查掩盖损坏的字节码。
            runner = root / "Probe.java"
            runner.write_text(
                """public class Probe {
 public static void main(String[] args) throws Exception {
  Class<?> c = Class.forName(args[0]);
  Object value = c.getConstructor(long.class, long.class).newInstance(17L, 29L);
  java.util.Set<Long> actual = new java.util.HashSet<>();
  for (var method : c.getDeclaredMethods()) {
   if (method.getReturnType() == long.class && method.getParameterCount() == 0)
    actual.add((Long) method.invoke(value));
  }
  if (!actual.equals(java.util.Set.of(17L, 29L))) throw new AssertionError(actual);
 }
}""",
                encoding="utf-8",
            )
            subprocess.run(
                [str(jdk / "bin/javac.exe"), "-d", str(root), str(runner)], check=True
            )
            subprocess.run(
                [
                    str(jdk / "bin/java.exe"),
                    "-cp",
                    str(root) + os.pathsep + str(output),
                    "Probe",
                    name,
                ],
                check=True,
            )


if __name__ == "__main__":
    unittest.main()
