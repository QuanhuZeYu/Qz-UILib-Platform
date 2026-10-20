package club.heiqi.uilib.host.desktop;

/** 正式帧驱动及字形连续稳定等待；等待上限只属于实机验收，不改变生产调度。 */
final class DesktopTestFrames {
    private DesktopTestFrames() {}
    static void ready(DesktopWindow window) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!window.ready().isDone() && System.nanoTime() < deadline) window.frame();
        if (!window.ready().isDone() || window.ready().isCompletedExceptionally())
            throw new AssertionError("资源尚未完成owner发布");
    }
    static void frames(DesktopWindow window, int count) {
        ready(window);
        for (int i = 0; i < count; i++) {
            if (window.shouldClose()) throw new AssertionError("验收窗口提前关闭");
            window.frame();
        }
    }
    /**
     * 同步 MOVE 断言须经过正常输入采样；真实 GPU 回归中单帧曾落在 300Hz 门内。
     * 只按真实时钟驱动帧跨过一个采样周期，不等待业务状态或触发额外输入。
     */
    static void inputFrame(DesktopWindow window) {
        long started = System.nanoTime();
        long notBefore = started + (1_000_000_000L + 299) / 300;
        long deadline = started + 1_000_000_000L;
        long frameStart;
        do {
            frameStart = System.nanoTime();
            if (frameStart - deadline >= 0) throw new AssertionError("已提交的 MOVE 未在时限内完成正式输入采样");
            frames(window, 1);
        } while (frameStart - notBefore < 0);
    }
    /**
     * 仅供所有呈现都会有限结束的场景比较端点；字形稳定不代表共享动效已经落定。
     * 测试只观察原调度集合，不读取业务值或预期图形，也不向生产暴露轨迹/时钟。
     */
    static void finitePresentations(DesktopWindow window) {
        ready(window);
        long deadline = System.nanoTime() + 5_000_000_000L;
        for (;;) {
            String active = finitePresentationSteps(window);
            if (System.nanoTime() - deadline >= 0)
                throw new AssertionError("有限呈现未在时限内结束，残余step=[" + active + "]");
            if (!active.isEmpty()) { frames(window, 1); continue; }
            try { settle(window, deadline); }
            catch (AssertionError failure) {
                throw new AssertionError("有限呈现/字形等待失败，残余step=[" + finitePresentationSteps(window) + "]", failure);
            }
            active = finitePresentationSteps(window);
            if (System.nanoTime() - deadline >= 0)
                throw new AssertionError("有限呈现未在时限内结束，残余step=[" + active + "]");
            if (active.isEmpty() && window.fonts.rasterizer().stats().pending == 0
                    && window.fonts.rasterizer().stats().ready == 0
                    && window.fonts.presentationStats().uploadCalls == 0) return;
        }
    }
    private static String finitePresentationSteps(DesktopWindow window) {
        String[] result = {null};
        window.domain.execute(() -> {
            try {
                Object runtime = window.page.runtime();
                java.lang.reflect.Field transitions = runtime.getClass().getDeclaredField("transitions");
                if (!"club.heiqi.uilib.ui.scene.runtime.SceneTransitions".equals(transitions.getType().getName()))
                    throw new AssertionError("有限呈现观察字段transitions类型已变");
                transitions.setAccessible(true);
                Object scheduler = transitions.get(runtime);
                java.lang.reflect.Field active = transitions.getType().getDeclaredField("active");
                if (!java.util.Set.class.isAssignableFrom(active.getType()))
                    throw new AssertionError("有限呈现观察字段active类型已变");
                active.setAccessible(true);
                Object steps = active.get(scheduler);
                if (!(steps instanceof java.util.Set<?>))
                    throw new AssertionError("有限呈现观察字段active不是有效Set");
                result[0] = ((java.util.Set<?>) steps).stream().map(step -> step.getClass().getName())
                        .collect(java.util.stream.Collectors.joining(", "));
            } catch (ReflectiveOperationException | RuntimeException failure) {
                throw new AssertionError("有限呈现观察字段不可读，须核对测试与生产内部结构", failure);
            }
        });
        return result[0];
    }
    static void settle(DesktopWindow window) {
        settle(window, System.nanoTime() + 5000000000L);
    }
    private static void settle(DesktopWindow window, long deadline) {
        int stable = 0;
        do {
            frames(window, 1);
            if (window.fonts.rasterizer().stats().pending == 0
                    && window.fonts.rasterizer().stats().ready == 0
                    && window.fonts.presentationStats().uploadCalls == 0) stable++;
            else stable = 0;
            if (stable >= 4) break;
        } while (System.nanoTime() < deadline);
        if (stable < 4 || window.fonts.rasterizer().stats().errors != 0
                || window.fonts.rasterizer().workerFailure() != null)
            throw new AssertionError("可见字形未连续稳定或存在raster错误");
    }
}
