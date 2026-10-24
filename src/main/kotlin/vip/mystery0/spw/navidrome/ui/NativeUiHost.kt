@file:OptIn(
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class,
    androidx.compose.runtime.InternalComposeApi::class,
)

package vip.mystery0.spw.navidrome.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import vip.mystery0.spw.navidrome.AppSession
import vip.mystery0.spw.navidrome.NavidromePlugin
import vip.mystery0.spw.navidrome.credentials.CredentialStore

/** 仅追加本插件页面；在共享配置页之后绘制，避免争用替换槽位。 */
object NativeUiHost {
    private var handle: HookHandle? = null
    private var state: NativeState? = null
    private var sidebar: NativeSidebar? = null
    private var configBinding: HostUiResolver.ConfigPage? = null
    var sidebarUnavailable by mutableStateOf(false)
        private set

    @JvmStatic
    fun install(version: String, session: AppSession, store: CredentialStore) {
        HostTheme.validate()
        val loader = NativeUiHost::class.java.classLoader
        val reflection = HostUiResolver.reflection(loader)
        val binding = HostUiResolver.configPage(reflection)
        configBinding = binding
        try {
            val target = binding.target
            handle =
                WorkshopApi.hookRegistrar.afterComposable(
                    target.declaringClass.name,
                    target.name,
                    binding.caller.name,
                    target.parameterTypes.takeWhile { it != Composer::class.java }.map { it.name },
                    100,
                    Panel,
                )
            // 配置页注册成功后再恢复凭据；侧栏失败不撤销配置页。
            state = NativeState(session, store)
        } catch (t: Throwable) {
            uninstall()
            throw t
        }
        sidebarUnavailable = false
        try {
            sidebar =
                NativeSidebar(HostUiResolver.sidebar(reflection, NavidromePlugin.ID)) {
                        sidebarUnavailable = true
                    }
                    .also { it.install() }
        } catch (t: Throwable) {
            sidebar?.close()
            sidebar = null
            sidebarUnavailable = true
            System.err.println(
                "[SPW Navidrome] Sidebar hook unavailable: " + t.javaClass.simpleName,
            )
        }
        System.out.println("[SPW Navidrome] Native UI resolved for host " + version)
    }

    @JvmStatic
    fun uninstall() {
        try {
            sidebar?.close()
        } finally {
            sidebar = null
            try {
                handle?.unhook()
            } finally {
                handle = null
                configBinding = null
                state?.close()
                state = null
            }
        }
    }

    private object Panel : ComposableHook() {
        @Composable
        override fun Content(call: UiHookCall) {
            val args = call.arguments
            val route = args.getOrNull(0)
            val binding = configBinding
            val id =
                try {
                    if (route != null && binding?.route?.isInstance(route) == true)
                        binding.pluginId.invoke(route)
                    else null
                } catch (t: Exception) {
                    null
                }
            val current = state
            if (id == NavidromePlugin.ID && current != null) {
                val palette = HostTheme.read()
                if (palette != null)
                    CompositionLocalProvider(LocalNativePalette provides palette) { Page(current) }
                else
                    Box(Modifier.fillMaxSize().background(Color(0xff20242c)).padding(24.dp)) {
                        BasicText(
                            "当前宿主主题不可用，无法显示 Navidrome 页面。请重启插件或检查宿主兼容性。",
                            style = TextStyle(color = Color.White, fontSize = 16.sp),
                        )
                    }
            }
        }
    }
}

private val ink: Color
    @Composable get() = LocalNativePalette.current.text
private val muted: Color
    @Composable get() = LocalNativePalette.current.subText
private val blue: Color
    @Composable get() = LocalNativePalette.current.highlight
private val line: Color
    @Composable get() = LocalNativePalette.current.border
private val surface: Color
    @Composable get() = LocalNativePalette.current.surface
private val background: Color
    @Composable get() = LocalNativePalette.current.background
private val rounded = RoundedCornerShape(8.dp)

@Composable
private fun Modifier.card() = clip(rounded).background(surface).border(1.dp, line, rounded)

