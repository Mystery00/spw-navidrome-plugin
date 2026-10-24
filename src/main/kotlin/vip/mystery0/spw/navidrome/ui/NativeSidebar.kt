@file:OptIn(
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class,
    androidx.compose.runtime.InternalComposeApi::class,
)

package vip.mystery0.spw.navidrome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.ContentHook
import com.xuncorp.spw.workshop.api.hook.HookHandle

/** 通过官方 DSL Hook 追加一个条目，不修改侧栏数据或宿主曲库。 */
internal class NativeSidebar(
    private val binding: HostUiResolver.Sidebar,
    private val onFailure: () -> Unit,
) : AutoCloseable {
    private var enabled by mutableStateOf(true)
    private var failed by mutableStateOf(false)
    private var handle: HookHandle? = null

    fun install() {
        val target = binding.target
        val composerIndex = target.parameterTypes.indexOfFirst { it == Composer::class.java }
        check(composerIndex >= 0) { "Sidebar target is not Composable" }
        handle =
            WorkshopApi.hookRegistrar.appendContent(
                target.declaringClass.name,
                target.name,
                binding.caller.name,
                "content",
                LazyListScope::class.java,
                target.parameterTypes.take(composerIndex).map { it.name },
                100,
                ContentHook { scope, _ ->
                    scope.item(key = "vip.mystery0.spw.navidrome.sidebar") { Entry() }
                },
            )
    }

    @Composable
    private fun Entry() {
        if (!enabled || failed) return
        val palette = HostTheme.read() ?: return
        val composer = currentComposer
        val navigator =
            try {
                checkNotNull(composer.consume(binding.navigatorLocal))
            } catch (_: Exception) {
                SideEffect { fail() }
                return
            }
        val selected =
            try {
                (binding.backStack.invoke(navigator) as? List<*>)?.lastOrNull() == binding.route
            } catch (_: Exception) {
                false
            }
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) palette.selected else Color.Transparent)
                .clickable {
                    if (enabled) {
                        try {
                            if (!selected) binding.navigate.invoke(navigator, binding.route)
                        } catch (_: Exception) {
                            fail()
                        }
                    }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BasicText("♫", style = TextStyle(color = palette.highlight, fontSize = 18.sp))
            BasicText("Navidrome", style = TextStyle(color = palette.text, fontSize = 14.sp))
        }
    }

    private fun fail() {
        if (failed) return
        failed = true
        System.err.println(
            "[SPW Navidrome] Sidebar navigation unavailable; use plugin configuration",
        )
        onFailure()
    }

    override fun close() {
        enabled = false
        handle?.unhook()
        handle = null
    }
}
