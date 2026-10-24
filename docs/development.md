# 开发与发布

## 构建环境

Windows、JDK 25、Python 3.10 或更高。Gradle Wrapper 自动下载构建依赖，版本以构建脚本为准。插件信息位于 `project.json`；宿主、Kotlin、Compose 等运行时由 SPW 提供，不打入安装包。

### 独立构建

无需安装 SPW，使用公开 Workshop API 编译，与 GitHub Actions 的构建路径一致：

```powershell
python tools/build.py --standalone --jdk 'C:\Java\jdk-25'
```

脚本运行元数据校验、协议与流服务、请求隔离、真实 Windows DPAPI 合成凭据、页面状态、主题、权限及 Metadata 重命名回归，然后打包。API 由 JitPack 提供；编译通过不代表私有宿主 ABI 或实机播放已验证。

### 本机宿主构建

修改宿主适配或准备发布时，使用实际安装的受支持 SPW：

```powershell
.\build.ps1 -HostHome 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows' -JdkHome 'C:\Java\jdk-25'
```

也可通过 `SPW_HOME` 和 `JAVA_HOME` 指定路径。构建器提取宿主 API 作为编译输入，并检查动态播放绑定、主题、配置页面与侧栏挂载点；抽取物保留在被 Git 忽略的目录内，不得随源码分发。

产物为 `build/dist/plugin-vip.mystery0.spw.navidrome-<版本号>.spmod` 及内容相同的 `.zip`，构建器打印 SHA-256 并检查包内没有宿主、Compose、Kotlin、PF4J 或测试类。

## 按改动选择验证

### 代码格式

遵循根目录 `.editorconfig`：代码使用四空格缩进，JSON/YAML 使用两空格。展开密集的多语句代码、条件和异常处理；许可证与官方 Gradle Wrapper 保留上游格式。

Java 使用 google-java-format 1.37.0 的 `--aosp` 风格；纯格式整理保留导入、修饰符顺序及字符串，不混入清理。Kotlin 和 Gradle Kotlin DSL 使用 ktfmt 0.65 的 `--kotlinlang-style --do-not-remove-unused-imports`；Python 使用 Black 26.10.0 默认风格。PowerShell 展开参数、管道和控制块，整理后用 PowerShell Parser 检查语法。格式工具仅用于开发，不加入插件运行依赖。

### 验证范围

| 改动 | 验证入口 |
| --- | --- |
| 文档 | 相对链接、命令与现有脚本一致性；无需重新编译产品。 |
| 发布元数据或 tag 解析 | `python tools/test_build.py`。 |
| Java 协议、缓存与请求隔离 | 下方 Java 回归命令。 |
| 播放记录与有效收听 | Java 回归中的 `PlaybackReportingTest`；虚拟时间验证门槛和去重，模拟 HTTP 验证实际请求。宿主侧修改追加 ABI 检查。 |
| 凭据、原生状态、Compose 或完整安装包 | 独立构建；涉及宿主签名时追加本机宿主构建。 |
| 原生 UI、主题、播放桥 | 本机宿主构建及受支持 SPW 中的实际操作。 |
| 动态反射、Metadata 与混淆兼容 | `HostReflectionTest` 验证唯一性和缺失拒绝；`metadataRenamingTest` 改写合成夹具的 JVM 类与成员名并保留 Metadata。追加实际宿主的 `HostBindingsTest`、`hostUiAbiTest`，不能只测重命名夹具。 |
| 自动发布工作流 | 工作流语法检查、独立构建和产物清单核对；不为测试擅自推送发布 tag。 |

Java 回归可使用 JDK 21，不编译 Compose；额外传入 `-HostHome` 才会通过宿主 JNA 执行 DPAPI 测试：

```powershell
.\build.ps1 -TestOnly -JdkHome 'C:\Java\jdk-21'
```

本地自动测试使用模拟 HTTP 服务、临时缓存和合成凭据，不需要真实 Navidrome 账号。真实账号只由用户在 SPW 内输入，不读取已保存凭据开展测试。

重命名夹具工具位于 `tools/remap_fixture.py`，只处理测试编译结果，产物保存在 `build/fixtures/`。它不修改真实宿主，也不参与插件打包。工具自身测试可运行 `python tools/test_remap_fixture.py`，需要 `JAVA_HOME` 指向 JDK。

## 发布

先完成独立构建和本机宿主构建，再检查安装、启停、重启后的登录恢复、深浅色切换，以及 MP3/FLAC 播放、切歌、拖动、封面和歌词。UI 显示播放中不能作为实际出声的证据。不能完成的验收项应在发布说明中明确。

将包含工作流的待发布提交推送后，为该提交创建正式 tag：

```powershell
git tag v1.2.3
git push origin v1.2.3
```

`v1.2.3` 仅为格式示例，替换为本次实际版本。工作流仅接受 `v主版本.次版本.修订版本`，不接受前导零、预发布或构建后缀。安装包文件名和清单版本取自 tag；`project.json` 的版本仅用于未指定 tag 的本机构建。可用 `--tag v1.2.3` 在本地验证版本覆盖。

[发布工作流](../.github/workflows/release.yml) 在 Windows runner 构建并上传产物，再在独立发布任务中创建 Release 草稿、上传 `.spmod`、公开 Release。仅发布任务有仓库写权限，使用内置 `GITHUB_TOKEN`，无需配置个人访问令牌。

失败重跑可恢复草稿，已公开版本不会被覆盖；修复后发布新的 tag。GitHub Actions 不负责 Steam 创意工坊上传，后者使用相应 `.spmod` 及仓库内的工坊预览图（[16:9 横版](assets/workshop-preview-16x9.png)、[1:1 方版](assets/workshop-preview-1x1.png)）手动发布。