@Composable
private fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ink,
    size: Int = 14,
    bold: Boolean = false,
) {
    BasicText(
        text,
        modifier,
        style =
            TextStyle(
                color = color,
                fontSize = size.sp,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            ),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Action(
    text: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.clip(rounded)
            .background(
                if (primary && enabled) blue
                else if (enabled) surface else LocalNativePalette.current.disabled,
            )
            .border(1.dp, if (primary && enabled) blue else line, rounded)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            text,
            color =
                if (!enabled) muted
                else if (primary) LocalNativePalette.current.onHighlight else ink,
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label, color = muted)
        BasicTextField(
            value,
            onChange,
            Modifier.fillMaxWidth()
                .clip(rounded)
                .background(
                    if (enabled) LocalNativePalette.current.input
                    else LocalNativePalette.current.disabled,
                )
                .border(1.dp, line, rounded)
                .padding(11.dp),
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = if (enabled) ink else muted, fontSize = 14.sp),
            visualTransformation =
                if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(blue),
        )
    }
}

@Composable
private fun Account(s: NativeState, wide: Boolean) {
    var confirmForget by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().card().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label("账户与连接", size = 16, bold = true)
        Field("服务器地址 · https://music.example.com", s.address, enabled = !s.busy && !s.restoring) {
            s.address = it
        }
        if (wide)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    Field("用户名", s.username, enabled = !s.busy && !s.restoring) { s.username = it }
                }
                Box(Modifier.weight(1f)) {
                    Field("密码", s.password, true, enabled = !s.busy && !s.restoring) {
                        s.password = it
                    }
                }
            }
        else {
            Field("用户名", s.username, enabled = !s.busy && !s.restoring) { s.username = it }
            Field("密码", s.password, true, enabled = !s.busy && !s.restoring) { s.password = it }
        }
        Row(
            Modifier.toggleable(s.allowHttp, enabled = !s.busy, role = Role.Checkbox) {
                    s.allowHttp = it
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (s.allowHttp) blue else surface)
                    .border(1.dp, if (s.allowHttp) blue else muted, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (s.allowHttp) Label("✓", color = LocalNativePalette.current.onHighlight)
            }
            Label("明确允许 HTTP（仅用于可信局域网）")
        }
        Label(
            s.credentialStatus,
            color = if (s.credentialError) LocalNativePalette.current.error else muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(if (s.busy && !s.connected) "正在连接…" else "连接并保存", !s.busy, primary = true) {
                confirmForget = false
                s.login()
            }
            Action("退出并忘记") { confirmForget = true }
        }
        if (confirmForget)
            Column(
                Modifier.fillMaxWidth()
                    .clip(rounded)
                    .background(LocalNativePalette.current.warningBackground)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Label("退出此账户并删除本设备保存的加密凭据？", color = LocalNativePalette.current.warning)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("确认忘记") {
                        confirmForget = false
                        s.logout()
                    }
                    Action("取消") { confirmForget = false }
                }
            }
    }
}

@Composable
private fun CollectionPanel(s: NativeState, modifier: Modifier) {
    Column(modifier.card()) {
        Label(
            if (s.mode == "playlists") "歌单" else "专辑",
            Modifier.padding(16.dp),
            size = 16,
            bold = true,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        if (s.collections.isEmpty())
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label(
                    if (s.busy) "正在加载…" else if (!s.connected) "连接服务器后查看音乐库" else "暂无结果",
                    color = muted,
                )
            }
        else
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
                itemsIndexed(s.collections) { _, item ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(rounded)
                            .background(
                                if (s.selectedCollectionId == item.id())
                                    LocalNativePalette.current.selected
                                else surface,
                            )
                            .clickable(enabled = !s.busy) { s.open(item) }
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Label(
                            item.name(),
                            color = if (s.selectedCollectionId == item.id()) blue else ink,
                        )
                        Label(
                            listOf(item.artist(), "${item.count()} 首")
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            color = muted,
                        )
                    }
                }
            }
        if (s.more && s.mode != "search")
            Box(Modifier.padding(10.dp)) { Action("加载更多", !s.busy) { s.next() } }
    }
}

