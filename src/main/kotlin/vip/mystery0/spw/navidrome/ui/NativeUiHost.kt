@file:OptIn(
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class,
    androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package vip.mystery0.spw.navidrome.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
private val subtleLine: Color
    @Composable get() = LocalNativePalette.current.subtleBorder
private val surface: Color
    @Composable get() = LocalNativePalette.current.surface
private val background: Color
    @Composable get() = LocalNativePalette.current.background
private val rounded = RoundedCornerShape(10.dp)
private val controlShape = RoundedCornerShape(8.dp)
private val pillShape = RoundedCornerShape(50)

@Composable
private fun Modifier.card() = clip(rounded).background(surface).border(1.dp, line, rounded)

@Composable
private fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ink,
    size: Int = 14,
    bold: Boolean = false,
    singleLine: Boolean = false,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(
        text,
        modifier,
        style =
            TextStyle(
                color = color,
                fontSize = size.sp,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = align,
            ),
        maxLines = if (singleLine) 1 else 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Badge(text: String, fg: Color, bg: Color) {
    Box(
        Modifier.clip(pillShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, color = fg, size = 12, bold = true, singleLine = true)
    }
}

@Composable
private fun Action(
    text: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(controlShape)
            .background(
                if (primary && enabled) blue
                else if (enabled) surface else LocalNativePalette.current.disabled,
            )
            .border(1.dp, if (primary && enabled) blue else line, controlShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            text,
            color =
                if (!enabled) muted
                else if (primary) LocalNativePalette.current.onHighlight else ink,
            size = 13,
            bold = primary,
            singleLine = true,
        )
    }
}

private fun Modifier.onEnterKey(enabled: Boolean, action: (() -> Unit)?): Modifier {
    if (action == null) return this
    return onPreviewKeyEvent { event ->
        if (
            enabled &&
                event.type == KeyEventType.KeyDown &&
                (event.key == Key.Enter || event.key == Key.NumPadEnter)
        ) {
            action()
            true
        } else {
            false
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String = "",
    secret: Boolean = false,
    enabled: Boolean = true,
    onSubmit: (() -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label, color = muted, size = 12, singleLine = true)
        BasicTextField(
            value,
            onChange,
            Modifier.fillMaxWidth()
                .heightIn(min = 38.dp)
                .onFocusChanged { focused = it.isFocused }
                .onEnterKey(enabled, onSubmit)
                .clip(controlShape)
                .background(
                    if (enabled) LocalNativePalette.current.input
                    else LocalNativePalette.current.disabled,
                )
                .border(1.dp, if (focused && enabled) blue else line, controlShape)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = if (enabled) ink else muted, fontSize = 14.sp),
            visualTransformation =
                if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(blue),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Label(placeholder, color = muted, size = 13, singleLine = true)
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun SearchBar(s: NativeState, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val canSearch = s.connected && !s.busy
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            s.query,
            { s.query = it },
            Modifier.weight(1f)
                .height(36.dp)
                .onFocusChanged { focused = it.isFocused }
                .onEnterKey(canSearch) { s.search() }
                .clip(controlShape)
                .background(LocalNativePalette.current.input)
                .border(1.dp, if (focused) blue else line, controlShape)
                .padding(horizontal = 12.dp),
            enabled = !s.busy,
            singleLine = true,
            textStyle = TextStyle(color = ink, fontSize = 13.sp),
            cursorBrush = SolidColor(blue),
            decorationBox = { innerTextField ->
                Row(
                    Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Label("⌕", color = if (focused) blue else muted, size = 14, singleLine = true)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (s.query.isEmpty()) {
                            Label(
                                "搜索歌曲、艺术家或专辑（按 Enter 搜索）",
                                color = muted,
                                size = 13,
                                singleLine = true,
                            )
                        }
                        innerTextField()
                    }
                    if (s.query.isNotEmpty()) {
                        Box(
                            Modifier.size(20.dp).clip(CircleShape).clickable(enabled = !s.busy) {
                                s.query = ""
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Label("×", color = muted, size = 14, singleLine = true)
                        }
                    }
                }
            },
        )
        Action("搜索", canSearch, primary = true) { s.search() }
    }
}

@Composable
private fun ModeTabs(s: NativeState) {
    val enabled = s.connected && !s.busy
    Row(
        Modifier.height(36.dp)
            .clip(controlShape)
            .background(LocalNativePalette.current.input)
            .border(1.dp, line, controlShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun Tab(title: String, target: String) {
            val active = s.mode == target
            Box(
                Modifier.fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) blue else Color.Transparent)
                    .clickable(enabled = enabled) { s.browse(target) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label(
                    title,
                    color =
                        if (active) LocalNativePalette.current.onHighlight
                        else if (enabled) ink else muted,
                    size = 13,
                    bold = active,
                    singleLine = true,
                )
            }
        }
        Tab("专辑", "albums")
        Tab("歌单", "playlists")
        if (s.mode == "search") {
            Box(
                Modifier.fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(blue)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label(
                    "搜索结果",
                    color = LocalNativePalette.current.onHighlight,
                    size = 13,
                    bold = true,
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun Account(s: NativeState, wide: Boolean) {
    var confirmForget by remember { mutableStateOf(false) }
    val submitLogin: () -> Unit = {
        if (!s.busy) {
            confirmForget = false
            s.login()
        }
    }
    Column(
        Modifier.fillMaxWidth().card().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("账户与服务器连接", size = 15, bold = true, singleLine = true)
            Badge("DPAPI 加密保护", blue, LocalNativePalette.current.selected)
        }
        Field(
            "服务器根地址（不要追加 /rest）",
            s.address,
            placeholder = "https://music.example.com",
            enabled = !s.busy && !s.restoring,
            onSubmit = submitLogin,
        ) {
            s.address = it
        }
        if (wide)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    Field(
                        "用户名",
                        s.username,
                        placeholder = "输入 Navidrome 用户名",
                        enabled = !s.busy && !s.restoring,
                        onSubmit = submitLogin,
                    ) {
                        s.username = it
                    }
                }
                Box(Modifier.weight(1f)) {
                    Field(
                        "密码",
                        s.password,
                        placeholder = if (s.connected) "留空则重新输入以更新凭据" else "输入登录密码",
                        secret = true,
                        enabled = !s.busy && !s.restoring,
                        onSubmit = submitLogin,
                    ) {
                        s.password = it
                    }
                }
            }
        else {
            Field(
                "用户名",
                s.username,
                placeholder = "输入 Navidrome 用户名",
                enabled = !s.busy && !s.restoring,
                onSubmit = submitLogin,
            ) {
                s.username = it
            }
            Field(
                "密码",
                s.password,
                placeholder = if (s.connected) "留空则重新输入以更新凭据" else "输入登录密码",
                secret = true,
                enabled = !s.busy && !s.restoring,
                onSubmit = submitLogin,
            ) {
                s.password = it
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .toggleable(s.allowHttp, enabled = !s.busy, role = Role.Checkbox) {
                        s.allowHttp = it
                    }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
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
                    if (s.allowHttp)
                        Label("✓", color = LocalNativePalette.current.onHighlight, size = 12)
                }
                Label("明确允许 HTTP（仅用于可信局域网）", size = 13)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("退出并忘记", enabled = !s.busy) { confirmForget = true }
                Action(
                    if (s.busy && !s.connected) "正在连接…" else "连接并保存",
                    !s.busy,
                    primary = true,
                    onClick = submitLogin,
                )
            }
        }
        Label(
            s.credentialStatus,
            color = if (s.credentialError) LocalNativePalette.current.error else muted,
            size = 12,
        )
        if (confirmForget)
            Row(
                Modifier.fillMaxWidth()
                    .clip(controlShape)
                    .background(LocalNativePalette.current.warningBackground)
                    .border(1.dp, LocalNativePalette.current.warning, controlShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(
                    "确认退出此账户并清除本设备保存的加密凭据？",
                    Modifier.weight(1f),
                    color = LocalNativePalette.current.warning,
                    size = 13,
                    bold = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("取消") { confirmForget = false }
                    Action("确认忘记", primary = true) {
                        confirmForget = false
                        s.logout()
                    }
                }
            }
    }
}

@Composable
private fun CollectionPanel(s: NativeState, modifier: Modifier) {
    Column(modifier.card()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(
                if (s.mode == "playlists") "歌单列表" else "专辑列表",
                size = 15,
                bold = true,
                singleLine = true,
            )
            if (s.collections.isNotEmpty()) {
                Badge(
                    "${s.collections.size} 项",
                    muted,
                    LocalNativePalette.current.input,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        if (s.collections.isEmpty())
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Label(
                        if (s.busy) "正在加载列表…" else if (!s.connected) "尚未连接服务器" else "暂无可用内容",
                        color = ink,
                        bold = true,
                    )
                    Label(
                        if (s.busy) "请稍候，正在从 Navidrome 获取数据"
                        else if (!s.connected) "完成上方账户连接后即可浏览专辑与歌单" else "当前分类下没有找到专辑或歌单",
                        color = muted,
                        size = 12,
                    )
                }
            }
        else
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(s.collections) { _, item ->
                    val active = s.selectedCollectionId == item.id()
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(controlShape)
                            .background(
                                if (active) LocalNativePalette.current.selected
                                else Color.Transparent,
                            )
                            .clickable(enabled = !s.busy) { s.open(item) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.width(3.dp)
                                .height(28.dp)
                                .clip(pillShape)
                                .background(if (active) blue else Color.Transparent),
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Label(
                                item.name(),
                                color = if (active) blue else ink,
                                size = 14,
                                bold = active,
                                singleLine = true,
                            )
                            Label(
                                item.artist().ifBlank { "未知艺术家" },
                                color = muted,
                                size = 12,
                                singleLine = true,
                            )
                        }
                        Badge(
                            "${item.count()} 首",
                            if (active) blue else muted,
                            if (active) surface else LocalNativePalette.current.input,
                        )
                    }
                }
            }
        if (s.more && s.mode != "search") {
            Box(Modifier.fillMaxWidth().height(1.dp).background(subtleLine))
            Box(Modifier.fillMaxWidth().padding(8.dp)) {
                Action("加载更多", !s.busy, modifier = Modifier.fillMaxWidth()) { s.next() }
            }
        }
    }
}

@Composable
private fun SongPanel(s: NativeState, modifier: Modifier, wide: Boolean) {
    Column(modifier.card()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Label(s.selectionTitle, size = 15, bold = true, singleLine = true)
                Label(
                    if (s.songs.isEmpty()) "选择专辑、歌单或搜索以查看曲目"
                    else "共 ${s.songs.size} 首歌曲 · 双击曲目可直接播放",
                    color = muted,
                    size = 12,
                    singleLine = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("播放全部", s.songs.isNotEmpty() && !s.busy) {
                    if (s.songs.isNotEmpty()) {
                        s.selected = 0
                        s.play()
                    }
                }
                Action("播放选中", s.selected in s.songs.indices && !s.busy, primary = true) {
                    s.play()
                }
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .background(LocalNativePalette.current.input)
                .border(1.dp, subtleLine)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("#", Modifier.width(28.dp), color = muted, size = 12, singleLine = true)
            Label("标题", Modifier.weight(1.3f), color = muted, size = 12, singleLine = true)
            if (wide) {
                Label("艺术家", Modifier.weight(.75f), color = muted, size = 12, singleLine = true)
                Label("专辑", Modifier.weight(.75f), color = muted, size = 12, singleLine = true)
            }
            Label(
                "时长",
                Modifier.width(48.dp),
                color = muted,
                size = 12,
                singleLine = true,
                align = TextAlign.End,
            )
        }
        if (s.songs.isEmpty())
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Label(
                        if (s.busy) "正在加载歌曲…"
                        else if (!s.connected) "等待连接音乐库"
                        else if (s.mode == "search") "未找到匹配的歌曲"
                        else if (s.selectedCollectionId.isNotEmpty()) "此列表暂无歌曲" else "尚未选择专辑或歌单",
                        color = ink,
                        bold = true,
                    )
                    Label(
                        if (s.busy) "正在请求曲目详情，请稍候"
                        else if (!s.connected) "连接 Navidrome 服务器后即可浏览并播放歌曲"
                        else if (s.mode == "search") "请尝试更换关键词重新搜索"
                        else if (s.selectedCollectionId.isNotEmpty()) "该集合中不包含可播放的音轨"
                        else "从左侧列表点击任意专辑或歌单以加载曲目",
                        color = muted,
                        size = 12,
                    )
                }
            }
        else
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            ) {
                itemsIndexed(s.songs) { index, song ->
                    val active = s.selected == index
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (active) LocalNativePalette.current.selected
                                else Color.Transparent,
                            )
                            .combinedClickable(
                                onClick = { s.selected = index },
                                onDoubleClick = {
                                    s.selected = index
                                    s.play()
                                },
                            )
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Label(
                            if (active) "▶" else "${index + 1}",
                            Modifier.width(28.dp),
                            color = if (active) blue else muted,
                            size = if (active) 12 else 13,
                            bold = active,
                            singleLine = true,
                        )
                        if (wide) {
                            Label(
                                song.title(),
                                Modifier.weight(1.3f),
                                color = if (active) blue else ink,
                                size = 14,
                                bold = active,
                                singleLine = true,
                            )
                            Label(
                                song.artist().ifBlank { "未知艺术家" },
                                Modifier.weight(.75f),
                                color = if (active) ink else muted,
                                size = 13,
                                singleLine = true,
                            )
                            Label(
                                song.album().ifBlank { "未知专辑" },
                                Modifier.weight(.75f),
                                color = muted,
                                size = 13,
                                singleLine = true,
                            )
                        } else {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Label(
                                    song.title(),
                                    color = if (active) blue else ink,
                                    size = 14,
                                    bold = active,
                                    singleLine = true,
                                )
                                Label(
                                    listOf(song.artist(), song.album())
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    color = muted,
                                    size = 12,
                                    singleLine = true,
                                )
                            }
                        }
                        Label(
                            "${song.durationMs() / 60000}:${(song.durationMs() / 1000 % 60).toString().padStart(2, '0')}",
                            Modifier.width(48.dp),
                            color = if (active) blue else muted,
                            size = 12,
                            singleLine = true,
                            align = TextAlign.End,
                        )
                    }
                    if (index < s.songs.lastIndex) {
                        Box(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .height(1.dp)
                                .background(subtleLine),
                        )
                    }
                }
            }
        if (s.mode == "search" && s.more) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(subtleLine))
            Box(Modifier.fillMaxWidth().padding(8.dp)) {
                Action("加载更多歌曲", !s.busy, modifier = Modifier.fillMaxWidth()) { s.next() }
            }
        }
    }
}

