package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.geometry;

import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.*;
import club.heiqi.uilib.ui.scene.edit.SceneCompositionTarget;
import club.heiqi.uilib.ui.scene.host.ScenePage;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.layout.SceneGeometryView;
import club.heiqi.uilib.ui.scene.layout.SceneLayouts;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/** 文件定位、根范围旧目标与有界视口挂载的正式组件回归；只借用宿主已安装回调。 */
public final class DesktopFilePickerVerification extends TestApplication {
    private Path output, directory;
    public static void main(String[] args) throws Exception { new DesktopFilePickerVerification().run(args); }
    @Override protected void setup() throws Exception {
        output = Paths.get(arguments().length == 0 ? "file-picker-report" : arguments()[0]);
        Files.createDirectories(output);
        directory = Files.createTempDirectory("uilib 文件定位 中文 ");
        for (int i = 0; i < 150; i++) Files.createFile(directory.resolve(String.format("条目 %03d.ViDeO", i)));
    }
    @Override protected void teardown() throws Exception {
        if (directory == null) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
        }
    }
    @Override protected void runTests() throws Exception {
        scenario("后页定位显露、根范围清目标、明确搜索和宽窄动态布局", this::picker);
        System.out.println("FILE_PICKER_DESKTOP realDirectory=true installedCallbacks=true locatedReveal=true rootTargetCleared=true virtualRows=true responsive=true");
    }
    private void picker() throws Exception {
        Fixture page = new Fixture();
        try (DesktopWindow window = DesktopWindow.openWithServices(output.resolve("picker.yaml"), true, services -> page)) {
            glfwSetWindowSize(window.handle(), 1100, 820); DesktopTestFrames.settle(window);
            DesktopTestInput input = new DesktopTestInput(window);
            input.focus.invoke(window.handle(), true); window.frame();
            try (DesktopTestInput.ScriptedCursor ignored = input.scriptedCursor()) {
                input.click("打开文件选择器");
                await(window, () -> text(window, "条目 000.ViDeO"), "目录首批没有到达");
                DesktopTestFrames.finitePresentations(window);
                SceneNode list = geometry(window).ordered().stream().map(e -> e.node)
                        .filter(n -> n.control() != null && n.control().inspectionLogicalItemCount(n) == 64).findFirst()
                        .orElseThrow(() -> new AssertionError("文件列表没有复用共享有限集合"));
                require(list.control().inspectionInstantiatedItemCount(list) < 64, "目录页仍一次挂载所有行");
                SceneNode pathField = fields(window).get(0);
                Path target = directory.resolve("条目 149.ViDeO");
                type(window, input, pathField, target.toString()); key(window, input, GLFW_KEY_ENTER, 0);
                await(window, () -> text(window, "第 3 页") && text(window, target.getFileName().toString()), "直接定位没有落在自然第三页");
                SceneNode targetNode = geometry(window).ordered().stream().map(e -> e.node)
                        .filter(n -> n.clickAction() != null && target.getFileName().toString().equals(n.text())).findFirst().get();
                SceneGeometryView.Entry targetEntry = geometry(window, targetNode);
                require(DesktopTestInput.hit(targetEntry).height == targetEntry.box.height, "直接定位未显露屏外文件");
                SceneNode[] focus = {null}; window.domain.execute(() -> focus[0] = window.page.runtime().focusRouter().focused().get());
                require(focus[0] == pathField, "定位显露抢走路径字段焦点");
                input.click("上一页"); await(window, () -> text(window, "第 2 页") && text(window, "条目 064.ViDeO"), "定位后上一页边界错误");
                input.click("上一页"); await(window, () -> text(window, "第 1 页") && text(window, "条目 000.ViDeO"), "定位后第一页边界错误");
                type(window, input, pathField, target.toString()); key(window, input, GLFW_KEY_ENTER, 0);
                await(window, () -> text(window, "第 3 页") && text(window, "条目 149.ViDeO"), "再次定位没有显露");
                input.click("根目录"); await(window, () -> geometry(window).ordered().stream().anyMatch(e -> e.node.text().contains("根目录 / 盘符")), "不能进入根范围");
                require(editText(window, pathField).isEmpty(), "根范围保留旧文件路径");
                input.click("选择文件"); window.frame();
                require(page.selected == null && geometry(window).activeModal() != null, "根范围确认提交了旧文件");
                type(window, input, pathField, directory.toString()); key(window, input, GLFW_KEY_ENTER, 0);
                await(window, () -> text(window, "条目 000.ViDeO"), "不能从根范围导航回目录");
                SceneNode search = fields(window).get(1);
                type(window, input, search, "149"); input.click("搜索");
                await(window, () -> text(window, "条目 149.ViDeO") && !text(window, "条目 000.ViDeO"), "明确搜索动作没有覆盖后页");
                input.click("清空搜索"); await(window, () -> text(window, "条目 000.ViDeO"), "清除搜索没有恢复目录");
                input.click("条目 000.ViDeO"); key(window, input, GLFW_KEY_END, 0);
                SceneNode[] end = {null}; window.domain.execute(() -> end[0] = window.page.runtime().focusRouter().focused().get());
                require(end[0] != null && "条目 063.ViDeO".equals(end[0].text()) && DesktopTestInput.hit(geometry(window, end[0])).height > 0,
                        "End没有准备并显露末行");
                key(window, input, GLFW_KEY_HOME, 0); key(window, input, GLFW_KEY_PAGE_DOWN, 0);
                window.domain.execute(() -> end[0] = window.page.runtime().focusRouter().focused().get());
                require(end[0] != null && !"条目 000.ViDeO".equals(end[0].text()) && DesktopTestInput.hit(geometry(window, end[0])).height > 0,
                        "PageDown没有按视口移动并显露");
                key(window, input, GLFW_KEY_HOME, 0);
                for (boolean dark : new boolean[] {false, true}) {
                    window.domain.execute(() -> page.theme.set(dark ? SceneTheme.dark() : SceneTheme.light()));
                    for (int[] size : new int[][] {{1100,820},{560,620},{800,420}}) {
                        glfwSetWindowSize(window.handle(), size[0], size[1]); DesktopTestFrames.finitePresentations(window);
                        DesktopGalleryEvidence.saveGeometry(window, output.resolve((dark ? "dark-" : "light-") + size[0] + "x" + size[1] + ".geometry.json"));
                        for (String name : new String[] {"搜索", "取消", "选择文件"}) require(visible(window, name), "关键动作不可达：" + name + " " + size[0] + "x" + size[1]);
                        require(geometry(window, list).box.height > 0, "矮窗列表没有可用空间：" + size[0] + "x" + size[1]);
                        // 先清成目录文本，防旧选择让无响应的元数据点击假通过。
                        type(window, input, pathField, directory.toString());
                        // 元数据可能位于右侧或第二行；点击它必须沿整行唯一动作根选中。
                        SceneGeometryView.Entry metadata = geometry(window).ordered().stream()
                                .filter(e -> e.node.text().startsWith("VIDEO 文件") && DesktopTestInput.hit(e).height > 0 && DesktopTestInput.hit(e).width > 0 && DesktopTestInput.hit(e).height == e.box.height)
                                .findFirst().orElseThrow(() -> new AssertionError("视口没有完整元数据行"));
                        input.click(metadata);
                        require(editText(window, pathField).contains(".ViDeO"), "元数据区域没有激活文件行");
                        javax.imageio.ImageIO.write(DesktopTestReadback.readFront(window), "png",
                                output.resolve((dark ? "dark-" : "light-") + size[0] + "x" + size[1] + ".png").toFile());
                    }
                    window.domain.execute(() -> {
                        SceneTheme current = page.theme.get();
                        page.theme.set(current.withTextStyle(current.textStyle.withEm(22)));
                    });
                    glfwSetWindowSize(window.handle(), 900, 760); DesktopTestFrames.finitePresentations(window);
                    for (String name : new String[] {"搜索", "取消", "选择文件"}) require(visible(window, name), "大字号关键动作不可达：" + name);
                    require(geometry(window, list).box.height > 0, "大字号列表没有可用空间");
                }
                input.click("取消"); require(page.selected == null && geometry(window).activeModal() == null, "取消仍提交或拦截输入");
            }
        }
    }
    private static boolean visible(DesktopWindow window, String name) {
        return geometry(window).ordered().stream().anyMatch(e -> name.equals(e.node.text()) && e.node.clickAction() != null && DesktopTestInput.hit(e).height > 0 && DesktopTestInput.hit(e).width > 0 && DesktopTestInput.hit(e).height == e.box.height);
    }
    private static boolean text(DesktopWindow window, String value) {
        return geometry(window).ordered().stream().anyMatch(e -> value.equals(e.node.text()));
    }
    private static java.util.List<SceneNode> fields(DesktopWindow window) {
        return geometry(window).ordered().stream().map(e -> e.node).filter(n -> n.control() instanceof SceneCompositionTarget)
                .collect(java.util.stream.Collectors.toList());
    }
    private static String editText(DesktopWindow window, SceneNode field) {
        String[] value = {null}; window.domain.execute(() -> value[0] = ((SceneCompositionTarget) field.control()).compositionState().snapshot().get().text); return value[0];
    }
    private static void type(DesktopWindow window, DesktopTestInput input, SceneNode field, String value) {
        input.click(geometry(window, field)); key(window, input, GLFW_KEY_A, GLFW_MOD_CONTROL);
        value.codePoints().forEach(c -> input.character.invoke(window.handle(), c)); window.frame();
    }
    private static void key(DesktopWindow window, DesktopTestInput input, int key, int mods) {
        input.key.invoke(window.handle(), key, 0, GLFW_PRESS, mods); input.key.invoke(window.handle(), key, 0, GLFW_RELEASE, mods); window.frame();
    }
    private static void await(DesktopWindow window, BooleanSupplier ready, String message) {
        long end = System.nanoTime() + 5_000_000_000L;
        do { window.frame(); if (ready.getAsBoolean()) return; } while (System.nanoTime() < end);
        throw new AssertionError(message);
    }
    private final class Fixture implements ScenePage {
        Signal<SceneTheme> theme;
        Path selected;
        @Override public void build(ScenePageContext context) {
            theme = context.domain().signal(SceneTheme.light());
            Signal<Boolean> open = context.domain().signal(false);
            SceneAppearance style = new SceneAppearance(context, theme);
            SceneNode layer = SceneLayouts.overlay().bind(NodeBinding.repeat(
                    () -> open.get() ? Collections.singletonList(Boolean.TRUE) : Collections.emptyList(), value -> value,
                    value -> SceneFilePicker.create(context, directory, theme, path -> { selected = path; open.set(false); }, () -> open.set(false))));
            context.mountOwned(SceneLayouts.overlay()
                    .bind(NodeBinding.value(() -> style.colors.get().canvas, SceneNode::background))
                    .add(style.action("打开文件选择器", null, () -> open.set(true), SceneAppearance.Role.PRIMARY)
                    .size(Size.natural(), Size.natural())).add(layer).own(style.scope));
        }
    }
}
