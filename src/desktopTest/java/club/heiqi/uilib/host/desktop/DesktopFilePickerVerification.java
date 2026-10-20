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
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.SceneLayouts;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.PaintCommand;
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
        System.out.println("FILE_PICKER_DESKTOP realDirectory=true installedCallbacks=true locatedReveal=true rootTargetCleared=true virtualRows=true responsive=true geometryMetrics=true metadataTooltip=true editorFocusPreserved=true");
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
                SceneNode pathField = fields(window).stream()
                        .filter(node -> directory.toString().equals(editText(window, node))).findFirst()
                        .orElseThrow(() -> new AssertionError("路径字段没有当前目录"));
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
                SceneNode pickerModal = geometry(window).activeModal();
                SceneNode rootsLocation = geometry(window).ordered().stream().map(e -> e.node)
                        .filter(node -> node.clickAction() != null && node.text().startsWith("选择文件 · 根目录 / 盘符")).findFirst().get();
                input.click(geometry(window, rootsLocation));
                await(window, () -> geometry(window).activeModal() != pickerModal, "根范围层级菜单没有打开");
                require(geometry(window).ordered().stream().noneMatch(e -> e.node.clickAction() != null
                        && (directory.toString().equals(e.node.text()) || directory.getParent().toString().equals(e.node.text()))),
                        "根范围层级菜单混入上一目录祖先");
                key(window, input, GLFW_KEY_ESCAPE, 0);
                require(geometry(window).activeModal() == pickerModal, "关闭根层级菜单误关文件选择器");
                input.click("选择文件"); window.frame();
                require(page.selected == null && geometry(window).activeModal() != null, "根范围确认提交了旧文件");
                type(window, input, pathField, directory.toString()); key(window, input, GLFW_KEY_ENTER, 0);
                await(window, () -> text(window, "条目 000.ViDeO"), "不能从根范围导航回目录");
                SceneNode location = geometry(window).ordered().stream().map(e -> e.node)
                        .filter(node -> node.clickAction() != null && node.text().startsWith("选择文件 · ")).findFirst().get();
                input.click(geometry(window, location));
                await(window, () -> text(window, directory.toString()), "目录层级菜单没有保留当前路径");
                input.click(directory.toString());
                await(window, () -> text(window, "条目 000.ViDeO"), "目录层级点击未导航");
                input.click("所有文件");
                await(window, () -> visible(window, "VIDEO 文件") && text(window, "条目 000.ViDeO"), "类型筛选不可用");
                input.click("VIDEO 文件");
                await(window, () -> visible(window, "所有文件") && text(window, "条目 000.ViDeO"), "所有文件筛选不可用");
                input.click("名称 ↑");
                await(window, () -> text(window, "条目 149.ViDeO"), "名称降序未覆盖全目录");
                type(window, input, pathField, directory.toString()); key(window, input, GLFW_KEY_ENTER, 0);
                await(window, () -> visible(window, "名称 ↑") && text(window, "条目 000.ViDeO"), "路径导航未恢复默认排序");
                input.click("刷新"); await(window, () -> text(window, "条目 000.ViDeO"), "刷新未恢复当前范围");
                SceneNode search = fields(window).stream().filter(node -> node != pathField).findFirst().get();
                type(window, input, search, "149"); key(window, input, GLFW_KEY_ENTER, 0);
                await(window, () -> text(window, "条目 149.ViDeO") && !text(window, "条目 000.ViDeO"), "明确搜索动作没有覆盖后页");
                require(focused(window) == search, "搜索结果抢走名称字段焦点");
                input.click("清空搜索"); await(window, () -> text(window, "条目 000.ViDeO"), "清除搜索没有恢复目录");
                type(window, input, search, "149"); input.click("搜索");
                await(window, () -> text(window, "条目 149.ViDeO") && !text(window, "条目 000.ViDeO"), "搜索图标没有提交查询");
                input.click("清空搜索"); await(window, () -> text(window, "条目 000.ViDeO"), "搜索图标后的清空失败");
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
                    for (int[] size : new int[][] {{1100,820},{560,620},{800,420},{480,560},{400,620},{760,360}}) {
                        verifyLayout(window, input, list, pathField, search,
                                (dark ? "dark-" : "light-") + size[0] + "x" + size[1],
                                size[0], size[1], size[1] <= 360 ? .40 : size[0] >= 1000 ? .65 : .50,
                                size[1] <= 360 ? 2 : size[0] >= 1000 ? 8 : 3);
                    }
                    window.domain.execute(() -> {
                        SceneTheme current = page.theme.get();
                        page.theme.set(current.withTextStyle(current.textStyle.withEm(22)));
                    });
                    verifyLayout(window, input, list, pathField, search,
                            (dark ? "dark-" : "light-") + "900x760-em22", 900, 760, .60, 6);
                }
                input.click("取消"); require(page.selected == null && geometry(window).activeModal() == null, "取消仍提交或拦截输入");
            }
        }
    }
    private void verifyLayout(DesktopWindow window, DesktopTestInput input, SceneNode list,
            SceneNode pathField, SceneNode search, String name, int width, int height,
            double minimumRatio, int minimumRows) throws Exception {
        type(window, input, pathField, directory.toString());
        glfwSetWindowSize(window.handle(), width, height); DesktopTestFrames.finitePresentations(window);
        require(focused(window) == pathField, "尺寸变化抢走路径焦点：" + name);
        for (String action : new String[] {"搜索", "上一级", "根目录", "刷新", "前往", "下一页", "名称 ↑", "所有文件", "取消", "选择文件"})
            require(visible(window, action), "关键动作不完整可见：" + action + " " + name);
        require(fields(window).contains(pathField) && fields(window).contains(search), "布局切换替换了编辑字段：" + name);
        SceneNode root = geometry(window).ordered().stream().map(e -> e.node)
                .filter(node -> "根目录".equals(node.text()) && node.clickAction() != null).findFirst().get();
        input.point(window.width() - 2, window.height() - 2); window.frame();
        input.moveTo(root); DesktopTestFrames.finitePresentations(window);
        require(tooltipText(window, "根目录"), "导航图标没有完整名称提示：" + name);
        require(focused(window) == pathField, "导航提示抢走路径焦点：" + name);
        SceneGeometryView.Entry metadata = geometry(window).ordered().stream()
                .filter(e -> e.node.text().startsWith("VIDEO 文件") && complete(e))
                .findFirst().orElseThrow(() -> new AssertionError("视口没有完整元数据行：" + name));
        input.click(metadata);
        require(editText(window, pathField).contains(".ViDeO"), "元数据区域没有激活文件行：" + name);
        input.point(window.width() - 2, window.height() - 2); window.frame();
        input.moveTo(metadata.node); DesktopTestFrames.finitePresentations(window);
        String selectedName = java.nio.file.Paths.get(editText(window, pathField)).getFileName().toString();
        require(tooltipText(window, selectedName + "\n" + metadata.node.text()),
                "文件行提示未完整绘制名称、类型、大小与修改时间：" + name);
        input.point(window.width() - 2, window.height() - 2); DesktopTestFrames.finitePresentations(window);
        evidence(window, list, name, minimumRatio, minimumRows);
        // 同一搜索框跨宽度变化保留真实焦点与草稿，查询完成也不能把焦点送去列表。
        type(window, input, search, "149"); key(window, input, GLFW_KEY_ENTER, 0);
        await(window, () -> text(window, "条目 149.ViDeO") && !text(window, "条目 000.ViDeO"), "边界搜索失败：" + name);
        require(focused(window) == search, "边界搜索抢走焦点：" + name);
        input.click("清空搜索"); await(window, () -> text(window, "条目 000.ViDeO"), "边界清空搜索失败：" + name);
    }

    private void evidence(DesktopWindow window, SceneNode list, String name,
            double minimumRatio, int minimumRows) throws Exception {
        SceneGeometryView view = geometry(window);
        SceneNode frame = list;
        while (frame != null && (frame.control() == null || !"Frame".equals(frame.control().getClass().getSimpleName())))
            frame = frame.parent();
        require(frame != null && frame.children().size() == 3, "没有找到文件选择器正文框架");
        LayoutBox panel = view.get(frame.parent()).box;
        LayoutBox header = view.get(frame.children().get(0)).box;
        LayoutBox footer = view.get(frame.children().get(2)).box;
        LayoutBox viewport = view.get(list).box;
        long completeRows = view.ordered().stream()
                .filter(e -> within(e.node, list) && e.node.clickAction() != null
                        && e.node.text().startsWith("条目 ") && e.node.text().endsWith(".ViDeO") && complete(e)).count();
        double areaRatio = (double) viewport.width * viewport.height / ((double) panel.width * panel.height);
        double ratio = (double) viewport.height / panel.height;
        String metrics = String.format(java.util.Locale.ROOT,
                "{\"panel\":%s,\"header\":%s,\"list\":%s,\"footer\":%s,\"listAreaRatio\":%.6f,\"listHeightRatio\":%.6f,\"completeRows\":%d}",
                box(panel), box(header), box(viewport), box(footer), areaRatio, ratio, completeRows);
        System.out.println("FILE_PICKER_GEOMETRY " + name + " " + metrics);
        Files.write(output.resolve(name + ".metrics.json"), metrics.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DesktopGalleryEvidence.saveGeometry(window, output.resolve(name + ".geometry.json"));
        javax.imageio.ImageIO.write(DesktopTestReadback.readFront(window), "png", output.resolve(name + ".png").toFile());
        require(ratio >= minimumRatio, "列表占比不足：" + name + " " + ratio + " < " + minimumRatio);
        require(completeRows >= minimumRows, "列表完整可见行不足：" + name + " " + completeRows + " < " + minimumRows);
        require(header.y + header.height <= viewport.y && viewport.y + viewport.height <= footer.y,
                "头部或页脚侵入列表：" + name);
        require(complete(view.get(frame.children().get(0))) && complete(view.get(frame.children().get(2))),
                "头部或页脚被裁剪：" + name);
    }

    private static boolean tooltipText(DesktopWindow window, String text) {
        java.util.List<PaintCommand> commands = window.lastPlan.commands();
        if (commands.isEmpty()) return false;
        PaintCommand tip = commands.get(commands.size() - 1);
        // Tooltip 正文是末尾绘制命令；logicalText 保留省略前原文，必须同时检查实际绘制行。
        return tip.kind == PaintCommand.Kind.TEXT && text.equals(tip.text.logicalText)
                && tip.text.lines().stream().map(line -> line.text)
                        .collect(java.util.stream.Collectors.joining())
                        .equals(text.replace("\r", "").replace("\n", ""));
    }
    private static String box(LayoutBox box) {
        return "[" + box.x + "," + box.y + "," + box.width + "," + box.height + "]";
    }
    private static boolean within(SceneNode node, SceneNode ancestor) {
        for (SceneNode cursor = node; cursor != null; cursor = cursor.parent()) if (cursor == ancestor) return true;
        return false;
    }
    private static boolean complete(SceneGeometryView.Entry entry) {
        LayoutBox hit = DesktopTestInput.hit(entry);
        return entry.box.width > 0 && entry.box.height > 0
                && hit.width == entry.box.width && hit.height == entry.box.height;
    }
    private static SceneNode focused(DesktopWindow window) {
        SceneNode[] focus = {null};
        window.domain.execute(() -> focus[0] = window.page.runtime().focusRouter().focused().get());
        return focus[0];
    }

    private static boolean visible(DesktopWindow window, String name) {
        return geometry(window).ordered().stream().anyMatch(e -> name.equals(e.node.text()) && e.node.clickAction() != null && complete(e));
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
                    value -> SceneFilePicker.create(context, directory, theme, new SceneFilePicker.Options().filter("VIDEO 文件", "video"),
                            path -> { selected = path; open.set(false); }, () -> open.set(false))));
            context.mountOwned(SceneLayouts.overlay()
                    .bind(NodeBinding.value(() -> style.colors.get().canvas, SceneNode::background))
                    .add(style.action("打开文件选择器", null, () -> open.set(true), SceneAppearance.Role.PRIMARY)
                    .size(Size.natural(), Size.natural())).add(layer).own(style.scope));
        }
    }
}
