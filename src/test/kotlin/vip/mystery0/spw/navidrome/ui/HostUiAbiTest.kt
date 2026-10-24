package vip.mystery0.spw.navidrome.ui

import java.net.URLClassLoader
import java.nio.file.Path
import vip.mystery0.spw.navidrome.host.HostReflection

/** 离线读取真实宿主，只构造主题测试对象，不注册 Hook、不读取用户配置。 */
object HostUiAbiTest {
    @JvmStatic
    fun main(args: Array<String>) {
        URLClassLoader(
                arrayOf(Path.of(args[0]).toUri().toURL()),
                HostUiAbiTest::class.java.classLoader,
            )
            .use { loader ->
                val reflection = HostUiResolver.reflection(loader)
                val config = HostUiResolver.configPage(reflection)
                check(config.target.parameterCount == 5)
                val ownRoute =
                    config.route.getConstructor(String::class.java).newInstance("test.navidrome")
                check(config.pluginId.invoke(ownRoute) == "test.navidrome")
                val sidebar = HostUiResolver.sidebar(reflection, "vip.mystery0.spw.navidrome")
                check(sidebar.target.name == "LazyColumn")
                check(sidebar.navigate.parameterTypes.single().isInstance(sidebar.route))
                SidebarLifecycleTest.run(sidebar)
                val colors = reflection.type("com.moriafly.salt.ui.SaltColors")
                val configs = reflection.type("com.moriafly.salt.ui.SaltConfigs")
                val dynamic = reflection.type("com.moriafly.salt.ui.SaltDynamicColors")
                val names =
                    setOf(
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
                val getters = HostUiResolver.longProperties(reflection, colors, names)
                check(getters.size == 11)
                val constructor =
                    colors.declaredConstructors
                        .single { it.parameterCount == 11 }
                        .apply { isAccessible = true }
                val light =
                    constructor.newInstance(*Array<Any>(11) { 0xffeeeeee00000000UL.toLong() })
                val dark =
                    constructor.newInstance(*Array<Any>(11) { 0xff11111100000000UL.toLong() })
                val fields =
                    HostUiResolver.referenceProperties(
                        reflection,
                        dynamic,
                        mapOf("light" to light, "dark" to dark),
                    )
                check(fields.size == 2)
                val locals = HostUiResolver.themeLocals(reflection, configs, dynamic)
                check(locals.first !== locals.second)
                val getter =
                    HostReflection.uniqueMethod(
                        configs,
                        {
                            it.parameterCount == 0 &&
                                it.returnType == Boolean::class.javaPrimitiveType
                        },
                        "dark theme getter",
                    )
                val indication = loader.loadClass("androidx.compose.foundation.Indication")
                val effect =
                    java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(indication)) { _, _, _
                        ->
                        null
                    }
                for (value in listOf(false, true)) {
                    val probe =
                        configs
                            .getConstructor(Boolean::class.javaPrimitiveType, indication)
                            .newInstance(value, effect)
                    check(getter.invoke(probe) == value)
                }
            }
        println(
            "PASS dynamic host UI theme, configuration call site and sidebar navigation bindings",
        )
    }
}
