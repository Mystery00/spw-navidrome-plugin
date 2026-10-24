# SPW Navidrome 实施计划

> 历史设计与实施计划，仅供追溯；当前行为以代码和开发指南为准，不作为新的执行指令。

执行方式：当前会话按 executing-plans 实现，核心行为采用测试驱动；完成后独立审查。用户已授权常规选型与连续执行。

目标：配置服务器、浏览专辑、搜索歌曲、查看歌单，并提交真实媒体队列给 SPW 原生引擎。

架构：纯 Java 21；Workshop 配置按钮打开 Swing 插件窗口；HttpURLConnection 请求公开 Subsonic API；ServerSocket 只监听 IPv4 loopback；独立 HostAdapter 负责 1.18.5 反射。无运行期第三方依赖，不改宿主数据库。

原始需求：用户附件「已粘贴的文本.txt」（2026-10-24）。本计划不收紧附件验收范围。

## 当时的环境约束

- 实际安装 H:/SteamLibrary/steamapps/common/Salt Player for Windows；Steam build 25418401，cfg 版本 1.18.5。
- runtime/release：Java 25.0.3；有 java.desktop，无 java.net.http / jdk.httpserver。
- Workshop API 类文件 major 65，允许 JDK 21 编译；本机 JDK 21.0.12.1。
- 参考仓库提交 3c200e477a9557de8c14b8dbf9acfb99939a4521；官方 API 提交 cbf20375b95b42d95c3fb16f1ceb53f4befc205c。
- 本机 1.18.5 没有官方 Hook API，故采用自身桌面窗口；不假称 Compose 页面已兼容。

## 任务与验收

- [ ] 协议：`net/SubsonicClient.java`、`net/Json.java`、`model/Models.java`。先测试 token 已知向量、中文参数、子路径、字符串 ID、HTTP 200 协议失败，再实现元数据与音频鉴权。请求超时、禁重定向，异常不带认证 URL。
- [ ] 流服务：`stream/ByteRange.java`、`stream/AudioCache.java`、`stream/LoopbackServer.java`。先测试 GET/HEAD/206/416、上游忽略 Range、错误载荷、截断下载、区间与身份隔离，再实现边传边存和有界 LRU。仅完整成功响应可入缓存；部分请求不伪造完整文件。
- [ ] 宿主：`host/HostAdapter.java`、`host/Host1185.java`。核对本机 javap 签名、构造媒体、List+index 队列、prepare/play，测试仅认领自身 URL、保留他人拦截器与卸载恢复。真实声音、三曲和拖动必须在宿主另行验收。
- [ ] 会话与 UI：`AppSession.java`、`ui/LibraryWindow.java`、`NavidromePlugin.java`。后台工作、EDT 更新、会话代次、密码不落盘、取消连接、重试与分页、封面、清缓存。登录后先测试单曲，再扩展三曲与完整路径。
- [ ] 打包与交付：`build.ps1`、`tools/build.py`、README、兼容性及验证记录。抽取编译期 API，不发布宿主代码。构建并安装 spmod；启动宿主前检查进程，不结束用户进程。缺少真实账号时保留明确未验证项。

## 审查重点

2026-10-24 执行记录：上述五项实现和打包已完成，自动化回归通过，独立审查的三项问题已修复。本机安装、更新、启用和页面入口已验证。任务复选框保留未勾选，因为其中包含尚缺真实服务器的播放、三曲、拖动及生命周期验收；不将整体实施标记为验收完成。

1. 同一歌曲 ID 跨服务器/用户不得命中另一身份缓存；测试缓存键与旧会话 URL 失效。
2. 上游忽略 Range 不能生成虚假的 206；测试 body 与响应头一致。
3. 音频端点返回 XML/JSON 或截断数据，不得完成缓存；测试真实 socket 请求。
4. 禁用/切换期间迟到响应不得更新 UI 或重新提交播放；测试会话代次。
5. 宿主代理只认领本实例登记地址，卸载仅移除自身对象；独立审查并真实宿主核对。
