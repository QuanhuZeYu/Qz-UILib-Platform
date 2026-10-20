package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import club.heiqi.uilib.testing.gallery.PerformanceReport;
import club.heiqi.uilib.testing.gallery.PerformanceSamples;
import club.heiqi.uilib.ui.scene.host.ScenePageSession;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.SceneGeometryView;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 真实设置页基准：ui计入正式回调、字体帧、page.frame/replay及GPU完成；window计完整frame而不glFinish。 */
public final class DesktopSettingsPerformance {
    private static final double UI_BUDGET_MS = 1.0;
    private static final int SAMPLE_CAPACITY = 65536;
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final List<Map<String, Object>> runs = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args.length == 0 ? "settings-performance" : args[0]);
        Files.createDirectories(output);
        String visible = System.getProperty("uilib.desktop.visible");
        System.setProperty("uilib.desktop.visible", "false");
        DesktopSettingsPerformance benchmark = new DesktopSettingsPerformance();
        Map<String, Object> report =
                fields(
                        "runId",
                        UUID.randomUUID().toString(),
                        "startedAt",
                        Instant.now().toString(),
                        "host",
                        "desktop-glfw",
                        "uiBudgetMs",
                        UI_BUDGET_MS,
                        "width",
                        1440,
                        "height",
                        1000,
                        "vsync",
                        false,
                        "environment",
                        environment(),
                        "runs",
                        benchmark.runs,
                        "samples",
                        benchmark.results,
                        "complete",
                        false);
        Throwable failure = null;
        try {
            for (String mode : new String[] {"ui", "window"}) benchmark.run(output, mode);
            report.put("complete", true);
        } catch (Exception | Error error) {
            failure = error;
            report.put("failure", error.toString());
            throw error;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            try {
                Files.write(
                        output.resolve("settings-performance.json"),
                        json(report).getBytes(StandardCharsets.UTF_8));
            } catch (Exception writeFailure) {
                if (failure != null) failure.addSuppressed(writeFailure);
                else throw writeFailure;
            } finally {
                if (visible == null) System.clearProperty("uilib.desktop.visible");
                else System.setProperty("uilib.desktop.visible", visible);
            }
        }
    }

    private void run(Path output, String mode) throws Exception {
        Path configuration = output.resolve(mode + ".yaml");
        Map<String, Object> run =
                fields(
                        "mode",
                        mode,
                        "configuration",
                        configuration.toAbsolutePath().toString(),
                        "storageBeforeOpen",
                        storage(configuration),
                        "complete",
                        false,
                        "status",
                        "running");
        runs.add(run);
        int firstSample = results.size();
        long opening = System.nanoTime();
        try (DesktopWindow window = DesktopWindow.openSettings(configuration)) {
            run.put("openWindowNanos", System.nanoTime() - opening);
            glfwSetWindowSize(window.handle(), 1440, 1000);
            glfwSwapInterval(0);
            long firstFrame = System.nanoTime();
            window.frame();
            run.put("firstFrameNanos", System.nanoTime() - firstFrame);
            requireFramebuffer(window);
            require(glfwGetWindowAttrib(window.handle(), GLFW_VISIBLE) == GLFW_FALSE, "基准窗口必须隐藏");
            run.put("device", device(window));
            System.out.println(
                    "SETTINGS_GPU " + glGetString(GL_RENDERER) + " · " + glGetString(GL_VERSION));
            System.out.println(
                    "SETTINGS_BASELINE width="
                            + window.width()
                            + " height="
                            + window.height()
                            + " mode="
                            + mode);
            byte[] saved = Files.readAllBytes(configuration);
            List<String> original = DesktopSettingsVerification.current(window);
            run.put("sourcesBefore", original);
            run.put("storageBeforeInteraction", storage(configuration));
            require(DesktopSettingsVerification.hasText(window, "已保存"), "基准初始配置不是已保存状态");
            DesktopTestInput input = new DesktopTestInput(window);
            // 隔离系统光标的偶发MOVE；脚本仍调用同一已安装生产回调，真实resize等继续由唯一poll分发。
            try (DesktopTestInput.ScriptedCursor cursor = input.scriptedCursor()) {
                sample(window, input, cursor, mode, "warmup", 0, 1500, false, null);
                sample(window, input, cursor, mode, "empty-window", 0, 750, true, null);
                sample(window, input, cursor, mode, "idle", 0, 1500, false, null);
                sample(window, input, cursor, mode, "hover1", 1, 1500, false, null);
                sample(window, input, cursor, mode, "hover8", 8, 1500, false, null);
                input.click("管理字体来源");
                sample(window, input, cursor, mode, "font-modal-motion", 1, 450, false, null);
                sample(window, input, cursor, mode, "font-modal-hover8", 8, 1000, false, null);
                List<SceneGeometryView.Entry> handles =
                        DesktopSettingsVerification.fontHandles(window);
                require(handles.size() >= 2 && original.size() >= 2, "字体排序基准至少需要两行");
                SceneNode handle = handles.get(0).node;
                LayoutBox second = handles.get(1).box;
                int releaseX = second.x + second.width / 2, releaseY = second.y + second.height - 1;
                input.moveTo(handle);
                input.mouse.invoke(window.handle(), GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 0);
                window.frame();
                DesktopSettingsVerification.requireFontDragOwner(window, handle);
                sample(window, input, cursor, mode, "font-drag1", 1, 1000, false, handle);
                sample(window, input, cursor, mode, "font-drag8", 8, 1500, false, handle);
                input.point(releaseX, releaseY);
                window.frame();
                input.mouse.invoke(window.handle(), GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 0);
                window.frame();
                DesktopSettingsVerification.requireNoFontDragOwner(window);
                sample(window, input, cursor, mode, "font-reorder-motion", 0, 450, false, null);
                run.put("sourcesAfterRelease", DesktopSettingsVerification.current(window));
                require(DesktopSettingsVerification.current(window).equals(original), "字体排序草稿提前应用");
                require(
                        DesktopSettingsVerification.hasText(window, "有未应用的更改"),
                        "release没有产生设置排序草稿");
                require(Arrays.equals(saved, Files.readAllBytes(configuration)), "排序草稿隐式保存了文件");
                run.put("storageAfterRelease", storage(configuration));
                input.click("完成字体编辑");
                sample(window, input, cursor, mode, "modal-close-motion", 1, 450, false, null);
                // 应用在所有可比较轨迹完成之后；正式配置权威证明release真正改变业务顺序。
                List<String> expected = new ArrayList<>(original);
                Collections.swap(expected, 0, 1);
                require(!expected.equals(original), "基准前两项相同，无法证伪排序未生效");
                input.click("应用全部更改");
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (!DesktopSettingsVerification.current(window).equals(expected)
                        && System.nanoTime() < deadline) window.frame();
                List<String> applied = DesktopSettingsVerification.current(window);
                require(applied.equals(expected), "release后正式应用没有发布预期字体顺序：" + applied);
                require(DesktopSettingsVerification.hasText(window, "已应用，未保存"), "应用后的未保存状态不实");
                require(Arrays.equals(saved, Files.readAllBytes(configuration)), "基准应用隐式写了文件");
                run.put("expectedSourcesAfterApply", expected);
                run.put("sourcesAfterApply", applied);
                run.put("storageAfterApply", storage(configuration));
                run.put("reorderCommitted", true);
                run.put("suppressedNativeMoves", cursor.nativeMoves());
            }
            requireFramebuffer(window);
            require(
                    glfwGetWindowAttrib(window.handle(), GLFW_VISIBLE) == GLFW_FALSE,
                    "基准结束窗口必须仍隐藏");
            require(glGetError() == GL_NO_ERROR, "设置页基准产生GL错误");
        } catch (Exception | Error failure) {
            run.put("complete", false);
            run.put("status", "failed");
            run.put("failure", failure.toString());
            throw failure;
        } finally {
            run.put("actualScenes", results.size() - firstSample);
            long frames = 0;
            for (int i = firstSample; i < results.size(); i++)
                frames += ((Number) results.get(i).get("frames")).longValue();
            run.put("actualFrames", frames);
            run.put("storageAfterClose", storage(configuration));
        }
        run.put("complete", true);
        run.put("status", "complete");
    }

    private void sample(
            DesktopWindow window,
            DesktopTestInput input,
            DesktopTestInput.ScriptedCursor cursor,
            String mode,
            String scene,
            int moves,
            int millis,
            boolean empty,
            SceneNode dragOwner) {
        PerformanceSamples samples = new PerformanceSamples(SAMPLE_CAPACITY);
        long submittedMoves = 0;
        int nativeMoves = cursor.nativeMoves(), changes = 0;
        Object previous = window.lastPlan;
        PerformanceReport.Snapshot before = PerformanceReport.Snapshot.capture();
        long previousStart = 0, started = System.nanoTime(), target = millis * 1_000_000L;
        String status = "running", failure = null;
        try {
            if (dragOwner != null)
                DesktopSettingsVerification.requireFontDragOwner(window, dragOwner);
            while (System.nanoTime() - started < target && samples.size() < samples.capacity()) {
                if (window.shouldClose()) {
                    status = "window-closed";
                    throw new AssertionError("基准窗口提前关闭");
                }
                if (Thread.currentThread().isInterrupted()) {
                    status = "interrupted";
                    throw new IllegalStateException("基准线程被中断");
                }
                if ("ui".equals(mode) && !empty) {
                    glfwPollEvents();
                    glClear(GL_COLOR_BUFFER_BIT);
                }
                int frame = samples.size();
                long begin = System.nanoTime();
                for (int i = 0; i < moves; i++) {
                    input.point(120 + (frame + i) % 120, 140 + (frame + i) % 300);
                    submittedMoves++;
                }
                if (empty) {
                    glfwPollEvents();
                    glClear(GL_COLOR_BUFFER_BIT);
                    glfwSwapBuffers(window.handle());
                } else if ("window".equals(mode)) window.frame();
                else {
                    window.fonts.beginPresentation(System.nanoTime());
                    try {
                        window.page.frame();
                    } finally {
                        window.fonts.endPresentation();
                    }
                    // 完成真实GPU工作，不把只入队的速度冒充画面产出速度。
                    glFinish();
                }
                long end = System.nanoTime();
                samples.add(end - begin, previousStart == 0 ? 0 : begin - previousStart);
                previousStart = begin;
                if (previous != window.lastPlan) changes++;
                previous = window.lastPlan;
            }
            if (window.shouldClose()) {
                status = "window-closed";
                throw new AssertionError("基准窗口提前关闭");
            }
            if (Thread.currentThread().isInterrupted()) {
                status = "interrupted";
                throw new IllegalStateException("基准线程被中断");
            }
            status =
                    completionStatus(
                            samples.size(),
                            samples.capacity(),
                            System.nanoTime() - started,
                            target);
            require("complete".equals(status), "采样未完成：" + mode + "/" + scene + " " + status);
            if (dragOwner != null)
                DesktopSettingsVerification.requireFontDragOwner(window, dragOwner);
        } catch (RuntimeException | Error error) {
            failure = error.toString();
            if ("running".equals(status) || "complete".equals(status)) status = "failed";
            throw error;
        } finally {
            long wallNanos = System.nanoTime() - started;
            PerformanceReport.Snapshot after = PerformanceReport.Snapshot.capture();
            PerformanceSamples.Summary summary = samples.frame();
            boolean complete = "complete".equals(status) && failure == null;
            boolean budgetApplicable = !empty && "ui".equals(mode) && !"warmup".equals(scene);
            Map<String, Object> record =
                    fields(
                            "mode",
                            mode,
                            "scene",
                            scene,
                            "phase",
                            "warmup".equals(scene) ? "warmup" : "sample",
                            "frames",
                            samples.size(),
                            "targetFrames",
                            null,
                            "frameCapacity",
                            samples.capacity(),
                            "targetDurationNanos",
                            target,
                            "samplingModel",
                            "duration-limited; frameCapacity is a bound, not a completion target",
                            "wallNanos",
                            wallNanos,
                            "wallFramesPerSecond",
                            rate(samples.size(), wallNanos),
                            "complete",
                            complete,
                            "status",
                            status,
                            "failure",
                            failure,
                            "movesPerFrame",
                            moves,
                            "submittedMoves",
                            submittedMoves,
                            "submittedMovesPerSecond",
                            rate(submittedMoves, wallNanos),
                            "actualCoreInputBatches",
                            null,
                            "actualActiveInputTicks",
                            null,
                            "observedCoreInputHz",
                            null,
                            "inputBoundary",
                            "submitted GLFW MOVE callbacks counted; no formal Core batch/activeInput counter available",
                            "rawMoveDispatchCount",
                            null,
                            "suppressedNativeMoves",
                            cursor.nativeMoves() - nativeMoves,
                            "planChanges",
                            changes,
                            "timingBoundary",
                            boundary(mode, empty),
                            "intervalBoundary",
                            "adjacent timed-frame starts; first interval=0; includes poll/clear and outside-timer observation",
                            "samples",
                            samples,
                            "processBefore",
                            snapshot(before),
                            "processAfter",
                            snapshot(after),
                            "processDelta",
                            fields(
                                    "heapBytes",
                                    after.heap - before.heap,
                                    "gcCount",
                                    after.gcCount - before.gcCount,
                                    "gcMillis",
                                    after.gcMillis - before.gcMillis,
                                    "scope",
                                    "whole JVM; not per-run allocation or attribution"),
                            "fps",
                            summary.meanNanos > 0 ? 1e9 / summary.meanNanos : null,
                            "meanMs",
                            summary.meanNanos / 1e6,
                            "p50Ms",
                            summary.p50Nanos / 1e6,
                            "p95Ms",
                            summary.p95Nanos / 1e6,
                            "p99Ms",
                            summary.p99Nanos / 1e6,
                            "maxMs",
                            summary.maxNanos / 1e6,
                            "uiBudgetApplicable",
                            budgetApplicable,
                            "withinUiBudget",
                            budgetApplicable
                                    ? complete && summary.meanNanos <= UI_BUDGET_MS * 1e6
                                    : null);
            results.add(record);
            System.out.println(
                    "SETTINGS_PERFORMANCE "
                            + mode
                            + "/"
                            + scene
                            + " status="
                            + status
                            + " frames="
                            + samples.size()
                            + " meanMs="
                            + record.get("meanMs")
                            + " p95Ms="
                            + record.get("p95Ms")
                            + " p99Ms="
                            + record.get("p99Ms")
                            + " maxMs="
                            + record.get("maxMs")
                            + " submittedMoves="
                            + submittedMoves);
        }
    }

    static String completionStatus(int frames, int capacity, long wallNanos, long targetNanos) {
        if (frames == 0) return "no-samples";
        if (wallNanos >= targetNanos) return "complete";
        return frames >= capacity ? "sample-capacity" : "interrupted";
    }

    private static String boundary(String mode, boolean empty) {
        if (empty)
            return "empty control: glfwPollEvents + glClear + glfwSwapBuffers; no page, no glFinish";
        if ("window".equals(mode))
            return "installed GLFW MOVE callbacks + window.frame (poll/clear/font begin/page.frame/replay/font end/swap); no glFinish";
        return "installed GLFW MOVE callbacks + font beginPresentation + page.frame/replay + font endPresentation + glFinish (GPU completion); poll/clear outside";
    }

    private static Map<String, Object> environment() {
        return fields(
                "version",
                System.getProperty("uilib.version", "unknown"),
                "revision",
                System.getProperty("settings.revision", "unknown"),
                "coreCoordinate",
                System.getProperty("settings.coreCoordinate", "unknown"),
                "galleryCoordinate",
                System.getProperty("settings.galleryCoordinate", "unknown"),
                "coreCodeSource",
                codeSource(ScenePageSession.class),
                "galleryCodeSource",
                codeSource(PerformanceSamples.class),
                "desktopCodeSource",
                codeSource(DesktopWindow.class),
                "benchmarkCodeSource",
                codeSource(DesktopSettingsPerformance.class),
                "javaVersion",
                System.getProperty("java.version"),
                "javaRuntimeVersion",
                System.getProperty("java.runtime.version"),
                "javaVmName",
                System.getProperty("java.vm.name"),
                "javaVmVersion",
                System.getProperty("java.vm.version"),
                "javaVendor",
                System.getProperty("java.vendor"),
                "osName",
                System.getProperty("os.name"),
                "osVersion",
                System.getProperty("os.version"),
                "osArch",
                System.getProperty("os.arch"),
                "processors",
                Runtime.getRuntime().availableProcessors(),
                "maxHeapBytes",
                Runtime.getRuntime().maxMemory());
    }

    private static String codeSource(Class<?> type) {
        return type.getProtectionDomain().getCodeSource() == null
                ? "unknown"
                : type.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
    }

    private static Map<String, Object> device(DesktopWindow window) {
        int[] width = {0}, height = {0};
        glfwGetWindowSize(window.handle(), width, height);
        return fields(
                "renderer",
                glGetString(GL_RENDERER),
                "glVersion",
                glGetString(GL_VERSION),
                "vendor",
                glGetString(GL_VENDOR),
                "glfwVersion",
                glfwGetVersionString(),
                "framebufferWidth",
                window.width(),
                "framebufferHeight",
                window.height(),
                "windowWidth",
                width[0],
                "windowHeight",
                height[0],
                "hidden",
                true,
                "vsync",
                0);
    }

    private static void requireFramebuffer(DesktopWindow window) {
        int[] width = {0}, height = {0};
        glfwGetFramebufferSize(window.handle(), width, height);
        require(
                width[0] == 1440
                        && height[0] == 1000
                        && window.width() == width[0]
                        && window.height() == height[0],
                "实际framebuffer尺寸与基准不符：" + width[0] + "×" + height[0]);
    }

    private static Map<String, Object> storage(Path path) throws Exception {
        if (!Files.exists(path)) return fields("exists", false);
        byte[] data = Files.readAllBytes(path),
                digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder hash = new StringBuilder();
        for (byte value : digest) {
            hash.append(Character.forDigit((value & 255) >>> 4, 16));
            hash.append(Character.forDigit(value & 15, 16));
        }
        return fields("exists", true, "bytes", data.length, "sha256", hash.toString());
    }

    private static Map<String, Object> snapshot(PerformanceReport.Snapshot snapshot) {
        return fields(
                "heapBytes",
                snapshot.heap,
                "gcCount",
                snapshot.gcCount,
                "gcMillis",
                snapshot.gcMillis);
    }

    private static Double rate(long count, long nanos) {
        return nanos > 0 ? count * 1e9 / nanos : null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static Map<String, Object> fields(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }

    /** 字符串只沿正式Report.quote编码；Samples自己输出原始顺序与唯一统计，禁止复制统计公式。 */
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof PerformanceSamples) return ((PerformanceSamples) value).toJson();
        if (value instanceof String) return PerformanceReport.quote((String) value);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof long[]) return Arrays.toString((long[]) value);
        StringBuilder result = new StringBuilder();
        if (value instanceof Map) {
            result.append('{');
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (result.length() > 1) result.append(',');
                result.append(PerformanceReport.quote((String) entry.getKey()))
                        .append(':')
                        .append(json(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (value instanceof Iterable) {
            result.append('[');
            for (Object item : (Iterable<?>) value) {
                if (result.length() > 1) result.append(',');
                result.append(json(item));
            }
            return result.append(']').toString();
        }
        throw new IllegalArgumentException("不支持的报告值：" + value.getClass().getName());
    }
}
