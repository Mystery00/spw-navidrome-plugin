package vip.mystery0.spw.navidrome.ui

import androidx.compose.ui.graphics.Color

/** 颜色位模式与深浅色语义回归，避免重现透明颜色或固定浅色。 */
object NativeThemeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val packed = 0xff12345600000000UL
        check(HostTheme.colorFromPacked(packed.toLong()).value == packed) {
            "Packed host color bits must survive"
        }
        check(HostTheme.colorFromPacked(packed.toLong()).alpha == 1f) {
            "Packed color must stay opaque"
        }
        fun palette(dark: Boolean) =
            NativePalette(
                dark,
                Color(0xff448aff),
                if (dark) Color.White else Color.Black,
                Color.Gray,
                if (dark) Color(0xff181818) else Color.White,
                if (dark) Color(0xff242424) else Color.White,
                Color.Gray,
                Color.White,
                Color.Green,
                Color.Yellow,
                Color.Red,
            )
        val light = palette(false)
        val dark = palette(true)
        check(light.selected != dark.selected) { "Selected surface must follow host palette" }
        check(dark.input.red < .2f && light.input.red > .9f) {
            "Input background must follow host surface"
        }
        check(dark.disabled.red < .3f && light.disabled.red > .9f) {
            "Disabled background must follow host surface"
        }
        check(dark.warningBackground != light.warningBackground) {
            "Warning background must follow host surface"
        }
        check(light.selected.alpha == 1f && dark.selected.alpha == 1f) {
            "Derived surfaces must remain opaque"
        }
        println("PASS host packed colors and theme-derived semantic surfaces")
    }
}
