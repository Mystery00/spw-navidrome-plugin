@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package vip.mystery0.spw.navidrome.ui

import androidx.compose.foundation.lazy.LazyListScope
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.*
import java.lang.reflect.Proxy

/** 只在测试进程注入假注册器，验证注册边界和资源释放，不修改实际宿主。 */
internal object SidebarLifecycleTest {
    fun run(binding: HostUiResolver.Sidebar) {
        var registrations = 0
        var releases = 0
        var reject = false
        val registrar =
            object : HookRegistrar() {
                override fun hookMethod(
                    className: String,
                    methodName: String,
                    parameterTypes: List<String>?,
                    callback: MethodHook,
                ): HookHandle = error("Unexpected method hook")

                override fun replaceComposable(
                    className: String,
                    methodName: String,
                    inClass: String,
                    parameterTypes: List<String>?,
                    priority: Int,
                    content: ComposableHook,
                ): HookHandle = error("Unexpected replacement hook")

                override fun afterComposable(
                    className: String,
                    methodName: String,
                    inClass: String,
                    parameterTypes: List<String>?,
                    priority: Int,
                    content: ComposableHook,
                ): HookHandle = error("Unexpected Composable hook")

                override fun <S : Any> appendContent(
                    className: String,
                    methodName: String,
                    inClass: String,
                    parameter: String,
                    scopeType: Class<S>,
                    parameterTypes: List<String>?,
                    priority: Int,
                    content: ContentHook<S>,
                ): HookHandle {
                    check(className == binding.target.declaringClass.name)
                    check(methodName == binding.target.name)
                    check(inClass == binding.caller.name)
                    check(parameter == "content")
                    check(scopeType == LazyListScope::class.java)
                    check(parameterTypes?.last() == "kotlin.jvm.functions.Function1")
                    if (reject) throw IllegalArgumentException("Synthetic unsupported call site")
                    registrations++
                    return object : HookHandle {
                        override fun unhook() {
                            releases++
                        }
                    }
                }
            }
        val api =
            Proxy.newProxyInstance(
                WorkshopApi::class.java.classLoader,
                arrayOf(WorkshopApi::class.java),
            ) { _, method, _ ->
                check(method.name == "getHookRegistrar") {
                    "Sidebar must not access accounts or playback"
                }
                registrar
            } as WorkshopApi
        WorkshopApi.instance = api
        val sidebar = NativeSidebar(binding) { error("No UI navigation was performed") }
        sidebar.install()
        check(registrations == 1 && releases == 0)
        sidebar.close()
        sidebar.close()
        check(releases == 1) { "Repeated stop must not release a hook twice" }
        reject = true
        val unsupported = NativeSidebar(binding) { error("No UI navigation was performed") }
        try {
            unsupported.install()
            error("Rejected registration must propagate to configuration-page fallback")
        } catch (_: IllegalArgumentException) {}
        unsupported.close()
        check(registrations == 1 && releases == 1) {
            "Failed registration must not retain a handle"
        }
        println("PASS sidebar DSL registration and repeated stop cleanup")
    }
}
