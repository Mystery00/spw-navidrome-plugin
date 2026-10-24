package vip.mystery0.spw.navidrome.ui

/** 合成类型验证按 Metadata 参数语义映射，方法名称与声明顺序都不作为依据。 */
object HostUiResolverTest {
    class PaletteFixture(val warning: Long, val highlight: Long, val text: Long) {
        fun misleadingName(): Long = 123L
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val reflection =
            vip.mystery0.spw.navidrome.host.HostReflection(
                PaletteFixture::class.java.classLoader,
                PaletteFixture::class.java,
            )
        val resolved =
            HostUiResolver.longProperties(
                reflection,
                PaletteFixture::class.java,
                setOf("highlight", "text", "warning"),
            )
        val instance = PaletteFixture(19L, 37L, 83L)
        check(resolved.getValue("highlight").invoke(instance) == 37L)
        check(resolved.getValue("warning").invoke(instance) == 19L)
        check(resolved.getValue("text").invoke(instance) == 83L)
        try {
            HostUiResolver.longProperties(reflection, PaletteFixture::class.java, setOf("missing"))
            error("Missing semantic property must fail")
        } catch (_: ReflectiveOperationException) {}
        if (args.isNotEmpty()) {
            val archive = java.nio.file.Path.of(args[0])
            java.net
                .URLClassLoader(
                    arrayOf(archive.toUri().toURL()),
                    HostUiResolverTest::class.java.classLoader,
                )
                .use { loader ->
                    val renamed =
                        vip.mystery0.spw.navidrome.host.HostReflection(loader, listOf(archive))
                    val colors =
                        renamed.type("vip.mystery0.spw.navidrome.host.fixtures.MetadataColors")
                    val names = renamed.constructorParameterNames(colors, 11)
                    val methods = HostUiResolver.longProperties(renamed, colors, names.toSet())
                    val probe =
                        colors
                            .getConstructor(*Array(11) { Long::class.javaPrimitiveType })
                            .newInstance(*Array<Any>(11) { (it + 1).toLong() })
                    for ((index, name) in names.withIndex()) check(
                        methods.getValue(name).invoke(probe) == (index + 1).toLong(),
                    )
                }
        }
        println("PASS metadata constructor semantics and synthetic palette mapping")
    }
}
