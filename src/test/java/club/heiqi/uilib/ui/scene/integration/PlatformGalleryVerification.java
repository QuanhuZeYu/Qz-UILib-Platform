package club.heiqi.uilib.ui.scene.integration;

import club.heiqi.uilib.testing.TestApplication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 平台所属的真实字体、软件图像和会话检查；不创建窗口、GPU 或音频设备。 */
public final class PlatformGalleryVerification extends TestApplication {
    private Throwable failure;
    @Override protected void runTests() throws Exception {
        failure = null;
        String fonts = "src/core/resources/assets/qz_uilib/fonts";
        application("共享广场与真实字体", GalleryScenario.class);
        application("邮箱状态与呈现生命周期", MailboxScenario.class);
        application("字体投影采样", FontPlanSamplingScenario.class, fonts + "/unifont/unifont-16.0.04.otf");
        application("布局与真实内容", LayoutFoundationScenario.class);
        application("布局增量", IncrementalMeasurementScenario.class);
        application("所属层及宿主语义", LayerInputScenario.class);
        application("多语言编辑", BidiEditingScenario.class);
        application("单词编辑", WordEditingScenario.class);
        application("有限列表", LargeListScopeScenario.class);
        application("图像服务", ImageServiceScenario.class);
        application("图像owner关闭故障", club.heiqi.uilib.host.client.gl.ImageCloseScenario.class);
        application("富文本", RichTextScenario.class);
        application("Markdown阅读", MarkdownReadingScenario.class);
        application("Markdown表格与HTML", MarkdownTableHtmlScenario.class);
        application("内联盒", InlineBoxScenario.class);
        application("文档场景", DocumentSceneScenario.class);
        application("数学字体", MathFontScenario.class);
        application("数学布局", MathLayoutScenario.class);
        application("数学场景", MathSceneScenario.class);
        application("数学数组", MathArrayScenario.class);
        application("数学饰件", MathDecorationScenario.class);
        application("原生字体", NativeFontScenario.class, fonts + "/noto");
        application("原生字形像素", NativeFontVisualScenario.class, fonts + "/noto", "reports/native-font");
        application("字形样式与缓存", GlyphRasterStyleScenario.class, fonts, "reports/glyph-style");
        application("有序批次", OrderedBatchScenario.class, "reports/ordered-batch");
        application("数学文本字体", MathTextFontScenario.class, "reports/math-fonts");
        application("字体延迟状态", FontDeferredStateScenario.class);
        application("呈现代际", PresentationEpochScenario.class);
        application("媒体真实本地HTTP解码", "club.heiqi.uilib.testing.media.MediaNetworkScenario");
        application("媒体地址解析", "club.heiqi.uilib.host.media.bilibili.BilibiliResolverScenario");
        if (failure != null) rethrow(failure);
    }
    private void application(String name, Class<? extends TestApplication> type, String... args) {
        application(name, type.getName(), args);
    }
    private void application(String name, String type, String... args) {
        if (!selected(type)) return;
        try {
            scenario(name, () -> {
                try { ((TestApplication) Class.forName(type).getConstructor().newInstance()).run(args); }
                catch (java.lang.reflect.InvocationTargetException error) { rethrow(error.getCause()); }
            });
        } catch (Throwable error) {
            if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
        }
    }
    private boolean selected(String type) {
        String[] requested = arguments();
        if (requested.length == 0) return true;
        String id = type.substring(type.lastIndexOf('.') + 1);
        if (id.endsWith("Scenario")) id = id.substring(0, id.length() - "Scenario".length());
        for (String candidate : requested) if (candidate.equalsIgnoreCase(id)) return true;
        return false;
    }
    private static void rethrow(Throwable error) throws Exception {
        if (error instanceof Error) throw (Error) error;
        if (error instanceof Exception) throw (Exception) error;
        throw new RuntimeException(error);
    }
    public static void main(String[] args) throws Exception {
        StringBuilder results = new StringBuilder();
        Path report = Paths.get(System.getProperty("uilib.verification.report", "reports/platform"));
        Files.createDirectories(report);
        try {
            new PlatformGalleryVerification().runReporting(result -> results.append(result.failure == null ? "PASS " : "FAIL ")
                    .append(result.name).append(" nanos=").append(result.elapsedNanos).append((char) 10), args);
        } finally { Files.write(report.resolve("platform.txt"), results.toString().getBytes(StandardCharsets.UTF_8)); }
    }
}
