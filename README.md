# SPW Navidrome

在 Salt Player for Windows 中浏览 Navidrome 专辑、搜索歌曲、查看歌单，并把列表交给 SPW 原生播放引擎。

**当前状态：代码已实现、构建与自动化回归通过，本机 Steam 1.18.5 已验证安装、启用、配置入口和窗口。真实 Navidrome 登录、浏览分页和歌单已验证，用户已确认“那一夜”及 MP3“白狐”、FLAC“舍得”正常出声，0.2.0 原生封面与滚动歌词已实机验证；原生暂停/继续、上下首及跳转进度已观察。三曲自然连播及缓存区间拖动等仍需完成验收。** 详见 [验证记录](docs/VALIDATION.md)。

## 环境与构建

- Windows；JDK 21 或更高；Python 3.10 或更高。
- 本构建的宿主适配仅支持 **Steam SPW 1.18.5**。本机宿主 runtime 为 Java 25.0.3，API 字节码为 Java 21。
- 无 Gradle、Kotlin 或 Compose 构建依赖；不把宿主字节码打入安装包。

```powershell
.\build.ps1 -HostHome 'H:\SteamLibrary\steamapps\common\Salt Player for Windows' -JdkHome 'C:\Users\myste\.jdks\temurin-21.0.12.1'
```

换机器请调整两个目录，也可设置 `SPW_HOME` 和 `JAVA_HOME`。构建脚本从指定宿主抽取编译用 API，编译产品及测试，运行回归，检查包内没有宿主类，最后产出：

`build/dist/plugin-io.github.mystery00.spw.navidrome-0.2.0.spmod`

仅运行不需要宿主的回归：

```powershell
.\build.ps1 -TestOnly -JdkHome 'C:\Users\myste\.jdks\temurin-21.0.12.1'
```

## 安装和使用

1. 在 SPW 的「设置 → 创意工坊 → 模组管理」导入 `build/dist` 中的 `.zip`。本机 1.18.5 导入器只接受 ZIP/JAR，构建会同时生成内容相同的 `.spmod` 和 `.zip`。也可正常退出 SPW 后执行 `./tools/install.ps1`，从 `.spmod` 安装到当前用户工坊目录。脚本发现正在运行或同 ID 旧版本时会拒绝覆盖。
2. 启用 `SPW Navidrome`，打开它的「配置 → 打开 Navidrome 音乐库」。1.18.5 没有官方 UI Hook，首版使用插件独立 Swing 窗口；没有独立音频播放器。
3. 输入服务器根地址、用户名和密码，点击「连接测试并登录」。例如 `https://music.example.com/navidrome/`，不要填写 `/rest`。子路径会保留。
4. 局域网 HTTP 必须明确勾选允许；HTTPS 正常验证系统信任链，不跳过证书检查。
5. 点击「专辑」或「歌单」，选择左侧条目；或输入关键词搜索歌曲。「继续加载」获取下一页，每页 50 项。
6. 双击歌曲或选择后点击播放按钮：整张专辑、歌单或已加载的搜索结果进入原生队列，从选定下标开始。暂停、继续、上一首、下一首、进度拖动使用 SPW 原生播放栏。
7. 选中歌曲显示可用封面。网络失败显示原因，可用「重试」重新加载。

密码只存于会话内存，连接时清空密码框；不保存到账户配置、日志或磁盘。关闭插件窗口保留连接；「退出 / 切换账户」、禁用插件或退出宿主清除凭据。重启后须重新登录。首版没有 Windows 凭据保险库持久化。

## 缓存与限制

- 只监听 `127.0.0.1` 的随机可用端口；URL 是随机路径，只映射已登记歌曲，不是开放代理。
- 仅使用 `format=raw`，不宣称实时转码支持 Range。
- 原样验证并转发 200/206/416；上游忽略 Range 时保持 200。服务器不支持 Range 时，拖动能力取决于宿主，不保证能跳到未下载位置。
- 边收边向播放器写入，不等待整首。完整接收且长度正确的响应区间才进入缓存；不是稀疏文件。相同 Range 命中缓存，其它区间直接向上游请求；不合并重叠区间。
- 音频缓存上限 512 MiB，按最近访问顺序淘汰，计入正在写入的保留空间。单个响应超过 16 MiB、长度未知时正常流播但不缓存。无主动预取，因此没有预取抢占当前播放的问题。
- 缓存位于 `%LOCALAPPDATA%\SPW-Navidrome\audio-cache`；仅当前插件会话使用，退出账户、禁用、重启会清理。窗口有清理入口。缓存键包含规范化服务器、用户名、字符串歌曲 ID、raw 格式、码率和区间。
- 不写 SPW 的 Track/Playlist 数据库、不改全局搜索或导航。没有收藏、scrobble、离线同步和服务器歌单编辑。
- 0.2.0 新增原生播放页封面与歌词接入。检测 `songLyrics` 能力后读取行级同步歌词并转换为 LRC；不支持扩展时回退标准 `getLyrics`，没有歌词时正常显示无歌词。不同语言选择服务器提供的首个同步版本，不合并翻译和逐字时间轴。已在真实 Navidrome 0.64.2 上观察到“玉生烟”“白狐”“舍得”的原生封面与歌词，并确认切歌后更新及“白狐”歌词随播放滚动。
- 原生元数据只按需加载当前曲，最多内存保留 24 首（每首封面最多 2 MiB、歌词最多 256 KiB），切换账户清空。图片解码、歌词解析在后台；最终应用在宿主 Main 线程校验媒体实例、加载任务和会话代次后执行。

## 升级、卸载和恢复

优先在 SPW 模组管理中导入新版，使用宿主升级流程。手动升级请先正常退出 SPW，将本插件目录移到 `workshop/plugins` 以外作为备份，再运行安装脚本；不要保留两个同 ID 版本在扫描目录中。不需要改 `enabled.txt`。

卸载使用宿主模组管理的删除入口。手动卸载须先退出 SPW，仅移除 `plugin-io.github.mystery00.spw.navidrome-*` 目录；按需删除上面的独立缓存目录，不要删除 SPW 用户数据目录。恢复时将备份插件目录放回并启用。

## 源码导航

- `net/SubsonicClient.java`：认证、地址、公开 API、错误和连接取消。
- `net/Json.java`：有大小/深度边界的 JSON 读取。
- `stream/LoopbackServer.java`：GET/HEAD/Range、错误载荷过滤、边收边传。
- `stream/AudioCache.java`：完整区间提交、容量、淘汰和清理。
- `host/Host1185.java`：所有宿主内部签名、流拦截器和队列接入；**升级宿主优先修改这里**。
- `host/NativePresentation.java`：原生封面所有权和歌词发射器适配；宿主升级同样需要重新核对。
- `AppSession.java`：账户代次、后台请求、宿主串行调用和生命周期屏障。
- `ui/LibraryWindow.java`：插件窗口、分页、重试、封面和选曲。
- `NavidromePlugin.java`：生命周期与配置按钮入口。

所有 Java 源码位于 `src/main/java/io/github/mystery00/spw/navidrome`。协议与设计证据见 [兼容性说明](docs/COMPATIBILITY.md)，许可证见 [第三方说明](THIRD_PARTY.md)。