@Composable
private fun StatusBar(s: NativeState) {
    val palette = LocalNativePalette.current
    val bg =
        if (s.error) palette.errorBackground else if (s.busy) palette.selected else palette.input
    val fg = if (s.error) palette.error else if (s.busy) blue else muted
    Row(
        Modifier.fillMaxWidth()
            .clip(controlShape)
            .background(bg)
            .border(1.dp, if (s.error) palette.error else subtleLine, controlShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(
                if (s.error) "!" else if (s.busy) "◌" else "●",
                color = fg,
                size = 12,
                bold = true,
                singleLine = true,
            )
            Label(
                (if (s.busy && !s.status.startsWith("正在")) "正在处理 · " else "") + s.status,
                color = fg,
                size = 12,
                singleLine = true,
            )
        }
        Label(
            "播放与歌词由 SPW 原生播放栏承载",
            color = muted,
            size = 12,
            singleLine = true,
        )
    }
}

@Composable
private fun Page(s: NativeState) {
    val palette = LocalNativePalette.current
    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val wide = maxWidth >= 720.dp
        val viewportHeight = maxHeight
        val compact = s.showAccount || !wide || viewportHeight < 560.dp
        val scrollPanelHeight = (viewportHeight - 260.dp).coerceAtLeast(320.dp)
        val scrollState = rememberScrollState()
        val containerModifier =
            Modifier.fillMaxSize()
                .then(if (compact) Modifier.verticalScroll(scrollState) else Modifier)
                .padding(horizontal = if (wide) 24.dp else 14.dp)
                .padding(top = 18.dp, bottom = 92.dp)
        Column(
            containerModifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 顶部标题栏：连接状态徽章与快捷操作
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Label("Navidrome", size = 24, bold = true, singleLine = true)
                    if (s.restoring) {
                        Badge("正在恢复账号…", blue, palette.selected)
                    } else if (s.connected) {
                        Badge(
                            "● 已连接" + (if (s.username.isNotBlank()) " · ${s.username}" else ""),
                            palette.success,
                            palette.successBackground,
                        )
                    } else {
                        Badge("○ 未连接", muted, palette.input)
                    }
                    if (s.connected && !s.showAccount && s.address.isNotBlank() && wide) {
                        Label(s.address, color = muted, size = 12, singleLine = true)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.connected) {
                        Action("刷新", enabled = !s.busy) {
                            if (s.mode == "search") s.search() else s.browse(s.mode)
                        }
                    }
                    Action("清理缓存") { s.session.clearCache { s.status = it } }
                    Action(
                        if (s.showAccount && s.connected) "收起账户" else "账户管理",
                        primary = !s.connected && !s.showAccount,
                    ) {
                        s.accountExpanded = !s.accountExpanded
                    }
                }
            }
            if (NativeUiHost.sidebarUnavailable) {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(controlShape)
                        .background(palette.warningBackground)
                        .border(1.dp, palette.warning, controlShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Label(
                        "主菜单入口不可用，仍可通过模组配置使用 Navidrome。",
                        color = palette.warning,
                        size = 12,
                        singleLine = true,
                    )
                }
            }
            if (s.showAccount) Account(s, wide)
            else if (s.connected && (s.credentialError || s.credentialStatus.startsWith("正在")))
                Label(
                    s.credentialStatus,
                    color = if (s.credentialError) palette.error else muted,
                    size = 12,
                    singleLine = true,
                )
            // 导航切换与内联搜索栏对齐在同一水平高度
            if (wide) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeTabs(s)
                    SearchBar(s, Modifier.weight(1f))
                }
            } else {
                ModeTabs(s)
                SearchBar(s, Modifier.fillMaxWidth())
            }
            StatusBar(s)
            // 主浏览工作区：宽屏折叠账户时自适应撑满剩余高度，避免固定高度留白
            if (wide) {
                val rowModifier =
                    if (compact) Modifier.fillMaxWidth().height(scrollPanelHeight)
                    else Modifier.fillMaxWidth().weight(1f)
                Row(
                    rowModifier,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (s.mode != "search") {
                        CollectionPanel(s, Modifier.weight(1f).fillMaxHeight())
                    }
                    SongPanel(
                        s,
                        Modifier.weight(if (s.mode == "search") 1f else 2.3f).fillMaxHeight(),
                        true,
                    )
                }
            } else {
                if (s.mode != "search") {
                    CollectionPanel(s, Modifier.fillMaxWidth().height(210.dp))
                }
                SongPanel(s, Modifier.fillMaxWidth().height(360.dp), false)
            }
        }
    }
}
