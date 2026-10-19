# 第三方材料

Qz-UILib Platform 自有代码采用 MIT，随各正式产物根目录的 `LICENSE` 分发。Platform 通过 Maven 坐标消费 Core；Core 的字体、内容库与各自许可随对应 Core 产物分发。

| 正式产物 | 实际第三方材料位置 |
| --- | --- |
| `font-adapter`（Java21 与 `java8`） | JAR 自身的 `assets/qz_uilib/licenses/native/` 提供 LWJGL 3.4.2 BSD、FreeType FTL、HarfBuzz 14.2.1 Old MIT 的完整版权许可，以及同目录 `SOURCE.md` 中的字体 runtime 来源及 credits。字体 runtime 为 FreeType 2.14.3 / HarfBuzz 14.2.1，HarfBuzz 正文为 `HarfBuzz-COPYING.txt`，文件级 ISC、Unicode 通知及 Unicode License V3 全文另见 `HarfBuzz-ADDITIONAL-NOTICES.txt`。 |
| `desktop` JAR | 自身的 `assets/qz_uilib/licenses/native/` 提供直接合入的 LWJGL 与字体 native 材料，同上；Core 与媒体材料随 POM 声明的各自依赖 JAR 提供。 |
| `media-adapter`（Java21 与 `java8`） | JAR 自身的 `assets/qz_uilib/licenses/media/` 提供 FFmpeg、JavaCPP、LWJGL、OpenAL Soft、OpenH264、OpenSSL、FreeType、SVT-AV1、libvpx、libwebp、zlib 等实际随附组件的完整文本；`license-manifest.json` 保持原清单。精确版本、对应源码、重建和动态库替换方法见该目录的 `SOURCE.md` 与 `sources/ffmpeg-cppbuild.sh`。 |
| `desktop-dist` ZIP | 解压后根目录有本告知与 MIT 许可证；`licenses/native/`、`licenses/media/` 提供所属许可与来源材料，`lib/` 内各 JAR 也保留自身许可。Core 材料在 `lib/core-*.jar` 内。 |
| `gl-renderer`、`lwjgl2-adapter`、`lwjgl3-adapter` | 产物保留自有 MIT 与本告知；POM 声明的外部 Core、GL 或 LWJGL 绑定由相应依赖提供。 |

本软件部分基于 FreeType Team 的工作，使用 FreeType；FreeType 的版权归其各自作者。This software is based in part on the work of the FreeType Team. [FreeType Project](<https://www.freetype.org/>)。字体 runtime 的 FreeType 2.14.3 与 FFmpeg 内的 FreeType 2.13.2 分别在所属来源说明中列明。

仓库内的共享 native 全文位于 [共享许可目录](<src/nativeLicenses/resources/assets/qz_uilib/licenses/native/>)；媒体专用材料位于 [媒体许可目录](<src/mediaAdapter/resources/assets/qz_uilib/licenses/media/>)。media-adapter 装配时仍将共享 LWJGL/FTL 全文写回既有媒体资源路径。

同版本 Maven publication 的 `sources` classifier 随附本仓对应实现、装配脚本及所属许可材料；POM 将 desktop 与 Core、font-adapter、media-adapter 关联。上游第三方完整源码使用各 `SOURCE.md` 列明的精确版本入口。
