package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static club.heiqi.uilib.host.desktop.DesktopTestFrames.settle;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.geometry;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.hit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.testing.gallery.ScrollAreaPage;
import club.heiqi.uilib.ui.scene.control.SceneScrollbar;
import club.heiqi.uilib.ui.scene.input.SceneEditEvent;
import club.heiqi.uilib.ui.scene.input.SceneNavigationEvent;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.node.SceneNode;

/** 可见桌面方向回归；原生回调注入与共同几何取证，运行须等待桌面可用。 */
public final class DesktopDirectionVerification extends TestApplication {
    private Path output;
    public static void main(String[] args) throws Exception { new DesktopDirectionVerification().run(args); }
    @Override protected void setup() throws Exception {
        output = Paths.get(arguments().length == 0 ? "direction-report" : arguments()[0]);
        Files.createDirectories(output);
    }
    @Override protected void runTests() throws Exception {
        scenario("共享双轴任务网格四方向、动作与缩窗显露", this::taskGrid);
        scenario("文本端点、只读与组字优先于方向焦点", this::editingPriority);
        scenario("滚动条方向及端点消费保持当前焦点", this::scrollbarPriority);
        System.out.println("DIRECTION_DESKTOP visibleGLFW=true installedCallbacks=true sharedTaskGrid=true osIme=false");
    }
    private Path config(String name) throws Exception {
        Path path = output.resolve(name + ".yaml"); Files.deleteIfExists(path); return path;
    }
    private DesktopTestInput input(DesktopWindow w) {
        require(glfwGetWindowAttrib(w.handle(), GLFW_VISIBLE) == GLFW_TRUE, "方向验收必须使用可见窗口");
        settle(w);
        DesktopTestInput input = new DesktopTestInput(w);
        // 自动路径显式注入焦点恢复；不移动系统光标，不请求系统前台焦点。
        input.focus.invoke(w.handle(), true); w.frame();
        return input;
    }
    private void taskGrid() throws Exception {
        try (DesktopWindow w = DesktopWindow.openWithServices(config("task-grid"), true, services -> ctx ->
                ctx.mountOwned(ScrollAreaPage.create(ctx, ctx.theme(), () -> {})))) {
            glfwSetWindowSize(w.handle(), 1280, 900); DesktopTestInput in = input(w);
            try (DesktopTestInput.ScriptedCursor cursor = in.scriptedCursor()) {
                SceneNode first = task(w, "准备 · 任务1"), right = task(w, "准备 · 任务2"), down = task(w, "进行中 · 任务2");
                focus(w, first); key(w, in, GLFW_KEY_RIGHT); require(focused(w) == right, "右箭头没有到达同一行下一任务");
                key(w, in, GLFW_KEY_DOWN); require(focused(w) == down, "下箭头没有到达同列下一任务");
                w.page.navigation(SceneNavigationEvent.LEFT); w.frame();
                require(focused(w) == task(w, "进行中 · 任务1"), "中性左方向与键路径结果不一致");
                key(w, in, GLFW_KEY_UP); require(focused(w) == first, "上箭头没有回到同列任务");
                require(hasText(w, "从左上角出发，滚动到右下角"), "方向导航隐式打开任务");
                key(w, in, GLFW_KEY_ENTER); require(hasText(w, "已打开：准备 · 任务1"), "任务原业务动作未保留");
                screenshot(w, "task-grid-wide");
                Object session = w.page;
                glfwSetWindowSize(w.handle(), 720, 820); settle(w); focus(w, first);
                LayoutBox before = geometry(w, first).box;
                key(w, in, GLFW_KEY_RIGHT); require(focused(w) == right, "缩窗后的方向沿用了旧目标关系");
                LayoutBox after = geometry(w, right).box, visible = hit(geometry(w, right));
                require(after.width > 0 && visible.width > 0 && visible.height > 0 && after.x != before.x,
                        "方向目标没有在缩窗后当前几何中显露");
                require(w.page == session && read(w, () -> w.page.runtime().focusRouter().focusVisible(right)),
                        "缩窗重建页面或丢失正式键盘焦点线索");
                Set<SceneNode> visited = Collections.newSetFromMap(new IdentityHashMap<SceneNode, Boolean>());
                boolean edge = false;
                int limit = geometry(w).ordered().size();
                for (int step = 0; step <= limit; step++) {
                    SceneNode current = focused(w); require(visited.add(current), "右方向循环返回已访问目标");
                    key(w, in, GLFW_KEY_RIGHT);
                    if (focused(w) == current) { edge = true; key(w, in, GLFW_KEY_RIGHT); require(focused(w) == current, "边缘方向重复改变焦点"); break; }
                }
                require(edge, "有限页面中右方向没有到达边缘");
                screenshot(w, "task-grid-narrow-edge");
            }
        }
    }
    private void editingPriority() throws Exception {
        DesktopCommandPage page = new DesktopCommandPage();
        try (DesktopWindow w = DesktopWindow.open(config("editor-priority"), page)) {
            DesktopTestInput in = input(w);
            try (DesktopTestInput.ScriptedCursor cursor = in.scriptedCursor()) {
                focus(w, page.field); w.domain.execute(() -> page.editing.select(0, 0));
                key(w, in, GLFW_KEY_LEFT);
                require(focused(w) == page.field && read(w, () -> page.editing.snapshot().get().caret) == 0,
                        "文本端点方向没有消费而转移焦点");
                w.domain.execute(() -> page.readOnly.set(true)); key(w, in, GLFW_KEY_RIGHT);
                require(focused(w) == page.field && read(w, () -> page.editing.snapshot().get().caret) == 1,
                        "只读字段方向未保留光标移动");
                w.page.edit(SceneEditEvent.composition("候选", true));
                w.page.navigation(SceneNavigationEvent.DOWN); w.frame();
                require(focused(w) == page.field, "中性方向越过旧组字联系");
                w.page.edit(SceneEditEvent.composition("", false)); w.frame();
                require(read(w, () -> page.actions.get()) == 0 && read(w, () -> page.submissions.get()) == 0,
                        "方向输入执行了应用动作或隐式提交");
                screenshot(w, "editor-priority");
            }
        }
    }
    private void scrollbarPriority() throws Exception {
        SceneNode[] viewport = {null}, bar = {null};
        try (DesktopWindow w = DesktopWindow.openWithServices(config("scrollbar-priority"), true, services -> ctx -> {
            viewport[0] = new SceneNode().size(Size.fill(1), Size.fixed(220)).scrollable(true).clip(true)
                    .add(new SceneNode().size(Size.fill(1), Size.fixed(900)).text("滚动条方向优先"));
            bar[0] = SceneScrollbar.create(ctx, viewport[0]).size(Size.fixed(12), Size.fixed(220));
            ctx.mountOwned(new SceneNode().size(Size.fill(1), Size.fill(1)).padding(30)
                    .add(new SceneNode().axis(SceneNode.Axis.ROW).size(Size.fixed(400), Size.fixed(220)).add(viewport[0]).add(bar[0])));
        })) {
            DesktopTestInput in = input(w);
            try (DesktopTestInput.ScriptedCursor cursor = in.scriptedCursor()) {
                focus(w, bar[0]); int before = geometry(w, viewport[0]).scrollOffset;
                key(w, in, GLFW_KEY_DOWN);
                require(focused(w) == bar[0] && geometry(w, viewport[0]).scrollOffset > before,
                        "滚动条方向未优先消费或被转成焦点导航");
                key(w, in, GLFW_KEY_END); int end = geometry(w, viewport[0]).scrollOffset;
                key(w, in, GLFW_KEY_DOWN);
                require(focused(w) == bar[0] && geometry(w, viewport[0]).scrollOffset == end,
                        "滚动条端点无变化时让渡了方向焦点");
                screenshot(w, "scrollbar-priority");
            }
        }
    }
    private static SceneNode task(DesktopWindow w, String title) {
        SceneNode heading = geometry(w).ordered().stream().filter(e -> title.equals(e.node.text())).map(e -> e.node)
                .findFirst().orElseThrow(() -> new AssertionError("缺少共享任务标题：" + title));
        return geometry(w).ordered().stream().map(e -> e.node)
                .filter(n -> "查看任务".equals(n.text()) && n.clickAction() != null && within(n, heading.parent()))
                .findFirst().orElseThrow(() -> new AssertionError("缺少正式任务动作：" + title));
    }
    private static boolean within(SceneNode node, SceneNode ancestor) {
        for (SceneNode at = node; at != null; at = at.parent()) if (at == ancestor) return true;
        return false;
    }
    private static void focus(DesktopWindow w, SceneNode node) {
        w.domain.execute(() -> require(w.page.runtime().focusRouter().navigateTo(w.page.runtime().captureMount(node)), "测试前提无法聚焦正式目标"));
        w.frame(); require(focused(w) == node, "聚焦前提没有在当前几何生效");
    }
    private static <T> T read(DesktopWindow w, Supplier<T> action) {
        Object[] value = {null}; w.domain.execute(() -> value[0] = action.get());
        @SuppressWarnings("unchecked") T result = (T) value[0]; return result;
    }
    private static SceneNode focused(DesktopWindow w) { return read(w, () -> w.page.runtime().focusRouter().focused().get()); }
    private static boolean hasText(DesktopWindow w, String text) {
        return geometry(w).ordered().stream().anyMatch(e -> e.text != null && text.equals(e.text.logicalText));
    }
    private static void key(DesktopWindow w, DesktopTestInput in, int code) {
        in.key.invoke(w.handle(), code, 0, GLFW_PRESS, 0); in.key.invoke(w.handle(), code, 0, GLFW_RELEASE, 0); w.frame();
    }
    private void screenshot(DesktopWindow w, String name) throws Exception {
        settle(w); w.fonts.beginPresentation(1_000_002);
        try { w.replay(w.lastPlan); } finally { w.fonts.endPresentation(); }
        require(glGetError() == GL_NO_ERROR, "方向验收绘制出现GL错误");
        ImageIO.write(DesktopTestReadback.readBack(w), "png", output.resolve(name + ".png").toFile());
    }
}
