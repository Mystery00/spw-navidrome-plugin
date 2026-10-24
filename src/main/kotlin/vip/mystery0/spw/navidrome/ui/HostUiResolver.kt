package vip.mystery0.spw.navidrome.ui

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocal
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import vip.mystery0.spw.navidrome.host.HostReflection

/** 只在初始化时解析宿主；语义来自 Metadata，真实 JVM 成员来自唯一结构与合成实例。 */
internal object HostUiResolver {
    data class ConfigPage(
        val target: Method,
        val caller: Class<*>,
        val route: Class<*>,
        val pluginId: Method,
    )

    data class Sidebar(
        val target: Method,
        val caller: Class<*>,
        val navigatorLocal: CompositionLocal<Any?>,
        val navigate: Method,
        val backStack: Method,
        val route: Any,
    )

    fun reflection(loader: ClassLoader): HostReflection =
        HostReflection(
            loader,
            Class.forName("com.xuncorp.voxzen.service.PlaybackService", false, loader),
        )

    private fun facade(reflection: HostReflection, vararg tokens: String): Class<*> =
        unique(
            reflection.typesWithMetadata(*tokens).filter {
                reflection.classFile(it).metadataKind() in listOf(2, 5)
            },
            "Metadata facade ${tokens.joinToString()}",
        )

    fun configPage(reflection: HostReflection): ConfigPage {
        val route =
            reflection.type("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ModConfigContent")
        val vm = reflection.type("com.xuncorp.voxzen.ui.screen.workshop.ModConfigViewModel")
        val owner = facade(reflection, "ModConfigScreen", "modConfigContent")
        val parameters =
            listOf(
                route,
                vm,
                Composer::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        val target =
            HostReflection.uniqueMethod(
                owner,
                {
                    Modifier.isStatic(it.modifiers) &&
                        it.returnType == Void.TYPE &&
                        it.parameterTypes.toList() == parameters
                },
                "mod configuration Composable",
            )
        val caller =
            reflection.type(
                "com.xuncorp.voxzen.ui.navigation.ComposableSingletons\$AppNavigation_desktopKt",
            )
        requireCallSite(reflection, caller, target)
        val pluginId =
            HostReflection.uniqueMethod(
                route,
                {
                    it.name == "getPluginId" &&
                        !Modifier.isStatic(it.modifiers) &&
                        it.parameterCount == 0 &&
                        it.returnType == String::class.java
                },
                "configuration plugin identifier",
            )
        return ConfigPage(target, caller, route, pluginId)
    }

    fun sidebar(reflection: HostReflection, pluginId: String): Sidebar {
        val caller = facade(reflection, "Sidebar", "SidebarRouteItem", "SidebarPlaylistItem")
        val calls =
            reflection
                .classFile(caller)
                .methods()
                .flatMap { it.instructions() }
                .mapNotNull { it.member() }
                .filter {
                    it.owner() == "androidx/compose/foundation/lazy/LazyDslKt" &&
                        it.name() == "LazyColumn"
                }
        val target = reflection.referencedMethod(unique(calls, "sidebar LazyColumn call"))
        check(target.parameterTypes.count { it.name == "kotlin.jvm.functions.Function1" } == 1) {
            "Sidebar content builder is ambiguous"
        }
        val navigation = reflection.type("com.xuncorp.voxzen.ui.navigation.AppNavigationKt")
        val navigator = reflection.type("com.xuncorp.voxzen.ui.navigation.Navigator")
        val route = reflection.type("com.xuncorp.voxzen.ui.navigation.ScreenRoute")
        val configRoute =
            reflection.type("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ModConfigContent")
        // 这些公开语义名称未被混淆，签名仍须验证，不能把其他单参数操作误当导航。
        val local = navigation.getMethod("getLocalNavigator")
        check(CompositionLocal::class.java.isAssignableFrom(local.returnType))
        @Suppress("UNCHECKED_CAST")
        return Sidebar(
            target,
            caller,
            local.invoke(null) as CompositionLocal<Any?>,
            navigator.getMethod("navigate", route),
            navigator.getMethod("getNavBackStack"),
            configRoute.getConstructor(String::class.java).newInstance(pluginId),
        )
    }

    fun requireCallSite(reflection: HostReflection, caller: Class<*>, target: Method) {
        val descriptor =
            java.lang.invoke.MethodType.methodType(
                    target.returnType,
                    target.parameterTypes.toList(),
                )
                .descriptorString()
        val calls =
            reflection
                .classFile(caller)
                .methods()
                .flatMap { it.instructions() }
                .mapNotNull { it.member() }
                .filter {
                    it.owner() == target.declaringClass.name.replace('.', '/') &&
                        it.name() == target.name &&
                        it.descriptor() == descriptor
                }
        unique(calls, "configuration Composable call")
    }

    /** 不按字段顺序猜色值；不同构造参数使用不同合法的打包色，再按返回值定位 getter。 */
    fun longProperties(
        reflection: HostReflection,
        type: Class<*>,
        required: Set<String>,
    ): Map<String, Method> {
        val constructors =
            type.declaredConstructors.filter {
                it.parameterCount >= required.size &&
                    it.parameterTypes.all { p -> p == Long::class.javaPrimitiveType }
            }
        val constructor = unique(constructors, "color constructor").apply { isAccessible = true }
        val names = reflection.constructorParameterNames(type, constructor.parameterCount)
        if (!names.containsAll(required) || names.distinct().size != names.size) {
            throw ReflectiveOperationException("Color constructor metadata is incomplete")
        }
        val values = names.indices.map { (0xff000001L + it * 0x10101L) shl 32 }
        val probe = constructor.newInstance(*values.toTypedArray())
        val getters =
            type.declaredMethods.filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    it.returnType == Long::class.javaPrimitiveType
            }
        return required.associateWith { semantic ->
            unique(
                getters.filter {
                    it.isAccessible = true
                    it.invoke(probe) == values[names.indexOf(semantic)]
                },
                "color $semantic",
            )
        }
    }

