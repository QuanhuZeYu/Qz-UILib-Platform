package club.heiqi.uilib.ui.scene.integration;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import club.heiqi.uilib.host.client.gl.GlApi;
import club.heiqi.uilib.host.embedded.EmbeddedSceneServices;
import club.heiqi.uilib.host.font.IsolatedFontEngine;
import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.ui.scene.text.TextLayout;
import club.heiqi.uilib.ui.scene.text.TextStyle;
import club.heiqi.uilib.ui.scene.text.font.FontSource;
import club.heiqi.uilib.ui.scene.text.font.FontTextService;

/** 正式嵌入资源服务与真实CPU字体路径；任何GL调用都使无设备场景失败。 */
public final class EmbeddedFontSourcesScenario extends TestApplication {
    public static void main(String[] args) throws Exception { new EmbeddedFontSourcesScenario().run(); }
    @Override protected void runTests() throws Exception {
        scenario("旧open保持classpath来源且无新资源前提", () -> exercise(false));
        scenario("显式按代来源推进正式字体缓存与布局", () -> exercise(true));
    }

    private void exercise(boolean explicit) throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        GlApi gl = (GlApi) Proxy.newProxyInstance(GlApi.class.getClassLoader(), new Class<?>[] {GlApi.class},
                (proxy, method, args) -> { throw new AssertionError("无设备路径访问GL: " + method.getName()); });
        Path directory = Files.createTempDirectory("uilib-embedded-font-");
        Path configuration = directory.resolve("settings.yaml");
        List<Long> generations = new ArrayList<>();
        List<FontSource> supplied = new ArrayList<>();
        EmbeddedSceneServices.FontSources provider = generation -> {
            generations.add(generation);
            List<FontSource> sources = FontTextService.defaultSources(loader, generation);
            supplied.clear(); supplied.addAll(sources);
            return sources;
        };
        try (EmbeddedSceneServices services = explicit
                ? EmbeddedSceneServices.open(configuration, loader, IsolatedFontEngine.shared(), gl, null, provider)
                : EmbeddedSceneServices.open(configuration, loader, IsolatedFontEngine.shared(), gl, null)) {
            TextStyle style = new TextStyle("default", 16, -1);
            TextLayout first = services.fonts().layout("A中文", style, 500);
            require(!first.lines().get(0).glyphs().isEmpty(), "正式字体服务未完成真实整形");
            long before = services.fonts().fonts().generation();
            if (explicit) require(supplied.contains(first.lines().get(0).glyphs().get(0).source), "正式布局没有使用提供的来源");
            services.resourcesReloaded();
            TextLayout next = services.fonts().layout("A中文", style, 500);
            require(next.generation > before && next != first, "按代字体来源未使正式布局缓存失效");
            if (explicit) {
                require(generations.size() >= 2 && generations.get(generations.size() - 1) > generations.get(0),
                        "资源重载没有再次请求新代默认来源");
                require(supplied.contains(next.lines().get(0).glyphs().get(0).source), "新布局未使用新代来源");
            }
            require(!services.fonts().hasGlResources(), "CPU资源重载创建了GPU字体资源");
            System.out.println("EMBEDDED_FONT_SOURCES explicit=" + explicit + " realLayout=true generationChanged=true oldOpenPreserved=true noGL=true");
        } finally {
            Files.deleteIfExists(configuration); Files.deleteIfExists(directory);
        }
    }
}
