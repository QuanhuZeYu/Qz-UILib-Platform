package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static club.heiqi.uilib.host.desktop.DesktopTestFrames.settle;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.geometry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import club.heiqi.uilib.host.embedded.EmbeddedSceneInputs;
import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.testing.gallery.TestGallery;
import club.heiqi.uilib.ui.scene.edit.SceneCompositionTarget;
import club.heiqi.uilib.ui.scene.input.SceneEditEvent;
import club.heiqi.uilib.ui.scene.input.SceneKey;
import club.heiqi.uilib.ui.scene.node.SceneNode;

/** 可见GLFW及正式发行组合验收；生产回调注入与耗尽后原生物理快照分别取证。 */
public final class DesktopCommandVerification extends TestApplication {
    private Path output;

    public static void main(String[] args) throws Exception { new DesktopCommandVerification().run(args); }
    @Override protected void setup() throws Exception {
        output = arguments().length == 0 ? Paths.get("command-report") : Paths.get(arguments()[0]);
        Files.createDirectories(output);
        String visible = System.getProperty("uilib.desktop.visible");
        System.setProperty("uilib.desktop.visible", "true");
        onClose(() -> { if (visible == null) System.clearProperty("uilib.desktop.visible"); else System.setProperty("uilib.desktop.visible", visible); });
    }
    @Override protected void runTests() throws Exception {
        scenario("共享设置保存条件、实际文件与原重载确认", this::settings);
        scenario("嵌入共享邮箱Ctrl+F及同批后续字符", this::mailbox);
        scenario("编辑空操作、只读和真实剪贴板优先", this::editing);
        scenario("模态关闭后的应用与编辑repeat隔离", this::modalRepeat);
        scenario("桌面Space完整UP及离焦取消、Enter仍DOWN", this::spacePhases);
        scenario("桌面原重复条件、小键盘Enter与失焦历史门", this::desktopGates);
        scenario("嵌入旧签名、修饰键、历史门和原导航重复条件", this::embedded);
        scenario("嵌入命名文本键保留UTF-16代理对及命令边界", this::embeddedTextSource);
        Files.write(output.resolve("provenance.txt"), (
                "可见GLFW、真实OpenGL及发行组合；常规场景经借用的生产回调注入，使用既有ScriptedCursor。\n"
                + "GLFW唯一poll耗尽后采集原生物理快照；旧持键及设备历史事实由Embedded场景显式递交。\n"
                + "Embedded场景在同一正式页面管线递交历史事实，不运行Minecraft。组字仅验证中性旧入口，未验OS IME。\n")
                .getBytes(StandardCharsets.UTF_8));
        System.out.println("COMMAND_DESKTOP visibleGLFW=true distribution=true nativeClipboard=true glfwPhysicalSnapshot=true osIme=false renderer=" + glRenderer);
    }
    private String glRenderer;
    private Path config(String name) throws Exception {
        Path path = output.resolve(name + ".yaml"); Files.deleteIfExists(path); return path;
    }
    private static void visible(DesktopWindow w) {
        require(glfwGetWindowAttrib(w.handle(), GLFW_VISIBLE) == GLFW_TRUE, "验收窗口不可见");
    }
    private void settings() throws Exception {
        Path path = config("settings");
        try (DesktopWindow w = DesktopWindow.openSettings(path)) {
            visible(w); glfwSetWindowSize(w.handle(), 1280, 900); focusWindow(w); DesktopTestInput in = new DesktopTestInput(w);
            try (DesktopTestInput.ScriptedCursor cursor = in.scriptedCursor()) {
                byte[] saved = Files.readAllBytes(path);
                require(!button(w, "保存到文件").enabled(), "初始保存按钮条件不是禁用");
                chord(w, in, GLFW_KEY_S, GLFW_MOD_CONTROL); w.frame();
                require(Arrays.equals(saved, Files.readAllBytes(path)), "无更改快捷键写了文件");
                in.click("深色模式"); require(!button(w, "保存到文件").enabled(), "未应用草稿错误启用保存按钮");
                chord(w, in, GLFW_KEY_S, GLFW_MOD_CONTROL); w.frame();
                require(Arrays.equals(saved, Files.readAllBytes(path)) && text(w, "有待应用修改"), "快捷保存替代了应用");
                in.click("应用全部更改"); require(button(w, "保存到文件").enabled(), "已应用未保存按钮未启用");
                chord(w, in, GLFW_KEY_S, GLFW_MOD_CONTROL | GLFW_MOD_ALT); w.frame();
                require(Arrays.equals(saved, Files.readAllBytes(path)), "额外Alt隐式匹配保存");
                chord(w, in, GLFW_KEY_S, GLFW_MOD_CONTROL); settle(w);
                byte[] dark = Files.readAllBytes(path);
                require(!Arrays.equals(saved, dark) && text(w, "已保存") && !button(w, "保存到文件").enabled(), "Ctrl+S未沿共享保存写真实文件");
                screenshot(w, "settings-shortcut-saved");
                in.click("浅色模式"); in.click("应用全部更改"); in.click("保存到文件");
                require(!Arrays.equals(dark, Files.readAllBytes(path)) && text(w, "已保存"), "共享按钮未保存第二次应用");
                in.click("深色模式"); in.click("管理字体来源");
                chord(w, in, GLFW_KEY_R, GLFW_MOD_CONTROL); settle(w);
                require(!text(w, "从文件重新加载？") && text(w, "完成字体编辑"), "Ctrl+R越过当前模态");
                in.click("完成字体编辑");
                chord(w, in, GLFW_KEY_R, GLFW_MOD_CONTROL); settle(w);
                require(text(w, "从文件重新加载？") && !w.shouldClose(), "原Ctrl+R确认缺失：" + geometry(w).ordered().stream().filter(e -> e.text != null).map(e -> e.text.logicalText).collect(java.util.stream.Collectors.joining(" | ")));
                screenshot(w, "settings-reload-confirmation"); in.click("继续编辑");
                require(text(w, "有待应用修改"), "重载取消丢失草稿");
                w.reloadConfiguration(); settle(w);
                require(text(w, "从文件重新加载？"), "公开reloadConfiguration入口未保留确认委托");
                in.click("放弃更改并加载"); settle(w); require(text(w, "已保存"), "重载确认后未恢复文件基线");
                glRenderer = glGetString(GL_RENDERER);
            }
        }
    }
    private void mailbox() throws Exception {
        try (DesktopWindow w = DesktopWindow.openWithServices(config("mailbox"), true,
                services -> DesktopGallery.createGallery(services, TestGallery.Page.MAILBOX))) {
            visible(w); glfwSetWindowSize(w.handle(), 1400, 1000); focusWindow(w); DesktopTestInput in = new DesktopTestInput(w);
            try (DesktopTestInput.ScriptedCursor cursor = in.scriptedCursor()) {
                in.enqueueClick("反向排序"); chord(w, in, GLFW_KEY_F, GLFW_MOD_CONTROL);
                type(w, in, "needle中文"); w.frame();
                SceneNode search = focused(w);
                require(search != null && search.control() instanceof SceneCompositionTarget && "needle中文".equals(search.text()),
                        "Ctrl+F未在同批文字前取得搜索焦点，actual=" + (search == null ? null : search.text()));
                chord(w, in, GLFW_KEY_F, GLFW_MOD_CONTROL); type(w, in, "f/字"); w.frame();
                require(focused(w) == search && "needle中文f/字".equals(search.text()), "搜索命令清查询、全选或裸字符触发命令");
                screenshot(w, "mailbox-search-same-batch");
            }
        }
    }
    private void editing() throws Exception {
        DesktopCommandPage view = new DesktopCommandPage();
        try (DesktopWindow w = DesktopWindow.open(config("editing"), view)) {
            visible(w); focusWindow(w); DesktopTestInput in = new DesktopTestInput(w);
            try (DesktopTestInput.ScriptedCursor cursor = in.scriptedCursor()) {
                in.click(geometry(w, view.field)); w.domain.execute(() -> view.editing.select(0, 0));
                String original = read(w, () -> view.editing.text().get());
                String clipboard = glfwGetClipboardString(w.handle());
                try {
                    glfwSetClipboardString(w.handle(), "clipboard-sentinel");
                    chord(w, in, GLFW_KEY_C, GLFW_MOD_CONTROL); chord(w, in, GLFW_KEY_Z, GLFW_MOD_CONTROL); w.frame();
                    require(read(w, () -> view.pageEdits.get()) == 0 && original.equals(read(w, () -> view.editing.text().get())), "空复制或空撤销误触页面命令");
                    w.domain.execute(() -> view.readOnly.set(true));
                    chord(w, in, GLFW_KEY_A, GLFW_MOD_CONTROL); chord(w, in, GLFW_KEY_X, GLFW_MOD_CONTROL);
                    chord(w, in, GLFW_KEY_V, GLFW_MOD_CONTROL); w.frame();
                    require(read(w, () -> view.pageEdits.get()) == 0 && original.equals(read(w, () -> view.editing.text().get())), "只读修改未被编辑消费");
                    w.domain.execute(() -> view.readOnly.set(false));
                    chord(w, in, GLFW_KEY_C, GLFW_MOD_CONTROL); w.frame();
                    require(original.equals(glfwGetClipboardString(w.handle())), "中性COPY未经过真实GLFW剪贴板");
                    chord(w, in, GLFW_KEY_X, GLFW_MOD_CONTROL); w.frame();
                    require(read(w, () -> view.editing.text().get()).isEmpty(), "中性CUT未编辑草稿");
                    chord(w, in, GLFW_KEY_V, GLFW_MOD_CONTROL); w.frame();
                    require(original.equals(read(w, () -> view.editing.text().get())) && read(w, () -> view.pageEdits.get()) == 0, "中性PASTE没有恢复原文或触发页面命令");
                    w.page.edit(SceneEditEvent.composition("预编辑", true)); chord(w, in, GLFW_KEY_S, GLFW_MOD_CONTROL); w.frame();
                    require(read(w, () -> view.actions.get()) == 0 && focused(w) == view.field, "中性旧组字联系被应用命令抢走");
                    w.page.edit(SceneEditEvent.composition("", false)); w.frame();
                    screenshot(w, "editing-priority");
                } finally { glfwSetClipboardString(w.handle(), clipboard == null ? "" : clipboard); }
            }
        }
    }
    private void modalRepeat() throws Exception {
        DesktopCommandPage view = new DesktopCommandPage();
        try (DesktopWindow w = DesktopWindow.open(config("modal"), view)) {
            focusWindow(w); DesktopTestInput in = new DesktopTestInput(w);
            try (DesktopTestInput.ScriptedCursor cursor = in.scriptedCursor()) {
                in.click(geometry(w, view.field)); String original = read(w, () -> view.editing.text().get());
                in.click("打开命令模态"); require(focused(w) == view.modalButton, "模态未取得原输入范围");
                String clipboard = glfwGetClipboardString(w.handle());
                try {
                    glfwSetClipboardString(w.handle(), "禁止粘贴到外层");
                    key(w, in, GLFW_KEY_V, GLFW_PRESS, GLFW_MOD_CONTROL);
                    key(w, in, GLFW_KEY_V, GLFW_REPEAT, GLFW_MOD_CONTROL);
                    key(w, in, GLFW_KEY_V, GLFW_REPEAT, GLFW_MOD_CONTROL);
                    key(w, in, GLFW_KEY_V, GLFW_RELEASE, GLFW_MOD_CONTROL); w.frame();
                    require(read(w, () -> view.modalActions.get()) == 1 && !read(w, () -> view.modal.get())
                            && read(w, () -> view.pageEdits.get()) == 0 && original.equals(read(w, () -> view.editing.text().get())),
                            "模态应用V关闭后repeat进入外层编辑或命令");
                    in.click("打开命令模态"); key(w, in, GLFW_KEY_S, GLFW_PRESS, GLFW_MOD_CONTROL);
                    key(w, in, GLFW_KEY_S, GLFW_REPEAT, GLFW_MOD_CONTROL);
                    key(w, in, GLFW_KEY_S, GLFW_RELEASE, GLFW_MOD_CONTROL); w.frame();
                    require(read(w, () -> view.modalActions.get()) == 2 && read(w, () -> view.actions.get()) == 0, "同键repeat穿透到外层应用命令");
                    screenshot(w, "modal-repeat-isolated");
                } finally { glfwSetClipboardString(w.handle(), clipboard == null ? "" : clipboard); }
            }
        }
    }
    private void spacePhases() throws Exception {
        DesktopCommandPage view = new DesktopCommandPage();
        try (DesktopWindow w = DesktopWindow.open(config("space-phases"), view)) {
            focusWindow(w); DesktopTestInput in = new DesktopTestInput(w);
            try (DesktopTestInput.ScriptedCursor cursor = in.scriptedCursor()) {
                in.click(geometry(w, view.button)); int before = read(w, () -> view.activations.get());
                // 正式S命令在同批中观察DOWN和repeat之后的计数，不改变当前焦点。
                key(w, in, GLFW_KEY_SPACE, GLFW_PRESS, 0); chord(w, in, GLFW_KEY_S, GLFW_MOD_CONTROL);
                key(w, in, GLFW_KEY_SPACE, GLFW_REPEAT, 0); chord(w, in, GLFW_KEY_S, GLFW_MOD_CONTROL);
                key(w, in, GLFW_KEY_SPACE, GLFW_RELEASE, 0); key(w, in, GLFW_KEY_SPACE, GLFW_RELEASE, 0); w.frame();
                require(read(w, () -> view.activationSnapshots).equals(Arrays.asList(before, before))
                        && read(w, () -> view.activations.get()) == before + 1,
                        "Space DOWN/repeat执行或合法UP未单次执行");
                int accepted = before + 1;
                key(w, in, GLFW_KEY_SPACE, GLFW_PRESS, 0); chord(w, in, GLFW_KEY_TAB, 0);
                key(w, in, GLFW_KEY_SPACE, GLFW_RELEASE, 0); w.frame();
                require(focused(w) == view.second && read(w, () -> view.activations.get()) == accepted,
                        "Tab离开原目标后Space UP仍激活");
                chord(w, in, GLFW_KEY_TAB, GLFW_MOD_SHIFT); w.frame();
                key(w, in, GLFW_KEY_SPACE, GLFW_PRESS, 0); chord(w, in, GLFW_KEY_TAB, 0);
                chord(w, in, GLFW_KEY_TAB, GLFW_MOD_SHIFT); key(w, in, GLFW_KEY_SPACE, GLFW_RELEASE, 0); w.frame();
                require(focused(w) == view.button && read(w, () -> view.activations.get()) == accepted,
                        "离开再返回原目标复活Space按压");
                key(w, in, GLFW_KEY_SPACE, GLFW_PRESS, 0); in.focus.invoke(w.handle(), false);
                in.focus.invoke(w.handle(), true); key(w, in, GLFW_KEY_SPACE, GLFW_RELEASE, 0); w.frame();
                require(read(w, () -> view.activations.get()) == accepted, "失焦取消后的Space UP仍激活");
                in.click(geometry(w, view.button)); int fresh = read(w, () -> view.activations.get());
                chord(w, in, GLFW_KEY_SPACE, 0); w.frame();
                require(read(w, () -> view.activations.get()) == fresh + 1, "取消后新的完整Space按压未恢复");
                w.domain.execute(view.activationSnapshots::clear); int enterBefore = read(w, () -> view.activations.get());
                key(w, in, GLFW_KEY_ENTER, GLFW_PRESS, 0); chord(w, in, GLFW_KEY_S, GLFW_MOD_CONTROL);
                key(w, in, GLFW_KEY_ENTER, GLFW_REPEAT, 0); key(w, in, GLFW_KEY_ENTER, GLFW_RELEASE, 0); w.frame();
                require(read(w, () -> view.activationSnapshots).equals(Collections.singletonList(enterBefore + 1))
                        && read(w, () -> view.activations.get()) == enterBefore + 1,
                        "Enter未维持DOWN单次激活");
                screenshot(w, "space-complete-up-cancellation");
            }
        }
    }
    private void desktopGates() throws Exception {
        DesktopCommandPage view = new DesktopCommandPage();
        try (DesktopWindow w = DesktopWindow.open(config("desktop-gates"), view)) {
            DesktopTestInput in = new DesktopTestInput(w);
            key(w, in, GLFW_KEY_S, GLFW_PRESS, GLFW_MOD_CONTROL); w.frame();
            require(read(w, () -> view.actions.get()) == 0, "开屏未耗尽历史就把PRESS当fresh");
            key(w, in, GLFW_KEY_S, GLFW_REPEAT, GLFW_MOD_CONTROL); key(w, in, GLFW_KEY_S, GLFW_RELEASE, GLFW_MOD_CONTROL); w.frame();
            require(read(w, () -> view.actions.get()) == 0, "开屏旧repeat激活命令");
            focusWindow(w);
            try (DesktopTestInput.ScriptedCursor cursor = in.scriptedCursor()) {
                in.click(geometry(w, view.button));
                key(w, in, GLFW_KEY_TAB, GLFW_PRESS, 0); key(w, in, GLFW_KEY_TAB, GLFW_REPEAT, 0);
                key(w, in, GLFW_KEY_TAB, GLFW_RELEASE, 0); w.frame();
                require(focused(w) == view.second, "桌面Tab重复改变了原只响应fresh条件");
                in.click(geometry(w, view.button)); int before = read(w, () -> view.activations.get());
                key(w, in, GLFW_KEY_ENTER, GLFW_PRESS, 0); key(w, in, GLFW_KEY_ENTER, GLFW_PRESS, 0);
                key(w, in, GLFW_KEY_ENTER, GLFW_REPEAT, 0); key(w, in, GLFW_KEY_KP_ENTER, GLFW_PRESS, 0);
                key(w, in, GLFW_KEY_KP_ENTER, GLFW_REPEAT, 0); w.frame();
                require(read(w, () -> view.activations.get()) == before + 2, "Enter物理段重复或小键盘Enter未独立");
                key(w, in, GLFW_KEY_ENTER, GLFW_RELEASE, 0); key(w, in, GLFW_KEY_KP_ENTER, GLFW_RELEASE, 0);
                key(w, in, GLFW_KEY_SPACE, GLFW_PRESS, 0); key(w, in, GLFW_KEY_SPACE, GLFW_REPEAT, 0);
                key(w, in, GLFW_KEY_SPACE, GLFW_RELEASE, 0); w.frame();
                require(read(w, () -> view.activations.get()) == before + 3, "Space重复激活");
                in.click("打开命令模态"); key(w, in, GLFW_KEY_ESCAPE, GLFW_PRESS, 0);
                key(w, in, GLFW_KEY_ESCAPE, GLFW_REPEAT, 0); key(w, in, GLFW_KEY_ESCAPE, GLFW_RELEASE, 0); w.frame();
                require(read(w, () -> view.escapes.get()) == 1 && !w.shouldClose(), "ESC repeat越过模态到页面");
                in.focus.invoke(w.handle(), false); in.focus.invoke(w.handle(), true);
                key(w, in, GLFW_KEY_S, GLFW_PRESS, GLFW_MOD_CONTROL); w.frame();
                require(read(w, () -> view.actions.get()) == 0, "失焦恢复未耗尽历史就接受DOWN");
                key(w, in, GLFW_KEY_S, GLFW_RELEASE, GLFW_MOD_CONTROL); chord(w, in, GLFW_KEY_S, GLFW_MOD_CONTROL); w.frame();
                require(read(w, () -> view.actions.get()) == 1, "真实释放事实后新命令未恢复");
                in.click(geometry(w, view.field)); w.domain.execute(() -> view.editing.select(20, 20));
                int caret = read(w, () -> view.editing.snapshot().get().caret);
                chord(w, in, GLFW_KEY_UP, GLFW_MOD_CONTROL); w.frame();
                require(read(w, () -> view.editing.snapshot().get().caret) < caret && focused(w) == view.field,
                        "桌面Primary+UP未统一沿Core原UP编辑命令");
                for (int code : new int[] {GLFW_KEY_DOWN, GLFW_KEY_PAGE_UP, GLFW_KEY_PAGE_DOWN}) chord(w, in, code, GLFW_MOD_CONTROL);
                w.frame(); require(focused(w) == view.field, "Primary方向编辑被误投页级导航");
                chord(w, in, GLFW_KEY_ENTER, GLFW_MOD_CONTROL); w.frame();
                require(read(w, () -> view.submissions.get()) == 1, "Ctrl+Enter显式提交未保留");
            }
        }
    }
    private void embedded() throws Exception {
        String[] original = {"OTHER", "TAB", "ENTER", "KEYPAD_ENTER", "SPACE", "ESCAPE", "LEFT_SHIFT", "RIGHT_SHIFT",
                "LEFT", "RIGHT", "UP", "DOWN", "HOME", "END", "PAGE_UP", "PAGE_DOWN", "BACKSPACE", "DELETE", "A", "C", "X", "V", "Z", "Y",
                "B", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "W"};
        for (int i = 0; i < original.length; i++) require(EmbeddedSceneInputs.Key.valueOf(original[i]).ordinal() == i, "旧Key顺序改变：" + original[i]);
        for (SceneKey key : SceneKey.values()) EmbeddedSceneInputs.Key.valueOf(key.name());
        DesktopCommandPage view = new DesktopCommandPage();
        try (DesktopWindow w = DesktopWindow.open(config("embedded"), view)) {
            focusWindow(w); DesktopTestInput in = new DesktopTestInput(w);
            try (DesktopTestInput.ScriptedCursor cursor = in.scriptedCursor()) {
                SceneEditEvent.Clipboard clipboard = new SceneEditEvent.Clipboard() {
                    public String read() { String text = glfwGetClipboardString(w.handle()); return text == null ? "" : text; }
                    public void write(String text) { glfwSetClipboardString(w.handle(), text); }
                };
                EmbeddedSceneInputs keys = new EmbeddedSceneInputs(w.page, clipboard, () -> { throw new AssertionError("嵌入ESC误穿透宿主"); });
                keys.availability(true); keys.key(83, EmbeddedSceneInputs.Key.S, true, false, true, false); w.frame();
                require(read(w, () -> view.actions.get()) == 0, "嵌入旧开屏DOWN提前执行");
                keys.historyDrained(Collections.singleton(83), Collections.emptySet(), true);
                keys.key(83, EmbeddedSceneInputs.Key.S, true, true, true, false); w.frame();
                require(read(w, () -> view.actions.get()) == 0, "旧消费者special不含字母时漏过持键");
                keys.key(83, EmbeddedSceneInputs.Key.S, false, false, true, false);
                keys.historyDrained(Collections.emptySet(), Collections.emptySet(), true);
                keys.key(83, EmbeddedSceneInputs.Key.S, true, false, true, false);
                keys.key(83, EmbeddedSceneInputs.Key.S, true, false, true, false); w.frame();
                require(read(w, () -> view.actions.get()) == 1, "旧六参签名无法执行或重复PRESS重发");
                keys.availability(false); keys.availability(true);
                keys.key(83, EmbeddedSceneInputs.Key.S, true, true, true, false);
                keys.historyDrained(Collections.singleton(83), Collections.emptySet(), true);
                keys.key(83, EmbeddedSceneInputs.Key.S, true, false, true, false); w.frame();
                require(read(w, () -> view.actions.get()) == 1, "失焦持键未释放可执行");
                keys.key(83, EmbeddedSceneInputs.Key.S, false, false, true, false);
                keys.historyDrained(Collections.emptySet(), Collections.emptySet(), true);
                for (boolean extra : new boolean[] {false, true}) {
                    keys.key(83, EmbeddedSceneInputs.Key.S, true, false, true, false, !extra, extra);
                    keys.key(83, EmbeddedSceneInputs.Key.S, false, false, true, false, !extra, extra);
                }
                keys.key(83, EmbeddedSceneInputs.Key.S, true, false, true, false, false, false);
                keys.key(83, EmbeddedSceneInputs.Key.S, false, false, true, false, false, false); w.frame();
                require(read(w, () -> view.actions.get()) == 2, "八参Alt/extra精确条件或恢复fresh失败");
                in.click(geometry(w, view.button));
                keys.key(9, EmbeddedSceneInputs.Key.TAB, true, false, false, false);
                keys.key(9, EmbeddedSceneInputs.Key.TAB, true, true, false, false);
                keys.key(9, EmbeddedSceneInputs.Key.TAB, false, false, false, false); w.frame();
                require(focused(w) == view.field, "嵌入原Tab合法repeat不再导航");
                w.domain.execute(() -> view.editing.select(20, 20)); int caret = read(w, () -> view.editing.snapshot().get().caret);
                keys.key(38, EmbeddedSceneInputs.Key.UP, true, false, true, false);
                keys.key(38, EmbeddedSceneInputs.Key.UP, false, false, true, false); w.frame();
                require(read(w, () -> view.editing.snapshot().get().caret) < caret, "嵌入旧Ctrl+UP普通编辑语义丢失");
                keys.availability(false); keys.availability(true);
                keys.historyDrained(Collections.singleton(156), Collections.singleton(EmbeddedSceneInputs.Key.KEYPAD_ENTER), true);
                w.frame(); in.click(geometry(w, view.button)); int before = read(w, () -> view.activations.get());
                keys.key(156, EmbeddedSceneInputs.Key.KEYPAD_ENTER, true, false, false, false);
                keys.key(28, EmbeddedSceneInputs.Key.ENTER, true, false, false, false); w.frame();
                require(read(w, () -> view.activations.get()) == before + 1, "小键盘旧持键未隔离或阻止独立Enter");
                keys.key(156, EmbeddedSceneInputs.Key.KEYPAD_ENTER, false, false, false, false);
                keys.key(28, EmbeddedSceneInputs.Key.ENTER, false, false, false, false);
                keys.historyDrained(Collections.emptySet(), Collections.emptySet(), true);
                keys.key(156, EmbeddedSceneInputs.Key.KEYPAD_ENTER, true, false, false, false);
                keys.key(156, EmbeddedSceneInputs.Key.KEYPAD_ENTER, true, true, false, false);
                keys.key(28, EmbeddedSceneInputs.Key.ENTER, true, false, false, false);
                keys.key(28, EmbeddedSceneInputs.Key.ENTER, true, true, false, false); w.frame();
                require(read(w, () -> view.activations.get()) == before + 3, "嵌入Enter与小键盘Enter共享物理门或repeat激活");
                keys.key(156, EmbeddedSceneInputs.Key.KEYPAD_ENTER, false, false, false, false);
                keys.key(28, EmbeddedSceneInputs.Key.ENTER, false, false, false, false);
                keys.historyDrained(Collections.emptySet(), Collections.emptySet(), true);
                w.domain.execute(view.activationSnapshots::clear); int spaceBefore = read(w, () -> view.activations.get());
                keys.key(57, EmbeddedSceneInputs.Key.SPACE, true, false, false, false);
                embeddedChord(keys, 31, EmbeddedSceneInputs.Key.S, true);
                keys.key(57, EmbeddedSceneInputs.Key.SPACE, true, true, false, false);
                embeddedChord(keys, 31, EmbeddedSceneInputs.Key.S, true);
                keys.key(57, EmbeddedSceneInputs.Key.SPACE, false, false, false, false);
                keys.key(57, EmbeddedSceneInputs.Key.SPACE, false, false, false, false); w.frame();
                require(read(w, () -> view.activationSnapshots).equals(Arrays.asList(spaceBefore, spaceBefore))
                        && read(w, () -> view.activations.get()) == spaceBefore + 1,
                        "嵌入Space DOWN/repeat执行或UP未沿合法起手单次执行");
                int complete = spaceBefore + 1;
                keys.key(57, EmbeddedSceneInputs.Key.SPACE, true, false, false, false);
                embeddedChord(keys, 15, EmbeddedSceneInputs.Key.TAB, false);
                keys.key(42, EmbeddedSceneInputs.Key.LEFT_SHIFT, true, false, false, true);
                keys.key(15, EmbeddedSceneInputs.Key.TAB, true, false, false, true);
                keys.key(15, EmbeddedSceneInputs.Key.TAB, false, false, false, true);
                keys.key(42, EmbeddedSceneInputs.Key.LEFT_SHIFT, false, false, false, false);
                keys.key(57, EmbeddedSceneInputs.Key.SPACE, false, false, false, false); w.frame();
                require(focused(w) == view.button && read(w, () -> view.activations.get()) == complete,
                        "嵌入Space离组回组后UP复活原按压");
                keys.key(57, EmbeddedSceneInputs.Key.SPACE, true, false, false, false);
                keys.availability(false); keys.availability(true);
                keys.key(57, EmbeddedSceneInputs.Key.SPACE, false, false, false, false); w.frame();
                require(read(w, () -> view.activations.get()) == complete, "嵌入失焦取消后Space UP仍激活");
                keys.historyDrained(Collections.emptySet(), Collections.emptySet(), true);
                screenshot(w, "embedded-compatibility");
            }
        }
    }
    private void embeddedTextSource() throws Exception {
        DesktopCommandPage view = new DesktopCommandPage();
        try (DesktopWindow w = DesktopWindow.open(config("embedded-text-source"), view)) {
            focusWindow(w); DesktopTestInput in = new DesktopTestInput(w);
            try (DesktopTestInput.ScriptedCursor cursor = in.scriptedCursor()) {
                EmbeddedSceneInputs keys = new EmbeddedSceneInputs(w.page, new SceneEditEvent.Clipboard() {
                    public String read() { return ""; }
                    public void write(String text) { }
                }, () -> { throw new AssertionError("文本来源回归误触宿主退出"); });
                keys.availability(true); keys.historyDrained(Collections.emptySet(), Collections.emptySet(), true);
                in.click(geometry(w, view.field)); w.domain.execute(() -> view.editing.reset(""));
                EmbeddedSceneInputs.Key[] names = {EmbeddedSceneInputs.Key.A, EmbeddedSceneInputs.Key.DIGIT_1,
                        EmbeddedSceneInputs.Key.SLASH, EmbeddedSceneInputs.Key.PERIOD, EmbeddedSceneInputs.Key.SPACE};
                int[] physical = {30, 2, 53, 52, 57}; StringBuilder expected = new StringBuilder();
                for (int i = 0; i < names.length; i++) {
                    keys.key(physical[i], names[i], true, false, false, false);
                    keys.character('\uD83D');
                    // 原生UTF-16事件可以各带一次DOWN，第二段不要求消费者伪造release。
                    keys.key(physical[i], names[i], true, false, false, false);
                    keys.character('\uDE80');
                    keys.key(physical[i], names[i], false, false, false, false); expected.append("🚀");
                }
                keys.text("中文"); expected.append("中文"); w.frame();
                require(expected.toString().equals(read(w, () -> view.editing.text().get())),
                        "同命名文本键的高低代理被截断，或原整段文字入口改变");
                String original = expected.toString();
                keys.key(2, EmbeddedSceneInputs.Key.DIGIT_1, true, false, false, false);
                keys.character('\uD83D'); keys.key(2, EmbeddedSceneInputs.Key.DIGIT_1, false, false, false, false);
                keys.key(53, EmbeddedSceneInputs.Key.SLASH, true, false, false, false);
                keys.character('\uDE80'); keys.key(53, EmbeddedSceneInputs.Key.SLASH, false, false, false, false); w.frame();
                require((original + "🚀").equals(read(w, () -> view.editing.text().get())), "普通文本键UP或下一文本键DOWN截断原字符来源");
                w.domain.execute(() -> view.editing.reset(""));
                keys.key(30, EmbeddedSceneInputs.Key.A, true, false, false, false); keys.character('\uD83D');
                embeddedChord(keys, 203, EmbeddedSceneInputs.Key.LEFT, false);
                keys.character('\uDE80'); keys.key(30, EmbeddedSceneInputs.Key.A, false, false, false, false); w.frame();
                require(read(w, () -> view.editing.text().get()).isEmpty(), "导航命令边界未结束旧高代理字符");
                keys.key(30, EmbeddedSceneInputs.Key.A, true, false, false, false); keys.character('\uD83D');
                keys.key(31, EmbeddedSceneInputs.Key.S, true, false, true, false);
                keys.key(31, EmbeddedSceneInputs.Key.S, false, false, false, false);
                keys.character('\uDE80'); keys.key(30, EmbeddedSceneInputs.Key.A, false, false, false, false); w.frame();
                require(read(w, () -> view.editing.text().get()).isEmpty(), "修饰命令后的低代理错误续接旧文本");
                screenshot(w, "embedded-text-source");
            }
        }
    }
    private static void embeddedChord(EmbeddedSceneInputs keys, int physical, EmbeddedSceneInputs.Key key, boolean control) {
        keys.key(physical, key, true, false, control, false); keys.key(physical, key, false, false, control, false);
    }
    private static void focusWindow(DesktopWindow w) throws Exception {
        glfwFocusWindow(w.handle());
        await(w, () -> glfwGetWindowAttrib(w.handle(), GLFW_FOCUSED) == GLFW_TRUE, "真实验收窗口未取得输入焦点");
        settle(w);
    }
    private static void await(DesktopWindow w, BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { w.frame(); Thread.sleep(5); }
        require(condition.getAsBoolean(), message);
    }
    private static <T> T read(DesktopWindow w, Supplier<T> action) {
        Object[] result = {null}; w.domain.execute(() -> result[0] = action.get());
        @SuppressWarnings("unchecked") T value = (T) result[0]; return value;
    }
    private static SceneNode focused(DesktopWindow w) { return read(w, () -> w.page.runtime().focusRouter().focused().get()); }
    private static SceneNode button(DesktopWindow w, String name) {
        return geometry(w).ordered().stream().map(e -> e.node).filter(n -> name.equals(n.text()) && n.clickAction() != null)
                .findFirst().orElseThrow(() -> new AssertionError("缺少按钮：" + name));
    }
    private static boolean text(DesktopWindow w, String text) {
        return geometry(w).ordered().stream().anyMatch(e -> e.text != null && text.equals(e.text.logicalText));
    }
    private static void key(DesktopWindow w, DesktopTestInput in, int key, int action, int modifiers) { in.key.invoke(w.handle(), key, 0, action, modifiers); }
    private static void chord(DesktopWindow w, DesktopTestInput in, int key, int modifiers) {
        key(w, in, key, GLFW_PRESS, modifiers); key(w, in, key, GLFW_RELEASE, modifiers);
    }
    private static void type(DesktopWindow w, DesktopTestInput in, String text) { text.codePoints().forEach(cp -> in.character.invoke(w.handle(), cp)); }
    private void screenshot(DesktopWindow w, String name) throws Exception {
        settle(w); w.fonts.beginPresentation(1_000_001);
        try { w.replay(w.lastPlan); } finally { w.fonts.endPresentation(); }
        require(glGetError() == GL_NO_ERROR, "命令键实际画面有GL错误");
        ImageIO.write(DesktopTestReadback.readBack(w), "png", output.resolve(name + ".png").toFile());
    }
}
