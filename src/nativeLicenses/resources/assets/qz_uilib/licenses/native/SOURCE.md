# 字体与桌面原生运行库

`font-adapter` 的 Java21 与 `java8` 产物，以及 `desktop` JAR，各自携带本目录。桌面完整分发还在 `licenses/native/` 提供相同材料；字体与桌面的许可交付不依赖 media-adapter 同时存在。

| 组件 | 实际输入与版本依据 | 许可与来源 |
| --- | --- | --- |
| LWJGL | `org.lwjgl:*:3.4.2`；字体私有运行时含 core、FreeType、HarfBuzz 绑定及 core/FreeType native；desktop 直接合入当前平台的 core、FreeType、HarfBuzz、OpenGL、GLFW 绑定和相应 native | BSD-3-Clause，完整版权、条件和免责声明见 [LWJGL-LICENSE.md](<LWJGL-LICENSE.md>)；[LWJGL 3.4.2 源码](<https://github.com/LWJGL/lwjgl3/tree/3.4.2>) |
| FreeType | 字体 runtime 为 **2.14.3**：LWJGL 3.4.2 sources JAR 的 `FREETYPE_MAJOR/MINOR/PATCH` 与 Windows x64 native 的 `FT_Library_Version` 均确认该版本 | 选择 FTL，完整文本见 [FreeType-FTL.txt](<FreeType-FTL.txt>)；[FreeType 2.14.3 源码](<https://github.com/freetype/freetype/tree/VER-2-14-3>) |
| HarfBuzz | **14.2.1**：LWJGL 3.4.2 sources JAR 的 `HB_VERSION_STRING` 与同一 FreeType DLL 导出的 `hb_version_string` 一致；字体实际通过该 DLL 使用 HarfBuzz | Old MIT，完整版权、授权及免责声明见 [HarfBuzz-COPYING.txt](<HarfBuzz-COPYING.txt>)；[HarfBuzz 14.2.1 源码](<https://github.com/harfbuzz/harfbuzz/tree/14.2.1>) |

`HarfBuzz-COPYING.txt` 原样提取自本地 [Pillow 12.3.0 wheel](<https://pypi.org/project/pillow/12.3.0/>) 随附的 `pillow-12.3.0.dist-info/licenses/LICENSE` 中 `===== harfbuzz-14.2.1 =====` 分段。独立缓存 wheel 的同段正文一致，其 `sboms/pillow-12.3.0.cdx.json` 也列明 HarfBuzz 14.2.1 和上游发行来源。该正文适用于上述字体 native 中的 HarfBuzz 14.2.1。

[HarfBuzz-ADDITIONAL-NOTICES.txt](<HarfBuzz-ADDITIONAL-NOTICES.txt>) 补充保留 `hb-ucd.cc` 的 ISC 版权许可、`hb-unicode-emoji-table.hh` 的 Unicode 通知与 Unicode License V3 全文。文件级通知原样取自本地 JDK 25.0.3+9 的 `legal/java.desktop/harfbuzz.md` 对应条目（该材料标注 HarfBuzz 12.3.2）；Unicode License V3 原样取自同一 JDK 的 `legal/java.base/unicode.md`。这些附加通知按其各自文件和数据范围保留，HarfBuzz 14.2.1 主许可使用前述 Pillow 分段。

本软件部分基于 FreeType Team 的工作，使用 FreeType；FreeType 的版权归其各自作者。This software is based in part on the work of the FreeType Team. [FreeType Project](<https://www.freetype.org/>)。

字体包内各平台 FreeType native 的 `.git` 元数据均指向 LWJGL native 构建提交 `0a0221a1347e2f1e07c395263540026e9a0aa7c7`；Windows x64 版本已通过原生导出函数确认。字体 runtime 与 FFmpeg 内的 FreeType **2.13.2** 分属不同输入，后者的来源与重建说明随 media-adapter 分发。

原始依赖 JAR 可按 GAV 从 [Maven Central](<https://repo.maven.apache.org/maven2/org/lwjgl/>) 获取。字体嵌套 JAR 保留原文件名与 JNI 名，装配仅剔除 `META-INF/versions/` 覆盖；`assets/qz_uilib/runtime/font/index.txt` 保留原文件名索引。上游 native 未经本仓修改。

同版本 `font-adapter:sources`、`desktop:sources` 随其 Maven publication 提供本仓实现、装配脚本及本目录；第三方 native 的对应源见上表。本仓共享的 LWJGL 与 FTL 全文由此目录维护，media-adapter 继续在原 `assets/qz_uilib/licenses/media/` 路径提供这两份全文。
