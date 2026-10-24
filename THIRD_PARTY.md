# 第三方说明

本项目参考 FUFUTPR/spw-netease-plugin 提交 `3c200e477a9557de8c14b8dbf9acfb99939a4521` 的宿主桥接和安装包结构。实现已按 Navidrome、字符串 ID 和独立窗口重写，没有引入网易云登录、数据库同步或 WebView 模块。

参考项目许可证：MIT，Copyright (c) 2025 TPR (SPW NetEase Plugin)。相关许可证全文见 `licenses/netease-MIT.txt`。

SPW Workshop API（Apache-2.0）和 PF4J（Apache-2.0）仅在本地编译期引用宿主提供的类，不随插件分发。插件无第三方运行期依赖。测试使用 JDK 的 jdk.httpserver，仅存在于测试源码，不打入 spmod。构建使用 JDK 和 Python 标准库。

本插件是非官方第三方插件，与 Salt Player、Navidrome 均无隶属关系。
