package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static club.heiqi.uilib.host.desktop.DesktopTestFrames.settle;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.geometry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.testing.gallery.ResourceTreePage;
import club.heiqi.uilib.ui.scene.edit.SceneCompositionTarget;
import club.heiqi.uilib.ui.scene.node.SceneNode;

/** 正式项目目录的可见桌面回归；只使用公开页面和生产回调，运行须等待桌面可用。 */
public final class DesktopTreeVerification extends TestApplication {
    private Path output;
    private DesktopWindow window;
    private DesktopTestInput input;
    public static void main(String[] args) throws Exception { new DesktopTreeVerification().run(args); }
    @Override protected void setup() throws Exception {
        output = Paths.get(arguments().length == 0 ? "tree-report" : arguments()[0]); Files.createDirectories(output);
    }
    @Override protected void runTests() throws Exception {
        Path config = output.resolve("project-tree.yaml"); Files.deleteIfExists(config);
        try (DesktopWindow w = DesktopWindow.openWithServices(config, true, services -> ctx ->
                ctx.mountOwned(ResourceTreePage.create(ctx, ctx.theme(), () -> {})))) {
            window = w; glfwSetWindowSize(w.handle(), 1120, 860); settle(w);
            require(glfwGetWindowAttrib(w.handle(), GLFW_VISIBLE) == GLFW_TRUE, "树验收必须使用可见窗口");
            input = new DesktopTestInput(w); input.focus.invoke(w.handle(), true); w.frame();
            try (DesktopTestInput.ScriptedCursor cursor = input.scriptedCursor()) {
                scenario("普通主节点展开折叠和父子方向", this::parentChild);
                scenario("名称字段光标和外部折叠后的独立草稿", this::editingAndCollapse);
                scenario("主动作选择反馈及同级来源重排", this::selectionAndReorder);
                System.out.println("TREE_DESKTOP visibleGLFW=true sharedProjectPage=true installedCallbacks=true independentDraft=true realFilesystem=false");
            }
        }
    }
    private void parentChild() throws Exception {
        SceneNode root = main("工作区资源"); focus(root);
        key(GLFW_KEY_LEFT, 0); require(focused() == root && !hasText("界面"), "Left未折叠普通Node主根或抢焦点");
        key(GLFW_KEY_RIGHT, 0); require(focused() == root && hasText("界面"), "Right未展开普通Node主根");
        key(GLFW_KEY_RIGHT, 0); SceneNode folder = main("界面");
        require(focused() == folder, "已展开根的Right未到达首个直接子项");
        key(GLFW_KEY_RIGHT, 0); require(focused() == main("界面资源1"), "目录Right没有进入首个资源主目标");
        key(GLFW_KEY_LEFT, 0); require(focused() == folder, "资源Left没有返回父项");
        key(GLFW_KEY_LEFT, 0); require(focused() == folder && !hasText("界面资源1"), "目录Left未折叠后代");
        key(GLFW_KEY_RIGHT, 0); require(focused() == folder && hasText("界面资源1"), "目录Right未重新展开");
        require(!hasText("已打开：界面资源1"), "父子方向隐式触发资源主动作");
        screenshot("tree-parent-child");
    }
    private void editingAndCollapse() throws Exception {
        input.click("编辑名称"); SceneNode resource = main("界面资源1"), field = field(resource); focus(field);
        key(GLFW_KEY_A, GLFW_MOD_CONTROL); type("未确认名称草稿"); window.frame();
        require("未确认名称草稿".equals(field.text()), "名称字符没有进入独立草稿");
        key(GLFW_KEY_HOME, 0); key(GLFW_KEY_LEFT, 0);
        require(focused() == field && caret(field) == 0, "树Left接走名称字段端点输入");
        key(GLFW_KEY_RIGHT, 0);
        require(focused() == field && caret(field) == 1 && hasText("界面资源1"), "树Right抢走字段光标或隐式提交名称");
        input.click("折叠全部目录");
        require(!hasText("界面资源1") && geometry(window).get(field) == null && focused() != field,
                "外部折叠没有结束后代旧呈现和焦点联系");
        input.click("展开全部目录");
        SceneNode restoredResource = main("界面资源1"), restored = field(restoredResource);
        require(restored != field && "未确认名称草稿".equals(restored.text()), "外部展开复活旧挂载或丢失独立草稿");
        focus(restored); key(GLFW_KEY_ENTER, 0);
        require(hasText("未确认名称草稿") && hasText("名称已更新：未确认名称草稿"), "字段Enter未通过原提交更新业务名称");
        glfwSetWindowSize(window.handle(), 700, 820); settle(window);
        require(field(main("未确认名称草稿")) == restored, "缩窗重建了当前名称字段");
        screenshot("tree-restored-name-draft");
    }
    private void selectionAndReorder() throws Exception {
        SceneNode resource = main("未确认名称草稿"); focus(resource); key(GLFW_KEY_ENTER, 0);
        require(hasText("已打开：未确认名称草稿"), "普通主节点Enter没有发布实际打开反馈");
        input.click("反转同级顺序");
        require(hasText("已打开：未确认名称草稿"), "重排清除了独立选择反馈");
        input.click("折叠全部目录"); input.click("展开全部目录");
        SceneNode root = main("工作区资源"); focus(root); key(GLFW_KEY_RIGHT, 0);
        require(focused() == main("配置"), "反转同级来源后父子方向仍使用旧首项");
        require(hasText("已打开：未确认名称草稿"), "展开或父子移动隐式改变选择反馈");
        screenshot("tree-reordered-selection");
    }
    private SceneNode main(String name) {
        SceneNode label = geometry(window).ordered().stream().map(e -> e.node).filter(n -> name.equals(n.text()))
                .findFirst().orElseThrow(() -> new AssertionError("缺少正式项目项：" + name));
        for (SceneNode at = label; at != null; at = at.parent())
            if (at.focusable() && at.clickFocus() && at.clickAction() != null) return at;
        throw new AssertionError("正式项目项没有主交互目标：" + name);
    }
    private SceneNode field(SceneNode resource) {
        return geometry(window).ordered().stream().map(e -> e.node)
                .filter(n -> n.control() instanceof SceneCompositionTarget && within(n, resource))
                .findFirst().orElseThrow(() -> new AssertionError("资源没有当前名称编辑器"));
    }
    private static boolean within(SceneNode node, SceneNode ancestor) { for (SceneNode at = node; at != null; at = at.parent()) if (at == ancestor) return true; return false; }
    private int caret(SceneNode field) { return read(() -> ((SceneCompositionTarget)field.control()).compositionState().snapshot().get().caret); }
    private void focus(SceneNode target) { window.domain.execute(() -> require(window.page.runtime().focusRouter().navigateTo(window.page.runtime().captureMount(target)), "无法聚焦正式树目标")); window.frame(); }
    private <T> T read(Supplier<T> action) { Object[] value = {null}; window.domain.execute(() -> value[0] = action.get()); @SuppressWarnings("unchecked") T result = (T)value[0]; return result; }
    private SceneNode focused() { return read(() -> window.page.runtime().focusRouter().focused().get()); }
    private boolean hasText(String text) { return geometry(window).ordered().stream().anyMatch(e -> e.text != null && text.equals(e.text.logicalText)); }
    private void key(int code, int mods) { input.key.invoke(window.handle(), code, 0, GLFW_PRESS, mods); input.key.invoke(window.handle(), code, 0, GLFW_RELEASE, mods); window.frame(); }
    private void type(String text) { text.codePoints().forEach(cp -> input.character.invoke(window.handle(), cp)); }
    private void screenshot(String name) throws Exception {
        settle(window); window.fonts.beginPresentation(1_000_004);
        try { window.replay(window.lastPlan); } finally { window.fonts.endPresentation(); }
        require(glGetError() == GL_NO_ERROR, "树页面回放出现GL错误");
        ImageIO.write(DesktopTestReadback.readBack(window), "png", output.resolve(name + ".png").toFile());
    }
}
