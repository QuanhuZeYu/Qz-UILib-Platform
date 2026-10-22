# Qz-UILib Platform

UILib 的字体、GL、LWJGL、媒体与桌面适配。游戏用户请使用 [Minecraft 发行包](https://github.com/QuanhuZeYu/Qz-UILib/releases/tag/5.0.0-beta3)。

## 开发接入

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("asia.quanhuzeyu.uilib:font-adapter:5.0.0-beta3")
    implementation("asia.quanhuzeyu.uilib:gl-renderer:5.0.0-beta3")
}
```

按宿主选用 `lwjgl2-adapter` 或 `lwjgl3-adapter`，媒体功能使用 `media-adapter`，桌面宿主使用 `desktop`；均使用同一 group 与版本。

默认产物需要 Java 21。Java 8 接入须选用 `java8` classifier、关闭其传递依赖，并显式添加同版 `core:java8`；`desktop` 仅支持 Java 21。

[下载与更新说明](https://github.com/QuanhuZeYu/Qz-UILib-Platform/releases/tag/5.0.0-beta3) · [桌面接入](specs/host/2026-10-11-uilib-desktop-spec.md)

[MIT](LICENSE) · [第三方许可](THIRD-PARTY-NOTICES.md)
