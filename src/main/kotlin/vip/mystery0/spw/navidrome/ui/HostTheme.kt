@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)

package vip.mystery0.spw.navidrome.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import java.lang.reflect.Field
import java.lang.reflect.Method
import vip.mystery0.spw.navidrome.host.HostReflection

/** 所有页面语义色来自当前宿主 SaltTheme；派生色在当前表面合成。 */
internal data class NativePalette(
    val dark: Boolean,
    val highlight: Color,
    val text: Color,
    val subText: Color,
    val background: Color,
    val surface: Color,
    val border: Color,
    val onHighlight: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
) {
    val selected: Color
        get() = highlight.copy(alpha = if (dark) .24f else .13f).compositeOver(surface)

    val disabled: Color
        get() = text.copy(alpha = .06f).compositeOver(surface)

    val input: Color
        get() = text.copy(alpha = .025f).compositeOver(surface)

    val warningBackground: Color
        get() = warning.copy(alpha = if (dark) .16f else .10f).compositeOver(surface)

    val errorBackground: Color
        get() = error.copy(alpha = if (dark) .16f else .10f).compositeOver(surface)

    val successBackground: Color
        get() = success.copy(alpha = if (dark) .16f else .10f).compositeOver(surface)

    val subtleBorder: Color
        get() = border.copy(alpha = if (dark) .45f else .60f).compositeOver(surface)
}

internal val LocalNativePalette =
    staticCompositionLocalOf<NativePalette> { error("Host theme is not available") }

/** 按 Metadata 和合成实例解析 SaltTheme；不使用系统主题替代宿主选择。 */
internal object HostTheme {
    private val names =
        listOf(
            "highlight",
            "text",
            "subText",
            "background",
            "subBackground",
            "popup",
            "stroke",
            "onHighlight",
            "success",
            "warning",
            "error",
        )

    private class Reader(
        val configs: CompositionLocal<Any?>,
        val colors: CompositionLocal<Any?>,
        val dark: Method,
        val lightColors: Field,
        val darkColors: Field,
        val getters: List<Method>,
    ) {
        fun read(composer: Composer): NativePalette {
            val config = checkNotNull(composer.consume(configs))
            val dynamic = checkNotNull(composer.consume(colors))
            val isDark = dark.invoke(config) as Boolean
            val palette = (if (isDark) darkColors else lightColors).get(dynamic)
            val c = getters.map { colorFromPacked(it.invoke(palette) as Long) }
            return NativePalette(
                isDark,
                c[0],
                c[1],
                c[2],
                c[3],
                c[4],
                c[6],
                c[7],
                c[8],
                c[9],
                c[10],
            )
        }
    }

    private val reader: Reader by lazy {
        val loader = HostTheme::class.java.classLoader
        val reflection = HostUiResolver.reflection(loader)
        val configs = reflection.type("com.moriafly.salt.ui.SaltConfigs")
        val dynamic = reflection.type("com.moriafly.salt.ui.SaltDynamicColors")
        val colors = reflection.type("com.moriafly.salt.ui.SaltColors")
        val locals = HostUiResolver.themeLocals(reflection, configs, dynamic)
        val getters = HostUiResolver.longProperties(reflection, colors, names.toSet())
        val constructor =
            colors.declaredConstructors
                .single {
                    it.parameterCount == names.size &&
                        it.parameterTypes.all { type -> type == Long::class.javaPrimitiveType }
                }
                .apply { isAccessible = true }
        val light =
            constructor.newInstance(*Array<Any>(names.size) { 0xffeeeeee00000000UL.toLong() })
        val dark =
            constructor.newInstance(*Array<Any>(names.size) { 0xff11111100000000UL.toLong() })
        val fields =
            HostUiResolver.referenceProperties(
                reflection,
                dynamic,
                mapOf("light" to light, "dark" to dark),
            )
        Reader(
            locals.first,
            locals.second,
            HostReflection.uniqueMethod(
                configs,
                { it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType },
                "dark theme getter",
            ),
            fields.getValue("light"),
            fields.getValue("dark"),
            names.map(getters::getValue),
        )
    }
    private var reported = false

    fun validate() {
        reader
    }

    // 宿主已经返回 Color 的打包位，必须保留 ULong 位模式，不能再走 ARGB Long 工厂。
    fun colorFromPacked(packed: Long): Color = Color(packed.toULong())

    @Composable
    fun read(): NativePalette? {
        val composer = currentComposer
        return try {
            reader.read(composer)
        } catch (t: Throwable) {
            if (!reported) {
                reported = true
                System.err.println(
                    "[SPW Navidrome] Host theme read failed: " + t.javaClass.simpleName,
                )
            }
            null
        }
    }
}
