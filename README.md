# SPW Navidrome

本项目参考了 [FUFUTPR/spw-netease-plugin（SPW 网易云音乐插件）](https://github.com/FUFUTPR/spw-netease-plugin) 的宿主桥接与安装包组织思路，并针对 Navidrome 重新实现。感谢原作者的开源工作，详见 [第三方说明](THIRD_PARTY.md)。

在 Salt Player for Windows 中浏览 Navidrome 音乐库，使用 SPW 原生播放器播放歌曲、显示封面和歌词。

## 下载

- [GitHub Releases](https://github.com/Mystery00/spw-navidrome-plugin/releases)：下载 `.spmod` 安装包。
- [Steam 创意工坊](https://steamcommunity.com/app/3009140/workshop/)：条目待发布，届时补充直接订阅链接。

## 功能与要求

- 专辑、歌单浏览与歌曲搜索，选曲后提交到原生播放队列。
- 原生封面和歌词，内嵌页面跟随 SPW 深浅色主题。
- 保存服务器与账号，密码由 Windows DPAPI 加密，重新启用时自动恢复连接。
- 向 Navidrome 上报正在播放与有效收听记录，同一轮播放只计一次。

验证基线为 **Windows / Steam SPW 1.19.2**，需要可访问的 Navidrome 服务器。宿主接入通过 Metadata 与结构反射动态定位，不再按版本号精确锁定；其他版本仍需实际验证，结构不兼容时会拒绝接入。浏览列表未接入 SPW 原生曲库；不支持离线同步或服务器歌单编辑。

## 快速开始

1. 在 SPW「设置 → 创意工坊 → 模组管理」导入 `.spmod`，启用并授予「允许修改应用行为与界面」权限。
2. 从左侧「Navidrome」进入页面，也可打开模组「配置」。填写服务器根地址（如 `https://music.example.com/navidrome/`，不要追加 `/rest`）、用户名和密码，点击「连接并保存」。使用 HTTP 时需显式允许。
3. 浏览或搜索歌曲，选择后点击「播放选中」。暂停、切歌、拖动、封面和歌词均使用 SPW 原生播放页。

账户管理、缓存、升级和故障处理见 [使用说明](docs/usage.md)。普通使用无需安装 JDK 或 Python。

## 开发与反馈

- [开发与发布](docs/development.md)：构建、测试和 tag 自动发布。
- [架构说明](docs/architecture.md)：模块职责、宿主适配与数据边界。
- [文档规范](docs/documentation.md)：文档分类与维护约定。

请通过 [GitHub Issues](https://github.com/Mystery00/spw-navidrome-plugin/issues) 反馈问题，附插件版本、SPW 版本、安装渠道和脱敏错误信息。不要提交密码、凭据文件或带认证参数的 URL。

本项目采用 [MIT 许可证](LICENSE)，是非官方第三方插件，与 Salt Player、Navidrome 无隶属关系。
