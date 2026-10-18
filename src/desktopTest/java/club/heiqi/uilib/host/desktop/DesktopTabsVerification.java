package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static club.heiqi.uilib.host.desktop.DesktopTestFrames.settle;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.geometry;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.hit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.testing.gallery.ProjectDetailsPage;
import club.heiqi.uilib.ui.scene.edit.SceneCompositionTarget;
import club.heiqi.uilib.ui.scene.edit.SceneEditState;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.SceneGeometryView;
import club.heiqi.uilib.ui.scene.node.SceneNode;

/** 正式项目详情标签的可见桌面回归；仅生产回调和共同几何，运行须等待桌面可用。 */
public final class DesktopTabsVerification extends TestApplication {
    private Path output;
    private DesktopWindow window;
    private DesktopTestInput input;
    private SceneNode originalName, originalNotes;
    private static final String NAME = "Platform项目草稿", NOTES = "备注第一行\n备注第二行";
    public static void main(String[] args) throws Exception { new DesktopTabsVerification().run(args); }
    @Override protected void setup() throws Exception {
        output = Paths.get(arguments().length == 0 ? "tabs-report" : arguments()[0]); Files.createDirectories(output);
    }
    @Override protected void runTests() throws Exception {
        Path config = output.resolve("project-details.yaml"); Files.deleteIfExists(config);
        try (DesktopWindow w = DesktopWindow.openWithServices(config, true, services -> ctx ->
                ctx.mountOwned(ProjectDetailsPage.create(ctx, ctx.theme(), () -> {})))) {
            window = w; glfwSetWindowSize(w.handle(), 1040, 860); settle(w);
            require(glfwGetWindowAttrib(w.handle(), GLFW_VISIBLE) == GLFW_TRUE, "标签页验收必须使用可见窗口");
            input = new DesktopTestInput(w); input.focus.invoke(w.handle(), true); w.frame();
            try (DesktopTestInput.ScriptedCursor cursor = input.scriptedCursor()) {
                scenario("标签单入口及切换后同批字段文字", this::sameBatchFields);
                scenario("窄窗横向显露和方向repeat", this::narrowHeader);
                scenario("保存命令活动反馈及返回草稿", this::saveAndActivity);
                scenario("动态禁用与同键重排", this::disableAndReorder);
                System.out.println("TABS_DESKTOP visibleGLFW=true sharedProjectDetails=true installedCallbacks=true currentPanelOnly=true");
            }
        }
    }
    private void sameBatchFields() throws Exception {
        require(!hasEditor(), "未访问编辑面板就创建了字段");
        SceneNode notesTab = control("名称与备注"); focus(control("项目概览"));
        enqueueKey(GLFW_KEY_RIGHT, 0); enqueueKey(GLFW_KEY_TAB, 0); enqueueKey(GLFW_KEY_A, GLFW_MOD_CONTROL); type(NAME); window.frame();
        originalName = focused();
        require(editor(originalName, SceneEditState.Mode.SINGLE_LINE) && NAME.equals(originalName.text()),
                "切换标签后的同批文字没有进入新名称字段");
        key(GLFW_KEY_TAB, GLFW_MOD_SHIFT); require(focused() == notesTab, "反向Tab没有返回标签条唯一入口");
        key(GLFW_KEY_TAB, 0); require(focused() == originalName, "正向Tab没有从标签条直接进入当前面板");
        key(GLFW_KEY_TAB, 0); originalNotes = focused();
        require(editor(originalNotes, SceneEditState.Mode.MULTI_LINE), "面板逻辑顺序未到达多行备注");
        enqueueKey(GLFW_KEY_A, GLFW_MOD_CONTROL); type("备注第一行"); enqueueKey(GLFW_KEY_ENTER, 0); type("备注第二行"); window.frame();
        require(NOTES.equals(originalNotes.text()) && !hasText("项目名称和备注已保存。"), "多行编辑错误提交或未保留草稿");
        screenshot("tabs-same-batch-fields");
    }
    private void narrowHeader() throws Exception {
        glfwSetWindowSize(window.handle(), 240, 860); settle(window);
        SceneGeometryView.Entry viewport = headerViewport(); require(viewport.scrollMaximum > 0, "窄窗没有形成正式横向滚动范围");
        focus(control("项目概览"));
        event(GLFW_KEY_RIGHT, GLFW_PRESS); event(GLFW_KEY_RIGHT, GLFW_REPEAT); event(GLFW_KEY_RIGHT, GLFW_REPEAT);
        event(GLFW_KEY_RIGHT, GLFW_RELEASE); window.frame();
        require(focused() == control("项目概览") && !hasEditor(), "标签条有限方向repeat未沿实际组焦点切换");
        key(GLFW_KEY_END, 0); SceneNode history = control("活动记录"); LayoutBox visible = hit(geometry(window, history));
        require(focused() == history && visible.width > 0 && visible.height > 0 && headerViewport().scrollOffset > 0,
                "窄窗末尾标签没有沿正式横向端口显露");
        screenshot("tabs-narrow-history");
        key(GLFW_KEY_HOME, 0); key(GLFW_KEY_RIGHT, 0); key(GLFW_KEY_TAB, 0);
        require(editor(focused(), SceneEditState.Mode.SINGLE_LINE) && NAME.equals(focused().text()),
                "横向滚动条增加了标签与当前面板之间的Tab停靠，或草稿丢失");
        key(GLFW_KEY_TAB, GLFW_MOD_SHIFT); require(focused() == control("名称与备注"), "窄窗反向Tab没有返回相同条入口");
        key(GLFW_KEY_TAB, 0); key(GLFW_KEY_TAB, 0);
        require(editor(focused(), SceneEditState.Mode.MULTI_LINE) && NOTES.equals(focused().text()), "窄窗返回备注丢失独立状态");
        glfwSetWindowSize(window.handle(), 1040, 860); settle(window);
    }
    private void saveAndActivity() throws Exception {
        SceneNode name = field(SceneEditState.Mode.SINGLE_LINE), notes = field(SceneEditState.Mode.MULTI_LINE);
        require(name != originalName && notes != originalNotes, "非活动面板的旧字段仍然常驻或被复活");
        require(NAME.equals(name.text()) && NOTES.equals(notes.text()) && control("保存项目").enabled(), "保存前未使用独立当前草稿条件");
        input.click("保存项目"); require(hasText("项目名称和备注已保存。"), "正式保存命令未发布成功反馈");
        input.click("活动记录");
        require(!hasEditor() && geometry(window).get(name) == null && geometry(window).get(notes) == null && hasText("已保存：" + NAME),
                "切换活动记录未结束旧编辑面板或没有实际保存活动");
        screenshot("tabs-saved-activity");
        input.click("名称与备注");
        require(NAME.equals(field(SceneEditState.Mode.SINGLE_LINE).text()) && NOTES.equals(field(SceneEditState.Mode.MULTI_LINE).text()),
                "保存后返回原标签丢失业务草稿");
    }
    private void disableAndReorder() throws Exception {
        SceneNode overview = control("项目概览"), notes = control("名称与备注"), history = control("活动记录");
        input.click("活动记录"); SceneNode savedActivity = textNode("已保存：" + NAME);
        input.click("暂停活动入口");
        require(!history.enabled() && geometry(window).get(savedActivity) != null, "禁用当前标签错误清除活动面板");
        focus(overview); key(GLFW_KEY_END, 0); require(focused() == notes && hasEditor(), "方向未跳过当前禁用标签");
        SceneNode field = field(SceneEditState.Mode.SINGLE_LINE);
        input.click("反转标签顺序");
        require(control("项目概览") == overview && control("名称与备注") == notes && control("活动记录") == history
                && geometry(window).get(field) != null, "纯重排重建同键标签或当前面板");
        focus(overview); key(GLFW_KEY_HOME, 0); require(focused() == notes, "重排后Home仍按旧可用顺序选择");
        input.click("启用活动入口"); focus(overview); key(GLFW_KEY_HOME, 0);
        require(focused() == history && hasText("已保存：" + NAME), "外部重新启用后没有使用新顺序与原活动来源");
        screenshot("tabs-reordered-disabled-source");
    }
    private SceneGeometryView.Entry headerViewport() {
        return geometry(window).ordered().stream().filter(e -> e.node.scrollable() && e.node.axis() == SceneNode.Axis.ROW)
                .findFirst().orElseThrow(() -> new AssertionError("缺少标签条正式横向视口"));
    }
    private boolean editor(SceneNode node, SceneEditState.Mode mode) {
        return node != null && node.control() instanceof SceneCompositionTarget
                && read(() -> ((SceneCompositionTarget)node.control()).compositionState().mode()) == mode;
    }
    private SceneNode field(SceneEditState.Mode mode) {
        return geometry(window).ordered().stream().map(e -> e.node).filter(n -> editor(n, mode))
                .findFirst().orElseThrow(() -> new AssertionError("当前面板缺少字段：" + mode));
    }
    private boolean hasEditor() { return geometry(window).ordered().stream().anyMatch(e -> e.node.control() instanceof SceneCompositionTarget); }
    private SceneNode control(String text) {
        return geometry(window).ordered().stream().map(e -> e.node).filter(n -> text.equals(n.text()) && n.clickAction() != null)
                .findFirst().orElseThrow(() -> new AssertionError("缺少正式项目控件：" + text));
    }
    private SceneNode textNode(String text) {
        return geometry(window).ordered().stream().filter(e -> e.text != null && text.equals(e.text.logicalText)).map(e -> e.node)
                .findFirst().orElseThrow(() -> new AssertionError("缺少项目反馈：" + text));
    }
    private boolean hasText(String text) { return geometry(window).ordered().stream().anyMatch(e -> e.text != null && text.equals(e.text.logicalText)); }
    private void focus(SceneNode target) { window.domain.execute(() -> require(window.page.runtime().focusRouter().navigateTo(window.page.runtime().captureMount(target)), "无法聚焦正式项目控件")); window.frame(); }
    private <T> T read(Supplier<T> action) { Object[] value = {null}; window.domain.execute(() -> value[0] = action.get()); @SuppressWarnings("unchecked") T result = (T)value[0]; return result; }
    private SceneNode focused() { return read(() -> window.page.runtime().focusRouter().focused().get()); }
    private void event(int code, int action) { input.key.invoke(window.handle(), code, 0, action, 0); }
    private void enqueueKey(int code, int mods) { input.key.invoke(window.handle(), code, 0, GLFW_PRESS, mods); input.key.invoke(window.handle(), code, 0, GLFW_RELEASE, mods); }
    private void key(int code, int mods) { enqueueKey(code, mods); window.frame(); }
    private void type(String text) { text.codePoints().forEach(cp -> input.character.invoke(window.handle(), cp)); }
    private void screenshot(String name) throws Exception {
        settle(window); window.fonts.beginPresentation(1_000_006);
        try { window.replay(window.lastPlan); } finally { window.fonts.endPresentation(); }
        require(glGetError() == GL_NO_ERROR, "项目标签回放出现GL错误");
        ImageIO.write(DesktopTestReadback.readBack(window), "png", output.resolve(name + ".png").toFile());
    }
}
