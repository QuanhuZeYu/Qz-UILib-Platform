package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static club.heiqi.uilib.host.desktop.DesktopTestFrames.settle;
import static club.heiqi.uilib.host.desktop.DesktopTestFrames.finitePresentations;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.geometry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.testing.gallery.PreferencesPage;
import club.heiqi.uilib.ui.scene.control.SceneAppearance;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.PaintCommand;

/** 正式通知偏好桌面回归；隐藏模式仍使用真实GL及生产回调，不覆盖OS可见焦点。 */
public final class DesktopStateControlsVerification extends TestApplication {
    private Path output;
    private DesktopWindow window;
    private DesktopTestInput input;
    public static void main(String[] args) throws Exception { new DesktopStateControlsVerification().run(args); }
    @Override protected void setup() throws Exception {
        output = Paths.get(arguments().length == 0 ? "state-controls-report" : arguments()[0]); Files.createDirectories(output);
    }
    @Override protected void runTests() throws Exception {
        Path config = output.resolve("preferences.yaml"); Files.deleteIfExists(config);
        boolean requestedVisible = Boolean.parseBoolean(System.getProperty("uilib.desktop.visible", "true"));
        try (DesktopWindow w = DesktopWindow.openWithServices(config, requestedVisible, services -> ctx -> {
            SceneNode root = PreferencesPage.create(ctx, ctx.theme(), () -> {});
            // 独立挂页没有Gallery外壳，沿共享主题角色补齐画布，而非暴露宿主清屏黑色。
            root.bind(NodeBinding.value(ctx.theme(), (node, theme) -> node.background(new SceneAppearance.Palette(theme).canvas)));
            ctx.mountOwned(root);
        })) {
            window = w; glfwSetWindowSize(w.handle(), 1040, 860); settle(w);
            boolean actualVisible = glfwGetWindowAttrib(w.handle(), GLFW_VISIBLE) == GLFW_TRUE;
            require(actualVisible == requestedVisible, requestedVisible
                    ? "状态控件验收必须使用请求的可见窗口" : "状态控件隐藏验收不应显示原生窗口");
            input = new DesktopTestInput(w); input.focus.invoke(w.handle(), true); w.frame();
            try (DesktopTestInput.ScriptedCursor cursor = input.scriptedCursor()) {
                scenario("单Tab入口及组内方向repeat和离组隔离", this::singleTabAndRepeat);
                scenario("禁用来源及外部选择更新", this::disabledAndExternal);
                scenario("离线拒绝保留真实开关状态", this::switchRejection);
                System.out.println("STATE_CONTROLS_DESKTOP visibleGLFW=" + actualVisible
                        + " sharedPreferences=true installedCallbacks=true sourceAuthority=true");
            }
        }
    }
    private void singleTabAndRepeat() throws Exception {
        SceneNode daily = control("每天汇总"), weekly = control("每周汇总"), autosave = control("自动保存偏好");
        focus(control("接收项目通知")); key(GLFW_KEY_TAB, 0);
        require(focused() == daily, "Tab没有直接进入当前选中项");
        key(GLFW_KEY_TAB, 0); require(focused() == autosave, "Tab在单选组内部增加了停靠点");
        key(GLFW_KEY_TAB, GLFW_MOD_SHIFT); require(focused() == daily, "反向Tab没有使用同一有效停靠项");
        event(GLFW_KEY_RIGHT, GLFW_PRESS, 0); event(GLFW_KEY_RIGHT, GLFW_REPEAT, 0); event(GLFW_KEY_RIGHT, GLFW_REPEAT, 0); window.frame();
        require(focused() == weekly && hasText("每周整理工作进展。"), "方向repeat未在原组内继续或没有跳过禁用实时选项");
        event(GLFW_KEY_RIGHT, GLFW_RELEASE, 0);
        // 同批递交整段历史，避免帧末真实物理快照先清除脚本按压而掩盖Core续段问题。
        event(GLFW_KEY_RIGHT, GLFW_PRESS, 0); enqueueKey(GLFW_KEY_TAB, 0); enqueueKey(GLFW_KEY_TAB, GLFW_MOD_SHIFT);
        event(GLFW_KEY_RIGHT, GLFW_REPEAT, 0); event(GLFW_KEY_RIGHT, GLFW_RELEASE, 0); window.frame();
        require(focused() == daily && hasText("每天汇总一次工作进展。"), "离组后旧repeat在同批回组时复活");
        key(GLFW_KEY_HOME, 0);
        require(focused() == daily && hasText("每天汇总一次工作进展。"), "fresh Home未选择首个可用项");
        key(GLFW_KEY_END, 0); require(focused() == weekly, "End没有到达末个可用项");
        screenshot("preferences-single-tab-repeat");
    }
    private void disabledAndExternal() throws Exception {
        SceneNode instant = control("实时通知"), daily = control("每天汇总"), weekly = control("每周汇总");
        require(!interactive(instant), "离线实时通知仍有有效交互资格");
        input.click("接收项目通知");
        require(!interactive(daily) && !interactive(weekly) && hasText("通知已关闭，保留已有频率。"), "通知开关没有控制整组资格");
        input.click("接收项目通知");
        require(interactive(daily) && interactive(weekly), "重新开启通知没有恢复组资格");
        input.click("恢复每天汇总"); require(hasText("已恢复为每天汇总。"), "外部选择动作没有发布来源");
        focus(control("自动保存偏好")); key(GLFW_KEY_TAB, GLFW_MOD_SHIFT);
        require(focused() == daily, "外部来源变更后组入口仍指向旧选择");
        input.click("连接网络"); require(interactive(instant), "网络来源更新没有启用实时选项");
        focus(daily); key(GLFW_KEY_HOME, 0);
        require(focused() == instant && hasText("网络已连接，新的进展会立即通知。"), "Home没有使用当前可用来源");
        screenshot("preferences-enabled-external-source");
        input.click("模拟离线"); require(!interactive(instant), "重新离线没有结束实时选项资格");
    }
    private void switchRejection() throws Exception {
        SceneNode autosave = control("自动保存偏好"); focus(autosave); finitePresentations(window);
        String before = symbolPlan(autosave); key(GLFW_KEY_ENTER, 0);
        require(Boolean.TRUE.equals(checked(autosave)) && hasText("连接网络后可修改自动保存；当前偏好已保留。"),
                "离线Enter拒绝没有即时保留原来源与反馈");
        finitePresentations(window);
        require(hasText("连接网络后可修改自动保存；当前偏好已保留。") && before.equals(symbolPlan(autosave)),
                "业务拒绝后开关保存了伪状态");
        key(GLFW_KEY_SPACE, 0);
        require(Boolean.TRUE.equals(checked(autosave)) && hasText("连接网络后可修改自动保存；当前偏好已保留。"),
                "离线Space拒绝没有即时保留原来源与反馈");
        finitePresentations(window);
        require(before.equals(symbolPlan(autosave)), "再次拒绝反转了控件私有状态");
        screenshot("preferences-offline-rejected");
        input.click("连接网络"); focus(autosave); key(GLFW_KEY_ENTER, 0);
        require(Boolean.FALSE.equals(checked(autosave)) && hasText("自动保存已关闭。"),
                "联网Enter未即时更新原来源与关闭反馈");
        finitePresentations(window);
        require(hasText("自动保存已关闭。") && !before.equals(symbolPlan(autosave)), "连接后未重读原来源或拇指未更新");
        key(GLFW_KEY_SPACE, 0);
        require(Boolean.TRUE.equals(checked(autosave)) && hasText("自动保存已开启。"),
                "联网Space未即时恢复原来源与开启反馈");
        finitePresentations(window);
        require(hasText("自动保存已开启。") && before.equals(symbolPlan(autosave)), "Space未沿当前来源恢复原状态");
        screenshot("preferences-switch-accepted");
    }
    private String symbolPlan(SceneNode node) {
        LayoutBox box = geometry(window, node).box;
        String plan = window.lastPlan.commands().stream().filter(c -> c.kind != PaintCommand.Kind.TEXT && c.box != null
                && c.box.x > box.x && c.box.y > box.y && c.box.x + c.box.width < box.x + box.width
                && c.box.y + c.box.height < box.y + box.height && c.box.width < box.width / 2)
                .map(c -> c.kind + ":" + c.box.x + "," + c.box.y + "," + c.box.width + "," + c.box.height + ":" + c.color)
                .collect(Collectors.joining("|"));
        require(!plan.isEmpty(), "正式开关没有可观察的内部向量图形"); return plan;
    }
    private SceneNode control(String name) {
        return geometry(window).ordered().stream().map(e -> e.node).filter(n -> name.equals(n.text()) && n.clickAction() != null)
                .findFirst().orElseThrow(() -> new AssertionError("缺少正式偏好控件：" + name));
    }
    private Boolean checked(SceneNode node) { return read(() -> node.control().semantics(node).values.checked); }
    private boolean interactive(SceneNode node) { return read(() -> window.page.runtime().interactive(node)); }
    private void focus(SceneNode target) { window.domain.execute(() -> require(window.page.runtime().focusRouter().navigateTo(window.page.runtime().captureMount(target)), "无法聚焦正式偏好控件")); window.frame(); }
    private <T> T read(Supplier<T> action) { Object[] value = {null}; window.domain.execute(() -> value[0] = action.get()); @SuppressWarnings("unchecked") T result = (T)value[0]; return result; }
    private SceneNode focused() { return read(() -> window.page.runtime().focusRouter().focused().get()); }
    private boolean hasText(String text) { return geometry(window).ordered().stream().anyMatch(e -> e.text != null && text.equals(e.text.logicalText)); }
    private void event(int code, int action, int mods) { input.key.invoke(window.handle(), code, 0, action, mods); }
    private void enqueueKey(int code, int mods) { event(code, GLFW_PRESS, mods); event(code, GLFW_RELEASE, mods); }
    private void key(int code, int mods) { enqueueKey(code, mods); window.frame(); }
    private void screenshot(String name) throws Exception {
        settle(window); window.fonts.beginPresentation(1_000_005);
        try { window.replay(window.lastPlan); } finally { window.fonts.endPresentation(); }
        require(glGetError() == GL_NO_ERROR, "偏好设置回放出现GL错误");
        ImageIO.write(DesktopTestReadback.readBack(window), "png", output.resolve(name + ".png").toFile());
    }
}
