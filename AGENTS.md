# Qz-UILib-Platform 协作入口

## 规格与职责

开始工作先读 [Core 核心职责与交互规格](<../Qz-UILib-Core/specs/2026-10-07-uilib-core-contract-design.md>)，再读本仓[桌面宿主规格](<specs/host/2026-10-11-uilib-desktop-spec.md>)。Core 维护共同合同，本仓维护 CPU 字体 native、共享 GL、LWJGL binding、媒体实现和桌面宿主。正式规格是行为权威，源码与测试说明实现和证据。

跨仓只消费明确版本和 Java 目标的 Maven 坐标；禁止读取邻仓 src、classes 或构建目录。复用 Core 的 scene、Signal、PaintCommand、控件和字体合同。公共包名、资源与许可保持。具体游戏兼容由 Minecraft 仓装配，不反向依赖加载器。

## 工作方式

- 中文沟通、文档、注释与提交；技术标识符保留原文。
- 先查 Git 与受影响实现，保护协作者改动，只提交本仓任务文件。
- 开发模式 runtime-driven-development：由真实 Gradle 与原场景提供反馈，生产与测试组织变更运行完整 build。
- 同一 Java 目标共享同一生产编译输出；Java8 和 Java21 编译同源实现。
- 真实桌面、字体、媒体和生命周期验收保留；测试只消费独立 Core 验证与广场坐标，不进入正式发行。
- push、tag、release 与游戏 runClient/runServer 需要另行授权。

构建入口为 [Gradle 脚本](<build.gradle>)：传入 -PuilibVersion 与 -PuilibRepository 后执行 build；publishWorkspace 写入该本地 Maven 仓。任务细节见 [验证声明](<gradle/verification.gradle>)与[桌面声明](<gradle/desktop.gradle>)。临时记录与日志放在忽略的 .superpowers/platform-build/，正式行为只在 specs/ 维护。
