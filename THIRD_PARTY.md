# 第三方说明

本项目参考 FUFUTPR/spw-netease-plugin 提交 `3c200e477a9557de8c14b8dbf9acfb99939a4521` 的宿主桥接和安装包结构。实现已按 Navidrome、字符串 ID 和原生 Compose 页面重写，没有引入网易云登录、数据库同步或 WebView 模块。

参考项目许可证：MIT，Copyright (c) 2025 TPR (SPW NetEase Plugin)。相关许可证全文见 `licenses/netease-MIT.txt`。

SPW Workshop API（Apache-2.0）、PF4J（Apache-2.0）、Kotlin 与 Compose（Apache-2.0）均由宿主提供，编译引用但不打入插件包。Windows 凭据保护通过反射复用宿主 JNA 的 Crypt32Util，不随插件打包 JNA。测试使用 JDK 的 jdk.httpserver，仅存在于测试源码，不打入 spmod。

构建使用 JDK、Python、Kotlin/Compose 编译器与 Gradle。仓库包含 Gradle Wrapper 9.2.1（Apache-2.0）；许可证见 `licenses/Apache-2.0.txt`，Gradle 项目为 https://github.com/gradle/gradle 。

本插件是非官方第三方插件，与 Salt Player、Navidrome 均无隶属关系。
