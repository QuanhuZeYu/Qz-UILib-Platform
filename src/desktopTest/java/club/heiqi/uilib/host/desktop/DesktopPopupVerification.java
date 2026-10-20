package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static club.heiqi.uilib.host.desktop.DesktopTestFrames.settle;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.geometry;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.hit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.testing.gallery.ProjectDetailsPage;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneAppearance;
import club.heiqi.uilib.ui.scene.control.SceneButton;
import club.heiqi.uilib.ui.scene.control.SceneSelect;
import club.heiqi.uilib.ui.scene.edit.SceneCompositionTarget;
import club.heiqi.uilib.ui.scene.edit.SceneEditState;
import club.heiqi.uilib.ui.scene.host.ScenePage;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.input.SceneNavigationEvent;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.SceneGeometryView;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;

/** 选择器和菜单的真实 GLFW 回归；可见性沿宿主配置，使用正式项目页面及公开控件 fixture。 */
public final class DesktopPopupVerification extends TestApplication {
    private Path output;
    private DesktopWindow window;
    private DesktopTestInput input;
    private boolean requestedVisible, actualVisible;
    public static void main(String[] args) throws Exception { new DesktopPopupVerification().run(args); }
    @Override protected void setup() throws Exception {
        output = Paths.get(arguments().length == 0 ? "popup-report" : arguments()[0]); Files.createDirectories(output);
        requestedVisible = Boolean.parseBoolean(System.getProperty("uilib.desktop.visible", "true"));
    }
    @Override protected void runTests() throws Exception {
        scenario("负责人预览确认与共享保存重置菜单", this::projectWorkflow);
        scenario("屏外当前项、缩高限位、外部吞尾和空Tab", this::longListAndClosing);
        System.out.println("POPUP_DESKTOP visibleGLFW=" + actualVisible + " focusSource=installed-callback sharedProjectDetails=true publicFixture=true installedCallbacks=true");
    }
    private Path config(String name) throws Exception { Path p = output.resolve(name + ".yaml"); Files.deleteIfExists(p); return p; }
    private void prepare(DesktopWindow w, int width, int height) {
        window = w; glfwSetWindowSize(w.handle(), width, height); settle(w);
        actualVisible = glfwGetWindowAttrib(w.handle(), GLFW_VISIBLE) == GLFW_TRUE;
        require(actualVisible == requestedVisible, requestedVisible ? "弹出层验收必须使用可见窗口" : "隐藏弹出层验收窗口意外显示");
        input = new DesktopTestInput(w); input.focus.invoke(w.handle(), true); w.frame();
    }
    private void projectWorkflow() throws Exception {
        try (DesktopWindow w = DesktopWindow.openWithServices(config("project-popup"), services -> ctx -> {
            // 独立项目页没有广场外壳，使用同一主题画布作为根背景。
            SceneNode root = ProjectDetailsPage.create(ctx, ctx.theme(), () -> {});
            root.bind(NodeBinding.value(() -> new SceneAppearance.Palette(ctx.theme().get()).canvas, SceneNode::background));
            ctx.mountOwned(root);
        })) {
            prepare(w, 1080, 860);
            try (DesktopTestInput.ScriptedCursor cursor = input.scriptedCursor()) {
                SceneNode trigger = control("林岚", false); focus(trigger); key(GLFW_KEY_DOWN, 0);
                require(open() && focused() == control("林岚", true), "选择器没有从当前负责人进入正式范围");
                key(GLFW_KEY_DOWN, 0);
                require(focused() == control("周宁", true) && "林岚".equals(trigger.text()) && !hasText("负责人已改为周宁。"),
                        "方向预览提前写入了负责人业务来源");
                key(GLFW_KEY_ESCAPE, 0); require(!open() && focused() == trigger && "林岚".equals(trigger.text()), "ESC关闭提交了预览或没有返回触发焦点");
                key(GLFW_KEY_DOWN, 0); key(GLFW_KEY_DOWN, 0); key(GLFW_KEY_ENTER, 0);
                require(!open() && "周宁".equals(trigger.text()) && hasText("负责人已改为周宁。"), "确认没有发布真实选择意图");
                input.click("名称与备注"); SceneNode name = nameField(); focus(name);
                enqueueKey(GLFW_KEY_A, GLFW_MOD_CONTROL); type("弹层保存项目"); w.frame();
                input.click("更多操作");
                require(open() && focused() == control("保存项目", true) && control("保存项目", false).enabled(), "菜单与原保存按钮没有使用同一当前资格");
                key(GLFW_KEY_ENTER, 0); require(!open() && hasText("项目名称和备注已保存。"), "菜单未沿共享命令保存");
                input.click("活动记录"); require(hasText("已保存：弹层保存项目"), "菜单保存没有发布原项目活动");
                input.click("名称与备注"); name = nameField(); focus(name);
                enqueueKey(GLFW_KEY_A, GLFW_MOD_CONTROL); enqueueKey(GLFW_KEY_BACKSPACE, 0); w.frame();
                require(name.text().isEmpty() && !control("保存项目", false).enabled(), "空名称没有改变原保存资格");
                input.click("更多操作"); require(!control("保存项目", true).enabled(), "菜单复制了过期保存条件");
                input.click(geometry(w, control("撤销未保存编辑", true)));
                require(!open() && "弹层保存项目".equals(nameField().text()) && hasText("名称和备注已恢复到上次保存。"), "菜单重置没有沿真实业务基线恢复");
                screenshot("project-menu-shared-command");
            }
        }
    }
    private void longListAndClosing() throws Exception {
        PopupPage page = new PopupPage();
        try (DesktopWindow w = DesktopWindow.open(config("long-popup"), page)) {
            prepare(w, 720, 680);
            try (DesktopTestInput.ScriptedCursor cursor = input.scriptedCursor()) {
                SceneNode trigger = control("候选999", false); focus(trigger); key(GLFW_KEY_DOWN, 0);
                require(open() && "候选999".equals(focused().text()) && inModal(focused()), "初始屏外选中项没有在本次打开中准备");
                long prepared = geometry(w).ordered().stream().filter(e -> inModal(e.node) && e.node.clickAction() != null).count();
                require(prepared < page.optionCount, "弹出列表创建了全部行控件");
                glfwSetWindowSize(w.handle(), 720, 120); settle(w);
                SceneNode last = focused(); LayoutBox layer = geometry(w, modal()).box;
                require(last != null && "候选999".equals(last.text()) && inModal(last)
                        && layer.x >= 0 && layer.x + layer.width <= w.width()
                        && layer.y >= 0 && layer.y + layer.height <= w.height()
                        && read(() -> page.selected.get()) == 999 && read(() -> page.changes.get()) == 0,
                        "缩高改变了焦点或选择来源，或弹层越出实际页面");
                // 普通 resize 保留滚动位置，新的显式导航才请求显露焦点项。
                key(GLFW_KEY_END, 0);
                last = focused(); SceneGeometryView snapshot = geometry(w);
                LayoutBox box = snapshot.get(last).box, shown = hit(snapshot.get(last));
                layer = snapshot.get(modal()).box;
                require("候选999".equals(last.text()) && shown.width == box.width && shown.height == box.height
                        && layer.x >= 0 && layer.x + layer.width <= w.width()
                        && layer.y >= 0 && layer.y + layer.height <= w.height(), "缩高后导航末项不完整或弹层越出实际页面");
                require(read(() -> page.selected.get()) == 999 && read(() -> page.changes.get()) == 0, "End导航产生了选择意图");
                screenshot("popup-last-999-short-window");
                key(GLFW_KEY_HOME, 0); require("候选0".equals(focused().text()) && read(() -> page.selected.get()) == 999,
                        "Home预览未导航或错误写入选择来源");
                key(GLFW_KEY_ESCAPE, 0); require(!open() && focused() == trigger, "长列表ESC未返回原触发");
                key(GLFW_KEY_DOWN, 0); require("候选999".equals(focused().text()), "再次打开没有使用真实选中来源");
                LayoutBox outside = hit(geometry(w, page.outside));
                require(!geometry(w, modal()).box.contains(outside.x + outside.width / 2, outside.y + outside.height / 2), "外部点击前提落入弹层内部");
                input.moveTo(page.outside); input.mouse.invoke(w.handle(), GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 0); w.frame();
                require(!open() && read(() -> page.outsideActions.get()) == 0, "外部DOWN没有关闭或重投了后台动作");
                input.mouse.invoke(w.handle(), GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 0); w.frame();
                require(read(() -> page.outsideActions.get()) == 0, "关闭后的旧UP激活了后台按钮");
                input.click(geometry(w, page.outside)); require(read(() -> page.outsideActions.get()) == 1, "新完整点击没有恢复普通按钮");
                SceneNode empty = control("空来源选择器", false); focus(empty); key(GLFW_KEY_DOWN, 0);
                require(open() && focused() == null && hasText("暂无可选项"), "空弹层建立了备用焦点或错误结果");
                key(GLFW_KEY_TAB, 0); require(!open() && focused() == page.outside, "空弹层Tab未在一次输入内继续到后一控件");
                focus(empty); key(GLFW_KEY_DOWN, 0); w.page.navigation(SceneNavigationEvent.PREVIOUS); w.frame();
                require(!open() && focused() == trigger, "空弹层反向Tab未在一次输入内继续到前一控件");
                focus(empty); key(GLFW_KEY_DOWN, 0); key(GLFW_KEY_ESCAPE, 0);
                require(!open() && focused() == empty, "空弹层ESC没有返回原触发器");
                require(read(() -> page.selected.get()) == 999 && read(() -> page.changes.get()) == 0, "预览或关闭产生了选择意图");
                screenshot("popup-empty-tab-closed");
            }
        }
    }
    /** 数据仅在验收内存；控件、范围、有限行与输入沿正式组合。 */
    private static final class PopupPage implements ScenePage {
        final int optionCount = 1000;
        Signal<Integer> selected, changes, outsideActions;
        SceneNode outside;
        @Override public void build(ScenePageContext ctx) {
            selected = ctx.domain().signal(999); changes = ctx.domain().signal(0); outsideActions = ctx.domain().signal(0);
            List<SceneSelect.Option<Integer>> options = new ArrayList<>();
            for (int i = 0; i < optionCount; i++) { final int key = i; options.add(new SceneSelect.Option<>(key, () -> "候选" + key)); }
            SceneNode select = SceneSelect.create(ctx, () -> options, selected, value -> { selected.set(value); changes.set(changes.get() + 1); });
            SceneNode empty = SceneSelect.create(ctx, () -> Collections.<SceneSelect.Option<Integer>>emptyList(), () -> null,
                    value -> { throw new AssertionError("空来源发布了选择意图"); }, () -> "空来源选择器", ctx.theme());
            outside = SceneButton.create(ctx, () -> "弹层外按钮", ctx.theme(), () -> outsideActions.set(outsideActions.get() + 1));
            ctx.mountOwned(new SceneNode().axis(SceneNode.Axis.ROW).size(Size.fill(1), Size.fill(1)).padding(8).gap(80)
                    .bind(NodeBinding.value(() -> new SceneAppearance.Palette(ctx.theme().get()).canvas, SceneNode::background))
                    .add(new SceneNode().size(Size.fixed(240), Size.fill(1)).gap(8).add(select).add(empty))
                    .add(outside.size(Size.fill(1), Size.natural())));
        }
    }
    private static boolean inModal(SceneNode node) { for (SceneNode at = node; at != null; at = at.parent()) if (at.modal()) return true; return false; }
    private boolean open() { return geometry(window).ordered().stream().anyMatch(e -> e.node.modal()); }
    private SceneNode modal() { return geometry(window).ordered().stream().map(e -> e.node).filter(SceneNode::modal).findFirst().orElseThrow(() -> new AssertionError("缺少正式弹出模态范围")); }
    private SceneNode control(String text, boolean popup) {
        return geometry(window).ordered().stream().map(e -> e.node).filter(n -> text.equals(n.text()) && n.clickAction() != null && inModal(n) == popup)
                .findFirst().orElseThrow(() -> new AssertionError("缺少正式" + (popup ? "弹出项" : "页面控件") + "：" + text));
    }
    private SceneNode nameField() { return geometry(window).ordered().stream().map(e -> e.node).filter(n -> n.control() instanceof SceneCompositionTarget
            && read(() -> ((SceneCompositionTarget)n.control()).compositionState().mode()) == SceneEditState.Mode.SINGLE_LINE)
            .findFirst().orElseThrow(() -> new AssertionError("缺少正式项目名称字段")); }
    private boolean hasText(String text) { return geometry(window).ordered().stream().anyMatch(e -> e.text != null && text.equals(e.text.logicalText)); }
    private void focus(SceneNode target) { window.domain.execute(() -> require(window.page.runtime().focusRouter().navigateTo(window.page.runtime().captureMount(target)), "无法聚焦正式弹层触发")); window.frame(); }
    private <T> T read(Supplier<T> action) { Object[] value = {null}; window.domain.execute(() -> value[0] = action.get()); @SuppressWarnings("unchecked") T result = (T)value[0]; return result; }
    private SceneNode focused() { return read(() -> window.page.runtime().focusRouter().focused().get()); }
    private void enqueueKey(int code, int mods) { input.key.invoke(window.handle(), code, 0, GLFW_PRESS, mods); input.key.invoke(window.handle(), code, 0, GLFW_RELEASE, mods); }
    private void key(int code, int mods) { enqueueKey(code, mods); window.frame(); }
    private void type(String text) { text.codePoints().forEach(cp -> input.character.invoke(window.handle(), cp)); }
    private void screenshot(String name) throws Exception {
        settle(window); window.fonts.beginPresentation(1_000_007);
        try { window.replay(window.lastPlan); } finally { window.fonts.endPresentation(); }
        require(glGetError() == GL_NO_ERROR, "弹出层页面回放出现GL错误");
        ImageIO.write(DesktopTestReadback.readBack(window), "png", output.resolve(name + ".png").toFile());
    }
}