@Composable
private fun SongPanel(s: NativeState, modifier: Modifier, wide: Boolean) {
    Column(modifier.card()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label(s.selectionTitle, size = 16, bold = true)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("${s.songs.size} 首歌曲", color = muted)
                Action("播放选中", s.selected in s.songs.indices, primary = true) { s.play() }
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .background(LocalNativePalette.current.input)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Label("#", Modifier.width(24.dp), color = muted)
            Label("标题", Modifier.weight(1f), color = muted)
            if (wide) Label("艺术家", Modifier.weight(.65f), color = muted)
            Label("时长", Modifier.width(45.dp), color = muted)
        }
        if (s.songs.isEmpty())
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label(
                    if (s.busy) "正在加载歌曲…"
                    else if (!s.connected) "连接后开始浏览你的音乐库"
                    else if (s.mode == "search") "没有搜索结果，试试其他关键词"
                    else if (s.selectedCollectionId.isNotEmpty()) "此列表暂无歌曲" else "在左侧选择专辑或歌单",
                    color = muted,
                )
            }
        else
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(s.songs) { index, song ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(
                                if (s.selected == index) LocalNativePalette.current.selected
                                else surface,
                            )
                            .clickable { s.selected = index }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Label(
                            "${index+1}",
                            Modifier.width(24.dp),
                            color = if (s.selected == index) blue else muted,
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Label(song.title(), color = if (s.selected == index) blue else ink)
                            Label(
                                if (wide) song.album() else "${song.artist()} · ${song.album()}",
                                color = muted,
                            )
                        }
                        if (wide) Label(song.artist(), Modifier.weight(.65f))
                        Label(
                            "${song.durationMs()/60000}:${(song.durationMs()/1000%60).toString().padStart(2,'0')}",
                            Modifier.width(45.dp),
                            color = muted,
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(line))
                }
            }
        if (s.mode == "search" && s.more)
            Box(Modifier.padding(10.dp)) { Action("加载更多歌曲", !s.busy) { s.next() } }
    }
}

@Composable
private fun Page(s: NativeState) {
    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val wide = maxWidth >= 720.dp
        val bodyHeight =
            (maxHeight - 380.dp - (if (s.showAccount) 330.dp else 0.dp)).coerceAtLeast(260.dp)
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 24.dp else 14.dp)
                .padding(top = 20.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Label("Navidrome", size = 28, bold = true)
                    Label(
                        if (s.restoring) "正在恢复账号…" else if (s.connected) "● 已连接音乐库" else "尚未连接音乐库",
                        color = if (s.connected) LocalNativePalette.current.success else muted,
                    )
                }
                Action(if (s.showAccount && s.connected) "收起账户" else "账户管理") {
                    s.accountExpanded = !s.accountExpanded
                }
            }
            if (NativeUiHost.sidebarUnavailable) {
                Label("主菜单入口不可用，仍可通过模组配置使用 Navidrome。", color = LocalNativePalette.current.warning)
            }
            if (s.showAccount) Account(s, wide)
            else if (s.connected && (s.credentialError || s.credentialStatus.startsWith("正在")))
                Label(
                    s.credentialStatus,
                    color = if (s.credentialError) LocalNativePalette.current.error else muted,
                )
            if (wide)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Action("专辑", s.connected && !s.busy, primary = s.mode == "albums") {
                        s.browse("albums")
                    }
                    Action("歌单", s.connected && !s.busy, primary = s.mode == "playlists") {
                        s.browse("playlists")
                    }
                    Box(Modifier.weight(1f)) { Field("搜索歌曲", s.query) { s.query = it } }
                    Action("搜索", s.connected && !s.busy, primary = true) { s.search() }
                }
            else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("专辑", s.connected && !s.busy, primary = s.mode == "albums") {
                        s.browse("albums")
                    }
                    Action("歌单", s.connected && !s.busy, primary = s.mode == "playlists") {
                        s.browse("playlists")
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(Modifier.weight(1f)) { Field("搜索歌曲", s.query) { s.query = it } }
                    Action("搜索", s.connected && !s.busy, primary = true) { s.search() }
                }
            }
            Label(
                (if (s.busy) "正在处理 · " else "") + s.status,
                color =
                    if (s.error) LocalNativePalette.current.error else if (s.busy) blue else muted,
            )
            if (wide)
                Row(
                    Modifier.fillMaxWidth().height(bodyHeight),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (s.mode != "search") CollectionPanel(s, Modifier.weight(1f).fillMaxHeight())
                    SongPanel(
                        s,
                        Modifier.weight(if (s.mode == "search") 1f else 2.4f).fillMaxHeight(),
                        true,
                    )
                }
            else {
                if (s.mode != "search") CollectionPanel(s, Modifier.fillMaxWidth().height(180.dp))
                SongPanel(s, Modifier.fillMaxWidth().height(340.dp), false)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("播放由 SPW 原生播放栏控制", Modifier.weight(1f), color = muted)
                Action("清理缓存") { s.session.clearCache { s.status = it } }
            }
        }
    }
}
