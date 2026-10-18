package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static club.heiqi.uilib.host.desktop.DesktopTestFrames.settle;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.geometry;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.hit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.testing.gallery.DataTablePage;
import club.heiqi.uilib.ui.scene.edit.SceneCompositionTarget;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.node.SceneNode;

/** 正式数据表可见桌面回归；只用公开页面、共同几何及生产回调，运行须等待桌面可用。 */
public final class DesktopDataTableVerification extends TestApplication {
    private Path output;
    private DesktopWindow window;
    private DesktopTestInput input;
    public static void main(String[] args) throws Exception { new DesktopDataTableVerification().run(args); }
    @Override protected void setup() throws Exception {
        output = Paths.get(arguments().length == 0 ? "data-table-report" : arguments()[0]); Files.createDirectories(output);
    }
    @Override protected void runTests() throws Exception {
        Path config = output.resolve("data-table.yaml"); Files.deleteIfExists(config);
        try (DesktopWindow w = DesktopWindow.openWithServices(config, true, services -> ctx ->
                ctx.mountOwned(DataTablePage.create(ctx, ctx.theme(), () -> {})))) {
            window = w; glfwSetWindowSize(w.handle(), 1280, 900); settle(w);
            require(glfwGetWindowAttrib(w.handle(), GLFW_VISIBLE) == GLFW_TRUE, "数据表验收必须使用可见窗口");
            input = new DesktopTestInput(w);
            // 自动路径只注入本窗口的生产回调，不请求系统前台焦点。
            input.focus.invoke(w.handle(), true); w.frame();
            try (DesktopTestInput.ScriptedCursor cursor = input.scriptedCursor()) {
                scenario("表头正文共享列盒及窄窗独立纵条", this::geometryAndScrollbars);
                scenario("排序后独立草稿及同批后续文字", this::sortAndDraft);
                scenario("动态列宽、隐藏恢复及原完成动作", this::columnsAndActions);
                System.out.println("DATA_TABLE_DESKTOP visibleGLFW=true sharedThousandRowPage=true installedCallbacks=true geometry=true");
            }
        }
    }
    private void geometryAndScrollbars() throws Exception {
        SceneNode title = firstTask(); assertAligned(title); screenshot("table-wide");
        glfwSetWindowSize(window.handle(), 640, 820); settle(window); assertAligned(firstTask());
        SceneNode vertical = scrollbar(false), horizontal = scrollbar(true);
        LayoutBox bar = hit(geometry(window, vertical));
        require(bar.width > 0 && bar.height > 0 && bar.x + bar.width <= window.width(), "窄窗正文纵条没有位于可见外框内");
        focus(horizontal); key(GLFW_KEY_END, 0);
        LayoutBox moved = hit(geometry(window, vertical));
        require(moved.x == bar.x && moved.width == bar.width && moved.height > 0, "正文纵条随全宽列内容横移");
        assertAligned(firstTask()); screenshot("table-narrow-horizontal-end");
        key(GLFW_KEY_HOME, 0); screenshot("table-narrow-horizontal-start");
        glfwSetWindowSize(window.handle(), 1280, 900); settle(window); top();
    }
    private void sortAndDraft() throws Exception {
        SceneNode original = firstTask(); String title = original.text(); SceneNode field = note(original);
        focus(field); type("保留原行草稿"); window.frame();
        require("保留原行草稿".equals(field.text()), "真实备注输入没有进入原行独立草稿");
        SceneNode header = sortHeader(); input.enqueue(geometry(window, header));
        enqueueKey(GLFW_KEY_TAB, 0); enqueueKey(GLFW_KEY_TAB, 0); type("排序后文字"); window.frame();
        SceneNode focused = focused();
        require(focused != null && focused.control() instanceof SceneCompositionTarget && "排序后文字".equals(focused.text()),
                "排序后的同批文字没有进入实际新焦点备注");
        SceneNode current = taskIn(row(focused)); require(!title.equals(current.text()), "排序没有切换业务投影或草稿被错绑原行");
        screenshot("table-sorted-same-batch-text");
        input.click(geometry(window, sortHeader())); top();
        SceneNode restored = node(n -> title.equals(n.text()) && n.clickAction() != null, "排序返回后的原任务");
        require("保留原行草稿".equals(note(restored).text()), "排序离屏后独立备注草稿丢失或改绑");
        screenshot("table-sort-restored-draft");
    }
    private void columnsAndActions() throws Exception {
        // 窄窗使两种任务列最小宽度都实际参与分配，宽窗可能已分得相同弹性宽度。
        glfwSetWindowSize(window.handle(), 640, 820); settle(window);
        focus(scrollbar(true)); key(GLFW_KEY_HOME, 0); top();
        SceneNode original = firstTask(); String title = original.text();
        SceneNode header = sortHeader(); int beforeWidth = geometry(window, header.parent()).box.width;
        input.click("加宽任务列"); int wider = geometry(window, sortHeader().parent()).box.width;
        require(wider > beforeWidth, "正式列宽按钮没有更新共享列宽"); assertAligned(original);
        SceneNode action = action(original); int actionBefore = geometry(window, action.parent()).box.x;
        String draft = note(original).text(); input.click("隐藏备注列");
        require(!hasText("我的备注") && nodes(n -> n.control() instanceof SceneCompositionTarget).isEmpty(), "隐藏列仍有备注呈现或输入目标");
        require(geometry(window, action.parent()).box.x < actionBefore, "隐藏列后剩余列没有按当前声明重排");
        screenshot("table-note-hidden"); input.click("显示备注列");
        SceneNode restored = node(n -> title.equals(n.text()) && n.clickAction() != null, "恢复备注列后的原任务");
        require(draft.equals(note(restored).text()), "隐藏恢复列清除了业务独立草稿"); assertAligned(restored);
        SceneNode complete = action(restored); String previous = complete.text();
        focus(complete); input.click(geometry(window, complete));
        require(!previous.equals(action(restored).text()) && hasText("已更新：" + title), "正式完成动作没有更新同一任务状态");
        input.click("收窄任务列"); assertAligned(restored); screenshot("table-note-restored-action");
    }
    private void assertAligned(SceneNode task) {
        SceneNode note = note(task), action = action(task);
        equalColumn(sortHeader(), task);
        equalColumn(node(n -> "我的备注".equals(n.text()), "备注表头"), note);
        equalColumn(node(n -> "操作".equals(n.text()), "操作表头"), action);
        SceneNode status = descendant(row(task), n -> "进行中".equals(n.text()) || "已完成".equals(n.text()), "任务状态");
        equalColumn(node(n -> "状态".equals(n.text()), "状态表头"), status);
    }
    private void equalColumn(SceneNode header, SceneNode cell) {
        LayoutBox a = geometry(window, header.parent()).box, b = geometry(window, cell.parent()).box;
        require(a.x == b.x && a.width == b.width, "表头与正文列盒不对齐：" + header.text() + " header=" + a + " body=" + b);
    }
    private SceneNode firstTask() {
        return nodes(DesktopDataTableVerification::task).stream().min(Comparator.comparingInt(n -> geometry(window, n).box.y))
                .orElseThrow(() -> new AssertionError("正式千行页面没有已准备任务"));
    }
    private static boolean task(SceneNode node) {
        return node.clickAction() != null && node.text().matches("(整理界面素材|核对设备输入|更新项目说明|准备发布清单|回顾用户反馈) · [0-9]+");
    }
    private SceneNode sortHeader() { return node(n -> n.clickAction() != null && n.text().startsWith("任务名称"), "排序表头"); }
    private SceneNode note(SceneNode task) { return descendant(row(task), n -> n.control() instanceof SceneCompositionTarget, "行备注"); }
    private SceneNode action(SceneNode task) { return descendant(row(task), n -> n.clickAction() != null && ("完成任务".equals(n.text()) || "重新打开".equals(n.text())), "行完成动作"); }
    private SceneNode row(SceneNode cell) {
        for (SceneNode at = cell.parent(); at != null; at = at.parent()) {
            final SceneNode candidate = at;
            boolean title = geometry(window).ordered().stream().map(e -> e.node).anyMatch(n -> task(n) && within(n, candidate));
            boolean field = geometry(window).ordered().stream().map(e -> e.node).anyMatch(n -> n.control() instanceof SceneCompositionTarget && within(n, candidate));
            if (title && field) return at;
        }
        throw new AssertionError("没有找到正式行的任务和备注组合");
    }
    private SceneNode taskIn(SceneNode row) { return descendant(row, DesktopDataTableVerification::task, "当前备注所属任务"); }
    private SceneNode descendant(SceneNode root, Predicate<SceneNode> condition, String label) { return node(n -> within(n, root) && condition.test(n), label); }
    private static boolean within(SceneNode node, SceneNode ancestor) { for (SceneNode at = node; at != null; at = at.parent()) if (at == ancestor) return true; return false; }
    private List<SceneNode> nodes(Predicate<SceneNode> condition) { return geometry(window).ordered().stream().map(e -> e.node).filter(condition).collect(Collectors.toList()); }
    private SceneNode node(Predicate<SceneNode> condition, String label) { return nodes(condition).stream().findFirst().orElseThrow(() -> new AssertionError("缺少" + label)); }
    private SceneNode scrollbar(boolean horizontal) {
        return geometry(window).ordered().stream().filter(e -> e.node.control() != null && e.node.control().capturesPointer()
                && e.node.focusable() && e.node.text().isEmpty() && hit(e).width > 0 && hit(e).height > 0
                && (horizontal ? e.box.width > e.box.height * 4L : e.box.height > e.box.width * 4L))
                .max(Comparator.comparingInt(e -> horizontal ? e.box.y : e.box.x)).map(e -> e.node)
                .orElseThrow(() -> new AssertionError("缺少可见" + (horizontal ? "横" : "纵") + "向滚动条"));
    }
    private void top() { focus(scrollbar(false)); key(GLFW_KEY_HOME, 0); settle(window); }
    private void focus(SceneNode target) { window.domain.execute(() -> require(window.page.runtime().focusRouter().navigateTo(window.page.runtime().captureMount(target)), "无法聚焦正式目标")); window.frame(); }
    private <T> T read(Supplier<T> action) { Object[] value = {null}; window.domain.execute(() -> value[0] = action.get()); @SuppressWarnings("unchecked") T result = (T) value[0]; return result; }
    private SceneNode focused() { return read(() -> window.page.runtime().focusRouter().focused().get()); }
    private boolean hasText(String text) { return geometry(window).ordered().stream().anyMatch(e -> e.text != null && text.equals(e.text.logicalText)); }
    private void enqueueKey(int code, int mods) { input.key.invoke(window.handle(), code, 0, GLFW_PRESS, mods); input.key.invoke(window.handle(), code, 0, GLFW_RELEASE, mods); }
    private void key(int code, int mods) { enqueueKey(code, mods); window.frame(); }
    private void type(String text) { text.codePoints().forEach(cp -> input.character.invoke(window.handle(), cp)); }
    private void screenshot(String name) throws Exception {
        settle(window); window.fonts.beginPresentation(1_000_003);
        try { window.replay(window.lastPlan); } finally { window.fonts.endPresentation(); }
        require(glGetError() == GL_NO_ERROR, "数据表回放出现GL错误");
        ImageIO.write(DesktopTestReadback.readBack(window), "png", output.resolve(name + ".png").toFile());
    }
}
