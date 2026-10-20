package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.geometry;

import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.testing.gallery.TestGallery;
import club.heiqi.uilib.ui.scene.control.SceneAppearance;
import club.heiqi.uilib.ui.scene.layout.SceneGeometryView;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.PaintCommand;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

/** 正式广场弹窗的连续呈现帧；捕获顶部遮罩空隙及嵌套退出的真实回归。 */
public final class DesktopDialogVerification extends TestApplication {
    private Path output;
    private final StringBuilder samples = new StringBuilder("phase,frame,maskY,maskBottom,topPixel,lowerPixel,panelY\n");
    public static void main(String[] args) throws Exception { new DesktopDialogVerification().run(args); }
    @Override protected void setup() throws Exception {
        output = Paths.get(arguments().length == 0 ? "dialog-report" : arguments()[0]);
        Files.createDirectories(output);
    }
    @Override protected void runTests() throws Exception {
        scenario("全程固定遮罩、面板位移、嵌套返回和退场释放", this::dialogs);
        System.out.println("DIALOG_DESKTOP sharedGallery=true installedCallbacks=true actualFrontPixels=true stationaryScrim=true");
    }
    @Override protected void teardown() throws Exception {
        Files.write(output.resolve("frames.csv"), samples.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void dialogs() throws Exception {
        try (DesktopWindow window = DesktopWindow.openWithServices(output.resolve("dialogs.yaml"),
                services -> DesktopGallery.createGallery(services, TestGallery.Page.DIALOGS))) {
            glfwSetWindowSize(window.handle(), 1100, 780);
            DesktopTestFrames.finitePresentations(window);
            DesktopTestInput input = new DesktopTestInput(window);
            input.focus.invoke(window.handle(), true); window.frame();
            try (DesktopTestInput.ScriptedCursor ignored = input.scriptedCursor()) {
                SceneNode trigger = button(window, "打开输入框");
                input.click("打开输入框");
                Set<Integer> positions = sample(window, "enter", true);
                require(positions.size() > 1, "弹窗修复丢失了面板进入位移");
                DesktopTestFrames.finitePresentations(window);
                input.click("选择预设名称");
                sample(window, "nested-enter", true);
                key(window, input, GLFW_KEY_ESCAPE);
                require(geometry(window).activeModal() != null, "关闭内层时错误结束外层模态");
                DesktopTestFrames.finitePresentations(window);
                input.click("取消输入");
                require(geometry(window).activeModal() == null, "退出动画延迟了模态输入释放");
                sample(window, "exit", false);
                DesktopTestFrames.finitePresentations(window);
                SceneNode[] focused = {null};
                window.domain.execute(() -> focused[0] = window.page.runtime().focusRouter().focused().get());
                require(focused[0] == trigger, "结束弹窗没有返回原触发焦点");
                require(window.lastPlan.commands().stream().noneMatch(c -> scrim(c, window)), "退场结束后遮罩仍在绘制");
                input.click("打开确认框");
                sample(window, "confirm-enter", true);
                input.click("取消操作");
                sample(window, "confirm-exit", false);
            }
        }
    }
    private Set<Integer> sample(DesktopWindow window, String phase, boolean entering) throws Exception {
        Set<Integer> positions = new HashSet<>();
        long until = System.nanoTime() + 450_000_000L;
        BufferedImage evidence = null;
        String violation = null;
        int frame = 0, visible = 0;
        do {
            SceneGeometryView view = geometry(window);
            SceneGeometryView.Entry mask = view.ordered().stream()
                    .filter(e -> e.node.background() == SceneAppearance.MODAL_SCRIM).findFirst().orElse(null);
            double top = Double.NaN, bottom = Double.NaN;
            for (PaintCommand command : window.lastPlan.commands()) if (scrim(command, window)) {
                top = command.transform.y(command.box.x, command.box.y);
                bottom = command.transform.y(command.box.x, command.box.y + command.box.height);
                if (top > 0.001 || bottom < window.height() - 0.001) violation = "遮罩未覆盖视口：" + phase + " top=" + top + " bottom=" + bottom;
                visible++;
            }
            if (entering && visible > 0 && mask != null && (mask.worldBounds.y != 0 || mask.worldBounds.height != window.height()))
                violation = "遮罩跟随面板移动：" + phase + " y=" + mask.worldBounds.y;
            int y = view.ordered().stream().filter(e -> "取消输入".equals(e.node.text())).map(e -> e.worldBounds.y).findFirst().orElse(-1);
            if (y >= 0) positions.add(y);
            int topPixel = DesktopGalleryEvidence.pixel(window, 4, 0);
            int lowerPixel = DesktopGalleryEvidence.pixel(window, 4, 28);
            if (topPixel != lowerPixel) violation = "遮罩顶部与下方实际像素不一致：" + phase;
            samples.append(phase).append(',').append(frame++).append(',').append(top).append(',').append(bottom)
                    .append(',').append(Integer.toHexString(topPixel)).append(',').append(Integer.toHexString(lowerPixel)).append(',').append(y).append('\n');
            if (evidence == null && (visible > 0 || violation != null)) evidence = DesktopTestReadback.readFront(window);
            if (violation != null) break;
            window.frame();
        } while (System.nanoTime() < until);
        if (evidence != null) ImageIO.write(evidence, "png", output.resolve(phase + ".png").toFile());
        require(violation == null, violation == null ? "" : violation);
        require(visible > 0, "连续帧没有命中真实遮罩绘制：" + phase);
        return positions;
    }
    private static boolean scrim(PaintCommand command, DesktopWindow window) {
        return command.kind == PaintCommand.Kind.RECT && (command.color & 0x00ffffff) == 0
                && command.color >>> 24 != 0 && command.box.width >= window.width() && command.box.height >= window.height();
    }
    private static SceneNode button(DesktopWindow window, String name) {
        return geometry(window).ordered().stream().filter(e -> name.equals(e.node.text()) && e.node.clickAction() != null)
                .findFirst().orElseThrow(() -> new AssertionError("未找到按钮：" + name)).node;
    }
    private static void key(DesktopWindow window, DesktopTestInput input, int key) {
        input.key.invoke(window.handle(), key, 0, GLFW_PRESS, 0);
        input.key.invoke(window.handle(), key, 0, GLFW_RELEASE, 0); window.frame();
    }
}
