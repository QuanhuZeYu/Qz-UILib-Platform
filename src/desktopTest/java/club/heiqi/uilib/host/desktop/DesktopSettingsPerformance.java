package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import club.heiqi.uilib.ui.scene.layout.SceneGeometryView;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 真实设置页基准：ui计入正式资源/输入/绘制及GPU完成，window另计poll/clear/swap；不移动系统鼠标。 */
public final class DesktopSettingsPerformance {
    private static final double UI_BUDGET_MS = 1.0;
    private final List<String> results = new ArrayList<>();
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args.length == 0 ? "settings-performance" : args[0]);
        Files.createDirectories(output);
        System.setProperty("uilib.desktop.visible", "false");
        DesktopSettingsPerformance benchmark = new DesktopSettingsPerformance();
        for (String mode : new String[] {"ui", "window"}) benchmark.run(output, mode);
        String json = "{\"uiBudgetMs\":" + UI_BUDGET_MS + ",\"width\":1440,\"height\":1000,\"vsync\":false,\"samples\":["
                + String.join(",", benchmark.results) + "]}";
        Files.write(output.resolve("settings-performance.json"), json.getBytes(StandardCharsets.UTF_8));
    }
    private void run(Path output, String mode) throws Exception {
        try (DesktopWindow window = DesktopWindow.openSettings(output.resolve(mode + ".yaml"))) {
            glfwSetWindowSize(window.handle(), 1440, 1000); glfwSwapInterval(0); window.frame();
            if (glfwGetWindowAttrib(window.handle(), GLFW_VISIBLE) != GLFW_FALSE) throw new AssertionError("基准窗口必须隐藏");
            if (window.width() != 1440 || window.height() != 1000) throw new AssertionError("实际framebuffer尺寸与基准不符：" + window.width() + "×" + window.height());
            System.out.println("SETTINGS_GPU " + glGetString(GL_RENDERER) + " · " + glGetString(GL_VERSION));
            System.out.println("SETTINGS_BASELINE width=" + window.width() + " height=" + window.height() + " mode=" + mode);
            DesktopTestInput input = new DesktopTestInput(window);
            sample(window, input, mode, "warmup", 0, 1500, false);
            sample(window, input, mode, "empty-window", 0, 750, true);
            sample(window, input, mode, "idle", 0, 1500, false);
            sample(window, input, mode, "hover1", 1, 1500, false);
            sample(window, input, mode, "hover8", 8, 1500, false);
            input.click("管理字体来源");
            sample(window, input, mode, "font-modal-motion", 1, 450, false);
            sample(window, input, mode, "font-modal-hover8", 8, 1000, false);
            SceneNode[] handle = {null}; int[] release = new int[2];
            window.domain.execute(() -> {
                int count = 0; SceneGeometryView geometry = window.page.pipeline().current().geometry;
                for (SceneGeometryView.Entry entry : geometry.ordered())
                    if ("::".equals(entry.node.text())) {
                        if (count++ == 0) handle[0] = entry.node.parent();
                        else { club.heiqi.uilib.ui.scene.layout.LayoutBox second = geometry.get(entry.node.parent()).box;
                            release[0] = second.x + second.width / 2; release[1] = second.y + second.height - 1; break; }
                    }
                if (count < 2) throw new AssertionError("字体排序基准至少需要两行");
            });
            if (handle[0] == null) throw new AssertionError("字体排序把手不存在");
            input.moveTo(handle[0]); input.mouse.invoke(window.handle(), 0, GLFW_PRESS, 0); window.frame();
            sample(window, input, mode, "font-drag1", 1, 1000, false);
            sample(window, input, mode, "font-drag8", 8, 1500, false);
            input.point(release[0], release[1]); window.frame();
            input.mouse.invoke(window.handle(), 0, GLFW_RELEASE, 0); window.frame();
            sample(window, input, mode, "font-reorder-motion", 0, 450, false);
            input.click("完成字体编辑");
            sample(window, input, mode, "modal-close-motion", 1, 450, false);
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("设置页基准产生GL错误");
        }
    }
    private void sample(DesktopWindow window, DesktopTestInput input, String mode, String scene, int moves, int millis, boolean empty) {
        boolean dragging = scene.startsWith("font-drag");
        if (dragging) requireDragOwner(window);
        long[] times = new long[65536]; int frames = 0, changes = 0; Object previous = null;
        long total = 0, until = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < until && frames < times.length) {
            if ("ui".equals(mode) && !empty) { glfwPollEvents(); glClear(GL_COLOR_BUFFER_BIT); }
            long begin = System.nanoTime();
            for (int i = 0; i < moves; i++) input.point(120 + (frames + i) % 120, 140 + (frames + i) % 300);
            if (empty) { glfwPollEvents(); glClear(GL_COLOR_BUFFER_BIT); glfwSwapBuffers(window.handle()); }
            else if ("window".equals(mode)) window.frame();
            else {
                window.fonts.beginPresentation(System.nanoTime());
                try { window.page.frame(); } finally { window.fonts.endPresentation(); }
                // 完成真实GPU工作，不把只入队的速度冒充画面产出速度。
                glFinish();
            }
            long elapsed = System.nanoTime() - begin;
            times[frames++] = elapsed; total += elapsed;
            if (previous != window.lastPlan) changes++;
            previous = window.lastPlan;
        }
        if (dragging) requireDragOwner(window);
        Arrays.sort(times, 0, frames);
        double mean = total / 1e6 / frames, fps = frames * 1e9 / total;
        String record = String.format(Locale.ROOT,
                "{\"mode\":\"%s\",\"scene\":\"%s\",\"frames\":%d,\"movesPerFrame\":%d,\"planChanges\":%d,\"fps\":%.3f,\"meanMs\":%.6f,\"p50Ms\":%.6f,\"p95Ms\":%.6f,\"p99Ms\":%.6f,\"maxMs\":%.6f,\"withinUiBudget\":%s}",
                mode, scene, frames, moves, changes, fps, mean, percentile(times, frames, .5), percentile(times, frames, .95),
                percentile(times, frames, .99), percentile(times, frames, 1), !empty && "ui".equals(mode) && mean <= UI_BUDGET_MS);
        results.add(record); System.out.println("SETTINGS_PERFORMANCE " + record);
    }
    private static void requireDragOwner(DesktopWindow window) {
        window.domain.execute(() -> {
            SceneNode owner = window.page.runtime().pointerRouter().pressedTarget().get();
            if (owner == null || owner.control() == null || !owner.control().capturesPointer())
                throw new AssertionError("字体拖动没有持续有效的正式捕获owner");
        });
    }
    private static double percentile(long[] values, int count, double q) { return values[(int)Math.ceil(count * q) - 1] / 1e6; }
}