    fun referenceProperties(
        reflection: HostReflection,
        type: Class<*>,
        values: Map<String, Any>,
    ): Map<String, Field> {
        val constructor =
            unique(
                type.declaredConstructors.filter {
                    it.parameterCount == values.size &&
                        it.parameterTypes.all { p -> p == values.values.first().javaClass }
                },
                "dynamic palette constructor",
            )
        val names = reflection.constructorParameterNames(type, constructor.parameterCount)
        if (names.toSet() != values.keys)
            throw ReflectiveOperationException("Dynamic palette metadata changed")
        constructor.isAccessible = true
        val probe = constructor.newInstance(*names.map { values.getValue(it) }.toTypedArray())
        return values.mapValues { (name, value) ->
            unique(
                type.declaredFields
                    .filter { !Modifier.isStatic(it.modifiers) && it.type == value.javaClass }
                    .filter {
                        it.isAccessible = true
                        it.get(probe) === value
                    },
                "dynamic palette $name",
            )
        }
    }

    fun themeLocals(
        reflection: HostReflection,
        configs: Class<*>,
        dynamic: Class<*>,
    ): Pair<CompositionLocal<Any?>, CompositionLocal<Any?>> {
        val theme = facade(reflection, "SaltTheme", "LocalSaltConfigs", "LocalSaltDynamicColors")
        val locals =
            theme.declaredMethods
                .filter {
                    Modifier.isStatic(it.modifiers) &&
                        it.parameterCount == 0 &&
                        CompositionLocal::class.java.isAssignableFrom(it.returnType)
                }
                .map {
                    it.isAccessible = true
                    it.invoke(null) as CompositionLocal<*>
                }
                .distinct()
        val holderGetter =
            HostReflection.uniqueMethod(
                CompositionLocal::class.java,
                {
                    it.parameterCount == 0 &&
                        it.returnType.name == "androidx.compose.runtime.ValueHolder"
                },
                "CompositionLocal default holder",
            )
        fun defaultType(local: CompositionLocal<*>): Class<*>? =
            try {
                val holder = holderGetter.invoke(local)
                val getter =
                    HostReflection.uniqueMethod(
                        holder.javaClass,
                        {
                            !Modifier.isStatic(it.modifiers) &&
                                it.parameterCount == 0 &&
                                it.returnType == Any::class.java
                        },
                        "lazy local default",
                    )
                getter.invoke(holder)?.javaClass
            } catch (_: ReflectiveOperationException) {
                null
            }
        val defaults = locals.associateWith(::defaultType)
        @Suppress("UNCHECKED_CAST")
        return Pair(
            unique(locals.filter { defaults[it] == configs }, "Salt config local")
                as CompositionLocal<Any?>,
            unique(locals.filter { defaults[it] == dynamic }, "Salt dynamic colors local")
                as CompositionLocal<Any?>,
        )
    }

    private fun <T> unique(values: List<T>, label: String): T {
        if (values.size != 1)
            throw ReflectiveOperationException("Expected unique $label, found ${values.size}")
        return values.single()
    }
}
