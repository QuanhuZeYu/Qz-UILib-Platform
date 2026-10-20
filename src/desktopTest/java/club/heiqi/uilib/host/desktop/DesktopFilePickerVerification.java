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
import club.heiqi.uilib.ui.scene.semantics.SceneSemanticSnapshot;
import club.heiqi.uilib.ui.scene.semantics.SceneSemantics;
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
        scenario("未选择条目时手输确认的旧成功与失败可见反馈", this::typedStaleConfirm);
        System.out.println("FILE_PICKER_DESKTOP realDirectory=true installedCallbacks=true locatedReveal=true rootTargetCleared=true virtualRows=true responsive=true geometryMetrics=true metadataTooltip=true editorFocusPreserved=true typedStaleSuccess=true typedStaleFailure=true visibleFeedback=true");
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
                SceneNode list = pickerList(window);
                require(list.control().inspectionLogicalItemCount(list) == 64, "文件列表没有使用当前目录页预算");
                require(list.control().inspectionInstantiatedItemCount(list) < 64, "目录页仍一次挂载所有行");
                SceneNode pathField = field(window, "输入目录或文件路径");
                require(directory.toString().equals(editText(window, pathField)), "路径字段没有当前目录");
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
                SceneNode search = field(window, "按名称搜索");
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
    private void typedStaleConfirm() throws Exception {
        Fixture page = new Fixture();
        try (DesktopWindow window = DesktopWindow.openWithServices(output.resolve("typed-stale.yaml"), true, services -> page)) {
            glfwSetWindowSize(window.handle(), 1100, 820); DesktopTestFrames.settle(window);
            DesktopTestInput input = new DesktopTestInput(window);
            input.focus.invoke(window.handle(), true); window.frame();
            try (DesktopTestInput.ScriptedCursor ignored = input.scriptedCursor()) {
                for (boolean existing : new boolean[] {true, false}) {
                    input.click("打开文件选择器");
                    await(window, () -> text(window, "条目 000.ViDeO"), "全新选择器未读到非空目录");
                    DesktopTestFrames.finitePresentations(window);
                    SceneNode modal = pickerModal(window);
                    SceneNode pathField = field(window, "输入目录或文件路径");
                    Path first = directory.resolve(existing ? "条目 000.ViDeO" : "不存在的待核验文件.ViDeO");
                    String laterDraft = directory.resolve("尚未确认的新路径.ViDeO").toString();
                    // 每例重新打开，未点击任何列表条目；只手输 A，避免旧选择的通用反馈掩盖请求反馈。
                    type(window, input, pathField, first.toString());
                    require(first.toString().equals(editText(window, pathField)), "手输 A 未成为当前草稿");
                    int cancelledBefore = page.cancelledCalls;
                    // 同一真实输入批次先确认 A 再编辑 B，worker 结果只能在输入结算后领取。
                    input.enqueueClick("选择文件");
                    input.enqueue(geometry(window, pathField));
                    input.key.invoke(window.handle(), GLFW_KEY_A, 0, GLFW_PRESS, GLFW_MOD_CONTROL);
                    input.key.invoke(window.handle(), GLFW_KEY_A, 0, GLFW_RELEASE, GLFW_MOD_CONTROL);
                    laterDraft.codePoints().forEach(c -> input.character.invoke(window.handle(), c));
                    window.frame();
                    await(window, () -> page.selectedCalls != 0 || geometry(window).activeModal() != modal
                            || action(geometry(window), modal, "选择文件").enabled()
                            && feedbackVisible(window, "路径已更改，请重新确认。"),
                            "未选择条目的旧核验没有可见重新确认反馈：" + existing);
                    require(page.selectedCalls == 0 && page.selected == null && page.cancelledCalls == cancelledBefore,
                            "旧核验触发了选择或取消回调：" + existing);
                    require(pickerModal(window) == modal && laterDraft.equals(editText(window, pathField)),
                            "旧核验关闭选择器或覆盖 B 草稿：" + existing);
                    DesktopTestFrames.settle(window);
                    require(feedbackVisible(window, "路径已更改，请重新确认。"), "请求反馈没有完整可见地绘制");
                    String name = "typed-stale-" + (existing ? "success" : "failure");
                    DesktopGalleryEvidence.saveGeometry(window, output.resolve(name + ".geometry.json"));
                    javax.imageio.ImageIO.write(DesktopTestReadback.readFront(window), "png", output.resolve(name + ".png").toFile());
                    System.out.println("FILE_PICKER_TYPED_STALE result=" + (existing ? "success" : "failure")
                            + " freshPicker=true noListSelection=true sameInputBatch=true callbacks=0"
                            + " modalRetained=true laterDraftPreserved=true completeVisibleFeedback=true");
                    input.click("取消");
                    require(geometry(window).activeModal() == null && page.cancelledCalls == cancelledBefore + 1,
                            "旧核验后取消没有正常结束选择器");
                    DesktopTestFrames.finitePresentations(window);
                }
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
        evidence(window, list, pathField, search, name, minimumRatio, minimumRows);
        // 同一搜索框跨宽度变化保留真实焦点与草稿，查询完成也不能把焦点送去列表。
        type(window, input, search, "149"); key(window, input, GLFW_KEY_ENTER, 0);
        await(window, () -> text(window, "条目 149.ViDeO") && !text(window, "条目 000.ViDeO"), "边界搜索失败：" + name);
        require(focused(window) == search, "边界搜索抢走焦点：" + name);
        input.click("清空搜索"); await(window, () -> text(window, "条目 000.ViDeO"), "边界清空搜索失败：" + name);
    }

    private void evidence(DesktopWindow window, SceneNode list, SceneNode pathField, SceneNode search, String name,
            double minimumRatio, int minimumRows) throws Exception {
        SceneGeometryView view = geometry(window);
        SceneNode modal = pickerModal(window);
        require(list == pickerList(window), "尺寸变化替换了正式列表：" + name);
        java.util.List<SceneNode> headerControls = new java.util.ArrayList<>();
        headerControls.add(pathField); headerControls.add(search);
        for (String action : new String[] {"后退", "前进", "上一级", "根目录", "刷新", "前往", "搜索", "名称 ↑", "所有文件"})
            headerControls.add(action(view, modal, action));
        headerControls.add(view.ordered().stream().map(e -> e.node)
                .filter(node -> within(node, modal) && node.clickAction() != null && node.text().startsWith("选择文件 · "))
                .findFirst().orElseThrow(() -> new AssertionError("没有当前目录层级动作")));
        java.util.List<SceneNode> footerControls = new java.util.ArrayList<>();
        for (String action : new String[] {"上一页", "下一页", "取消", "选择文件"})
            footerControls.add(action(view, modal, action));
        java.util.List<SceneNode> controls = new java.util.ArrayList<>(headerControls);
        controls.addAll(footerControls);
        // 面板由列表及真实操作的共同几何归属决定，内部布局类与组合层数不构成验证合同。
        SceneNode panelNode = list;
        for (SceneNode control : controls)
            while (panelNode != null && !within(control, panelNode)) panelNode = panelNode.parent();
        require(panelNode != null && panelNode != modal && within(panelNode, modal), "操作没有同属当前弹窗面板");
        LayoutBox panel = view.get(panelNode).box;
        LayoutBox header = controlBounds(view, headerControls, name);
        LayoutBox footer = controlBounds(view, footerControls, name);
        LayoutBox viewport = view.get(list).box;
        require(complete(view.get(panelNode)) && complete(view.get(list)), "面板或列表被裁剪：" + name);
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
        require(contains(panel, header) && contains(panel, viewport) && contains(panel, footer),
                "关键区域溢出弹窗面板：" + name);
    }

    private static SceneNode pickerModal(DesktopWindow window) {
        SceneSemanticSnapshot snapshot = window.semantics();
        SceneSemanticSnapshot.Entry dialog = snapshot.get(snapshot.currentModal);
        require(dialog != null && dialog.values.role == SceneSemantics.Role.GROUP
                && "选择本地文件".equals(dialog.values.name), "当前模态不是正式文件选择弹窗");
        SceneNode modal = geometry(window).activeModal();
        require(modal != null, "正式弹窗缺少当前模态几何");
        return modal;
    }
    private static SceneNode pickerList(DesktopWindow window) {
        SceneNode modal = pickerModal(window);
        SceneNode[] result = {null};
        window.domain.execute(() -> result[0] = geometry(window).ordered().stream().map(e -> e.node)
                .filter(node -> within(node, modal) && node.control() != null)
                .filter(node -> {
                    SceneSemantics semantics = node.control().semantics(node);
                    return semantics != null && semantics.values.role == SceneSemantics.Role.LIST;
                }).findFirst().orElseThrow(() -> new AssertionError("当前弹窗缺少正式 LIST")));
        return result[0];
    }
    private static SceneNode field(DesktopWindow window, String description) {
        SceneNode modal = pickerModal(window);
        SceneNode[] result = {null};
        window.domain.execute(() -> result[0] = fields(window).stream().filter(node -> within(node, modal))
                .filter(node -> description.equals(node.control().semantics(node).values.description))
                .findFirst().orElseThrow(() -> new AssertionError("当前弹窗缺少正式字段：" + description)));
        return result[0];
    }
    private static SceneNode action(SceneGeometryView view, SceneNode modal, String name) {
        return view.ordered().stream().map(e -> e.node)
                .filter(node -> within(node, modal) && node.clickAction() != null && name.equals(node.text()))
                .findFirst().orElseThrow(() -> new AssertionError("当前弹窗缺少动作：" + name));
    }
    private static LayoutBox controlBounds(SceneGeometryView view, java.util.List<SceneNode> controls, String name) {
        LayoutBox bounds = null;
        for (int i = 0; i < controls.size(); i++) {
            SceneGeometryView.Entry entry = view.get(controls.get(i));
            require(entry != null && complete(entry), "关键操作被裁剪：" + controls.get(i).text() + " " + name);
            LayoutBox box = entry.box;
            for (int j = 0; j < i; j++) {
                LayoutBox overlap = box.intersect(view.get(controls.get(j)).box);
                require(overlap.width == 0 || overlap.height == 0, "关键操作重叠：" + controls.get(i).text() + " " + name);
            }
            if (bounds == null) bounds = box;
            else {
                int left = Math.min(bounds.x, box.x), top = Math.min(bounds.y, box.y);
                bounds = new LayoutBox(left, top, Math.max(bounds.x + bounds.width, box.x + box.width) - left,
                        Math.max(bounds.y + bounds.height, box.y + box.height) - top);
            }
        }
        return bounds;
    }
    private static boolean contains(LayoutBox outer, LayoutBox inner) {
        return inner.x >= outer.x && inner.y >= outer.y
                && inner.x + inner.width <= outer.x + outer.width && inner.y + inner.height <= outer.y + outer.height;
    }
    private static boolean feedbackVisible(DesktopWindow window, String message) {
        SceneNode modal = geometry(window).activeModal();
        if (modal == null || geometry(window).ordered().stream()
                .noneMatch(e -> within(e.node, modal) && message.equals(e.node.text()) && complete(e))) return false;
        LayoutBox viewport = new LayoutBox(0, 0, window.width(), window.height());
        return window.lastPlan.commands().stream().anyMatch(command -> command.kind == PaintCommand.Kind.TEXT
                && message.equals(command.text.logicalText)
                && message.equals(command.text.lines().stream().map(line -> line.text)
                        .collect(java.util.stream.Collectors.joining()))
                && contains(viewport, command.box) && (command.clip == null || contains(command.clip, command.box)));
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
        int selectedCalls, cancelledCalls;
        @Override public void build(ScenePageContext context) {
            theme = context.domain().signal(SceneTheme.light());
            Signal<Boolean> open = context.domain().signal(false);
            SceneAppearance style = new SceneAppearance(context, theme);
            SceneNode layer = SceneLayouts.overlay().bind(NodeBinding.repeat(
                    () -> open.get() ? Collections.singletonList(Boolean.TRUE) : Collections.emptyList(), value -> value,
                    value -> SceneFilePicker.create(context, directory, theme, new SceneFilePicker.Options().filter("VIDEO 文件", "video"),
                            path -> { selectedCalls++; selected = path; open.set(false); },
                            () -> { cancelledCalls++; open.set(false); })));
            context.mountOwned(SceneLayouts.overlay()
                    .bind(NodeBinding.value(() -> style.colors.get().canvas, SceneNode::background))
                    .add(style.action("打开文件选择器", null, () -> open.set(true), SceneAppearance.Role.PRIMARY)
                    .size(Size.natural(), Size.natural())).add(layer).own(style.scope));
        }
    }
}
