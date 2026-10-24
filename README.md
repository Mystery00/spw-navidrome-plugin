# SPW Navidrome

本项目参考了 [FUFUTPR/spw-netease-plugin（SPW 网易云音乐插件）](https://github.com/FUFUTPR/spw-netease-plugin) 的宿主桥接与安装包组织思路，并针对 Navidrome 重新实现。感谢原作者的开源工作；参考版本和许可证见 [第三方说明](THIRD_PARTY.md)。

在 Salt Player for Windows 中浏览 Navidrome 专辑、搜索歌曲、查看歌单，并把列表交给 SPW 原生播放引擎。

**当前版本：0.1.0，正在准备发布。仅适配 Steam SPW 1.19.2。** 已完成内嵌页面、加密凭据恢复、深浅色主题和高清封面的实机检查；完整验收范围与待验证项见 [验证记录](docs/VALIDATION.md)。本插件为非官方第三方插件。

## 环境与构建

- Windows；完整构建使用 JDK 25、Python 3.10 或更高。
- 当前宿主适配仅支持 **Steam SPW 1.19.2**，打开配置页需要授予 `class-transform` 权限。
- Gradle Wrapper 9.2.1、Kotlin/Compose 编译器 2.4.20、Compose desktop 1.12.2。首次构建需要下载依赖；宿主与 Compose 依赖仅用于编译，不打入安装包。

```powershell
.\build.ps1 -HostHome 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows' -JdkHome 'C:\Java\jdk-25'
```

换机器请调整目录，或设置 `SPW_HOME` 和 `JAVA_HOME`。产物为 `build/dist/plugin-vip.mystery0.spw.navidrome-0.1.0.spmod` 及同内容 `.zip`。脚本先运行 Java 回归和真实 DPAPI 合成凭据测试，再编译原生页面并运行状态集成测试，最后检查包内不含宿主、Compose 或测试类。

仅运行 Java 回归（可使用 JDK 21，不编译 Compose；额外传入 `-HostHome` 才运行依赖宿主 JNA 的真实 DPAPI 测试）：

```powershell
.\build.ps1 -TestOnly -JdkHome 'C:\Java\jdk-21'
```

当前适配的官方曲库 API 尚无外部音乐源注册或写入接口，因此本页列表为临时浏览入口；歌曲交给 SPW 原生队列播放。界面跟随 SPW 深浅色主题，账户管理与浏览分开，为原生播放栏留出空间。

## 安装和使用

官方支持导入 `.spmod`、`.jar` 和 `.zip`；本项目同时生成 `.spmod` 和内容相同的 `.zip`。下文采用本机已验证的 ZIP 导入方式，Steam 发布器则要求 `.spmod`。

1. 在 SPW「设置 → 创意工坊 → 模组管理」导入 `plugin-vip.mystery0.spw.navidrome-0.1.0.zip`，启用时勾选「允许修改应用行为与界面」并确认。升级走宿主导入流程，不手动覆盖正在运行的插件。普通使用无需安装 JDK 或 Python。
2. 打开本模组的「配置」，在 SPW 内嵌页面输入服务器根地址、用户名和密码。例如 `https://music.example.com/navidrome/`，不要填写 `/rest`。不再打开独立 Swing 窗口。连接成功后收起表单，右上角「账户管理」可再次展开。
3. HTTP 需显式允许；HTTPS 使用正常证书验证。点击「连接并保存」。
4. 通过「专辑」「歌单」选择集合，或搜索歌曲；专辑和搜索结果每页 50 项，可点击「加载更多」或「加载更多 歌曲」追加结果。
5. 选择歌曲后点击「播放选中」。整个当前歌曲列表进入 SPW 原生队列，暂停、上下首、拖动、封面和歌词使用原生播放页。
6. 在账户管理中点击「退出并忘记」，再点击「确认忘记」，会断开会话并删除本机保存的登录信息。网络错误显示在页面，可以再次点击对应浏览或搜索操作重试。

服务器、用户名和 HTTP 选择与加密密码存放在宿主为插件提供的数据目录 `workshop/data/vip.mystery0.spw.navidrome/credentials.json`。密码使用 **Windows DPAPI 当前用户**保护，不写入普通配置字段、日志或命令行。只有连接成功后才保存；加密失败会提示并保留旧文件，不降级成明文。禁用或关闭宿主释放内存会话但保留加密文件，下次启用自动恢复连接。移动到另一个 Windows 用户后不可解密时，需要重新登录。

## 缓存与限制

- 只申请 `class-transform`，用于在本模组配置入口挂载原生页面；原生播放桥同时依赖版本专属的宿主适配。未授予权限时不启动会话或自动登录，可在标准配置页查看授权说明；停用后重新启用可调整勾选。不申请未使用的曲库读写或快捷键权限。
- 只监听 `127.0.0.1` 的随机可用端口；URL 是随机路径，只映射已登记歌曲，不是开放代理。
- 仅使用 `format=raw`，不宣称实时转码支持 Range。
- 原样验证并转发 200/206/416；上游忽略 Range 时保持 200。服务器不支持 Range 时，拖动能力取决于宿主，不保证能跳到未下载位置。
- 边收边向播放器写入，不等待整首。完整接收且长度正确的响应区间才进入缓存；不是稀疏文件。相同 Range 命中缓存，其它区间直接向上游请求；不合并重叠区间。
- 音频缓存上限 512 MiB，按最近访问顺序淘汰，计入正在写入的保留空间。单个响应超过 16 MiB、长度未知时正常流播但不缓存。无主动预取，因此没有预取抢占当前播放的问题。
- 缓存位于 `%LOCALAPPDATA%\SPW-Navidrome\audio-cache`；仅当前插件会话使用，退出账户、禁用、重启会清理。内嵌页面有清理入口。缓存键包含规范化服务器、用户名、字符串歌曲 ID、raw 格式、码率和区间。
- 不写 SPW 的 Track/Playlist 数据库、不改全局搜索或导航。没有收藏、scrobble、离线同步和服务器歌单编辑。
- 0.2.0 新增原生播放页封面与歌词接入。检测 `songLyrics` 能力后读取行级同步歌词并转换为 LRC；不支持扩展时回退标准 `getLyrics`，没有歌词时正常显示无歌词。不同语言选择服务器提供的首个同步版本，不合并翻译和逐字时间轴。已在真实 Navidrome 0.64.2 上观察到“玉生烟”“白狐”“舍得”的原生封面与歌词，并确认切歌后更新及“白狐”歌词随播放滚动。
- 原生元数据只按需加载当前曲，最多内存保留 24 首（每首封面最多 4 MiB、歌词最多 256 KiB），切换账户清空。图片解码、歌词解析在后台；最终应用在宿主 Main 线程校验媒体实例、加载任务和会话代次后执行。
- 封面请求尺寸为 1024 像素，原生解码目标为 1024×1024；清晰度受服务器原始封面限制。

## 升级、卸载和恢复

发布准备期间，插件 ID 从 `io.github.mystery00.spw.navidrome` 改为 `vip.mystery0.spw.navidrome`。SPW 会将新 ID 识别为另一个插件，而非旧 ID 的直接更新。使用过旧包时，请先在模组管理中停用旧插件，再导入并启用新包，重新登录。不要同时启用两个 ID；它们使用同一个音频缓存目录。旧 ID 的凭据目录不会自动迁移或删除。

优先在 SPW 模组管理中导入新版，使用宿主升级流程。手动升级请先正常退出 SPW，将本插件目录移到 `workshop/plugins` 以外作为备份，再运行安装脚本；不要保留两个同 ID 版本在扫描目录中。不需要改 `enabled.txt`。

卸载使用宿主模组管理的删除入口。手动卸载须先退出 SPW，仅移除 `plugin-vip.mystery0.spw.navidrome-*` 目录；按需删除上面的独立缓存目录，不要删除 SPW 用户数据目录。恢复时将备份插件目录放回并启用。

## 源码导航

源码仓库：[Mystery00/spw-navidrome-plugin](https://github.com/Mystery00/spw-navidrome-plugin)。反馈问题时请附插件版本、SPW 版本、安装渠道和脱敏错误信息，不要提交密码、凭据文件或带认证参数的 URL。

- `net/SubsonicClient.java`：认证、地址、公开 API、错误和连接取消。
- `net/Json.java`：有大小/深度边界的 JSON 读取。
- `stream/LoopbackServer.java`：GET/HEAD/Range、错误载荷过滤、边收边传。
- `stream/AudioCache.java`：完整区间提交、容量、淘汰和清理。
- `host/Host1192.java`：所有宿主内部签名、流拦截器和队列接入；**升级宿主优先修改这里**。
- `host/NativePresentation.java`：原生封面所有权和歌词发射器适配；宿主升级同样需要重新核对。
- `AppSession.java`：账户代次、后台请求、宿主串行调用和生命周期屏障。
- `ui/NativeUiHost.kt`、`ui/NativeState.kt`：原生页面、登录恢复、分页及请求代次隔离。
- `credentials/`：Windows DPAPI 与原子凭据存储。
- `NavidromePlugin.java`：生命周期与原生页面挂载。

Kotlin 界面位于 `src/main/kotlin`；Java 源码位于 `src/main/java/vip/mystery0/spw/navidrome`。协议与设计证据见 [兼容性说明](docs/COMPATIBILITY.md)，许可证见 [第三方说明](THIRD_PARTY.md)。
