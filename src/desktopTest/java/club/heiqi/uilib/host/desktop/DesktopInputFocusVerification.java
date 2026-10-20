package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static club.heiqi.uilib.host.desktop.DesktopTestFrames.finitePresentations;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.geometry;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.hit;

import club.heiqi.uilib.testing.gallery.TestGallery;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.*;
import club.heiqi.uilib.ui.scene.edit.SceneCompositionTarget;
import club.heiqi.uilib.ui.scene.edit.SceneEditState;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.SceneGeometryView;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** 正式字段的实窗回归；输入只借用已安装 GLFW callbacks，像素来自正式 PaintPlan 的真实 GL 回放。 */
public final class DesktopInputFocusVerification {
    private final Path output;
    private String renderer;
    private final List<String> failures = new ArrayList<>();
    private final StringBuilder report = new StringBuilder("case\tcheck\tpassed\tdetail\n");
    private final StringBuilder boxes = new StringBuilder("case\teditor\tsurface\tpadding\n");

    private DesktopInputFocusVerification(Path output) { this.output = output; }

    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args.length == 0 ? "input-focus-report" : args[0]);
        Files.createDirectories(output);
        new DesktopInputFocusVerification(output).run();
    }

    private void run() throws Exception {
        try {
            gallery(TestGallery.Page.INPUTS);
            gallery(TestGallery.Page.FORMS);
            gallery(TestGallery.Page.SETTINGS);
            contracts();
        } finally {
            Files.write(output.resolve("input-focus-checks.tsv"), report.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(output.resolve("input-focus-geometry.tsv"), boxes.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!failures.isEmpty()) throw new AssertionError("字段实机回归失败：" + String.join("；", failures));
        System.out.println("INPUT_FOCUS actualCallbacks=true nativeEvents=false actualGpuPixels=true formalGeometry=true "
                + "wide=true lightDark=true mouseTab=true singleMultilineSearchSettings=true disabled=true dynamicTheme=true "
                + "customTheme=true explicitPadding=true readBuffer=GL_BACK renderer=" + renderer);
    }

    private void gallery(TestGallery.Page page) throws Exception {
        Path config = output.resolve("gallery-" + page.name().toLowerCase(java.util.Locale.ROOT) + "-" + System.nanoTime() + ".yaml");
        try (DesktopWindow w = DesktopWindow.openWithServices(config, false,
                services -> DesktopGallery.createGallery(services, page))) {
            renderer = glGetString(GL_RENDERER);
            DesktopTestInput input = new DesktopTestInput(w);
            try (DesktopTestInput.ScriptedCursor cursor = input.scriptedCursor()) {
                glfwSetWindowSize(w.handle(), 2560, 1440);
                finitePresentations(w);
                if (page == TestGallery.Page.SETTINGS) { input.click("配置文件"); finitePresentations(w); }
                List<SceneNode> editors = editors(w);
                require(editors.size() >= (page == TestGallery.Page.SETTINGS ? 1 : 2), "正式页面缺少预期编辑控件：" + page);
                Object session = w.page;
                for (int width : new int[] {2560, 1440}) {
                    glfwSetWindowSize(w.handle(), width, width == 2560 ? 1440 : 1000);
                    finitePresentations(w);
                    BufferedImage light = null;
                    for (ThemeMode mode : ThemeMode.values()) {
                        w.domain.execute(() -> w.configuration.set(ThemeConfiguration.KEY, mode));
                        finitePresentations(w);
                        String prefix = page.name().toLowerCase(java.util.Locale.ROOT) + "-" + width + "-" + mode.name().toLowerCase(java.util.Locale.ROOT);
                        check(prefix, "theme-keeps-session", w.page == session, "同一页面实例更新主题");
                        for (int i = 0; i < (page == TestGallery.Page.SETTINGS ? 1 : 2); i++) {
                            SceneNode editor = editors.get(i);
                            String name = page == TestGallery.Page.INPUTS ? (i == 0 ? "single" : "multiline")
                                    : page == TestGallery.Page.FORMS ? (i == 0 ? "field" : "search") : "settings-path";
                            // 使用正式逻辑关系选取当前组合的外表面，不从截图重建布局或写回尺寸。
                            SceneNode surface = page == TestGallery.Page.INPUTS && i == 1 ? editor : editor.parent();
                            exercise(w, input, new Target(prefix + "-" + name, editor, surface));
                        }
                        BufferedImage current = pixels(w);
                        if (light == null) light = current;
                        else check(prefix, "dynamic-theme-pixels", distance(light.getRGB(2, 2), current.getRGB(2, 2)) > 24,
                                "同一窗口浅深主题改变实际画布像素");
                        check(prefix, "same-editors", editors(w).containsAll(editors), "主题/窗口变化保留原编辑身份");
                    }
                }
            }
        }
    }

    private void exercise(DesktopWindow w, DesktopTestInput input, Target target) throws Exception {
        SceneNode editor = target.editor;
        reveal(w, input, target.surface);
        LayoutBox b = geometry(w, target.surface).box;
        input.enqueueAt(b.x + b.width / 2.0, b.y - 3);
        finitePresentations(w);
        require(focused(w) != editor, "背景点击没有释放原字段焦点：" + target.name);
        input.moveTo(editor);
        finitePresentations(w);
        BufferedImage idle = capture(w, target, "idle");
        input.click(geometry(w, editor));
        finitePresentations(w);
        check(target.name, "mouse-logical-focus", focused(w) == editor, "生产主键回调取得唯一编辑焦点");
        boolean[] editable = {false};
        w.domain.execute(() -> editable[0] = editor.control().acceptsTextInput(editor));
        if (editable[0]) check(target.name, "editable-focus-visible", focusVisible(w, editor), "鼠标编辑保留焦点线索");
        BufferedImage mouse = capture(w, target, "mouse");
        rounded(target, b, mouse, "mouse");
        singleSurface(w, target, mouse, "mouse");
        int delta = edgeDifference(idle, mouse, b);
        check(target.name, "mouse-border-feedback", delta > 8, "top-edge RGB distance=" + delta);

        key(w, input, GLFW_KEY_TAB, GLFW_MOD_SHIFT);
        tabTo(w, input, editor);
        input.point(2, 2);
        finitePresentations(w);
        check(target.name, "tab-logical-focus", focused(w) == editor && focusVisible(w, editor), "Tab 走原路由且键盘焦点可见");
        BufferedImage tab = capture(w, target, "tab");
        rounded(target, b, tab, "tab");
        singleSurface(w, target, tab, "tab");
        check(target.name, "same-rounded-focus-edge", edgeDifference(mouse, tab, b) <= 2,
                "鼠标与 Tab 使用同一条圆角细边；hover 仅影响内填充");
        check(target.name, "one-focus-owner", focusedCount(w) == 1, "正式语义快照只有一个 focused entry");
    }

    private void contracts() throws Exception {
        Fixture f = new Fixture();
        try (DesktopWindow w = DesktopWindow.openWithServices(output.resolve("contracts-" + System.nanoTime() + ".yaml"), false, services -> f::build)) {
            glfwSetWindowSize(w.handle(), 1440, 1000);
            finitePresentations(w);
            DesktopTestInput input = new DesktopTestInput(w);
            try (DesktopTestInput.ScriptedCursor cursor = input.scriptedCursor()) {
                for (int i = 0; i < f.editors.size(); i++)
                    exercise(w, input, new Target("contract-" + i, f.editors.get(i), f.surfaces.get(i)));
                Target direct = new Target("custom", f.editors.get(0), f.surfaces.get(0));
                SceneNode editor = direct.editor;
                input.click(geometry(w, editor)); finitePresentations(w);
                BufferedImage before = capture(w, direct, "theme-before");
                int padding = editor.padding();
                LayoutBox original = geometry(w, editor).box;
                w.domain.execute(() -> f.theme.set(f.custom(true)));
                finitePresentations(w);
                BufferedImage after = capture(w, direct, "theme-after");
                check("custom", "author-padding", editor.padding() == padding && padding == 13, "显式 padding 在动态主题发布后保持");
                LayoutBox updated = geometry(w, editor).box;
                check("custom", "theme-keeps-geometry", same(original, updated) && focused(w) == editor, "纯配色保持正式几何及焦点");
                check("custom", "custom-fill", distance(after.getRGB(updated.x + updated.width * 3 / 4, updated.y + updated.height / 2), f.custom(true).panel) <= 2,
                        "独立 framed 字段保留作者 panel 色");
                check("custom", "custom-focus", distance(after.getRGB(updated.x + updated.width / 2, updated.y), f.custom(true).focus) <= 2,
                        "独立 framed 字段保留作者 focus 色");
                check("custom", "theme-invalidates-pixels", edgeDifference(before, after, updated) > 8, "主题变化通知现有边框动效");
                String[] text = {null};
                w.domain.execute(() -> text[0] = f.states.get(0).text().get());
                w.domain.execute(() -> f.enabled.set(false)); finitePresentations(w);
                BufferedImage disabled = capture(w, direct, "disabled");
                input.click(geometry(w, editor));
                input.character.invoke(w.handle(), 'x'); finitePresentations(w);
                boolean[] unchanged = {false};
                w.domain.execute(() -> unchanged[0] = text[0].equals(f.states.get(0).text().get()));
                check("custom", "disabled-releases-focus", focused(w) != editor, "禁用通过正式资格释放焦点");
                check("custom", "disabled-no-edit", unchanged[0], "禁用字段不接受原字符回调");
                check("custom", "disabled-theme-fill", distance(disabled.getRGB(updated.x + updated.width * 3 / 4, updated.y + updated.height / 2), f.custom(true).disabled) <= 2,
                        "禁用配色继续来自自定义主题");
                key(w, input, GLFW_KEY_TAB, 0);
                check("custom", "disabled-tab-skips", focused(w) != editor, "Tab 不进入禁用字段");
                w.domain.execute(() -> f.enabled.set(true)); finitePresentations(w);
                input.click(geometry(w, editor)); finitePresentations(w);
                check("custom", "reenable-no-second-focus", focused(w) == editor && focusedCount(w) == 1, "恢复可用后仍只有原路由焦点");
                coverage(w, input, f);
                overflow(w, input, f);
                move(w, input, f);
            }
        }
    }

    private void coverage(DesktopWindow w, DesktopTestInput input, Fixture f) throws Exception {
        SceneTheme base = f.custom(true);
        SceneTheme transparent = base.withColors(0, base.button, base.hover, base.pressed,
                base.focus, base.disabled, base.disabledText, base.tooltip, base.tooltipText);
        w.domain.execute(() -> f.theme.set(transparent));
        SceneNode editor = f.editors.get(0);
        input.click(geometry(w, editor)); finitePresentations(w);
        Target target = new Target("custom-transparent", editor, editor);
        BufferedImage image = capture(w, target, "coverage");
        LayoutBox b = geometry(w, editor).box;
        int outside = image.getRGB(b.x - 1, b.y - 1), radius = transparent.metrics(SceneControlMetrics.Kind.FIELD).radius;
        boolean intermediate = false;
        for (int y = 0; y < radius; y++) for (int x = 0; x < radius; x++) {
            int pixel = image.getRGB(b.x + x, b.y + y);
            intermediate |= distance(pixel, outside) > 2 && distance(pixel, transparent.focus) > 2;
        }
        check("custom", "rounded-stroke-coverage", intermediate, "透明底角区实际 GPU 像素包含介于背景和边色之间的 coverage 色");
        check("custom", "transparent-panel-stays-empty", distance(image.getRGB(b.x + b.width * 3 / 4, b.y + b.height / 2), outside) <= 2,
                "真实空心描边不把透明 panel 填成边色");
        String[] names = {"transparent-panel", "opaque-panel", "half-panel", "half-edge", "half-both", "transparent-edge", "transparent-both"};
        int[][] colors = {{0, 0xff000000}, {0xffff0000, 0xff000000}, {0x80ff0000, 0xff000000},
                {0xffff0000, 0x800000ff}, {0x80ff0000, 0x800000ff}, {0xffff0000, 0}, {0, 0}};
        for (int i = 0; i < colors.length; i++) {
            int panel = colors[i][0], edge = colors[i][1];
            SceneTheme contrast = base.withColors(panel, edge, edge, edge, edge,
                    base.disabled, base.disabledText, base.tooltip, base.tooltipText);
            w.domain.execute(() -> f.theme.set(contrast));
            for (int index : new int[] {0, 1}) {
                SceneNode current = f.editors.get(index), surface = f.surfaces.get(index);
                input.click(geometry(w, current)); finitePresentations(w);
                Target sample = new Target("coverage-" + names[i] + (index == 0 ? "-direct" : "-composite"), current, surface);
                BufferedImage pixels = capture(w, sample, "gpu");
                surfacePixels(sample, geometry(w, surface).box, pixels, contrast);
            }
        }
        w.domain.execute(() -> f.theme.set(base)); finitePresentations(w);
    }

    private void surfacePixels(Target target, LayoutBox b, BufferedImage image, SceneTheme theme) {
        int radius = Math.max(0, Math.min(theme.metrics(SceneControlMetrics.Kind.FIELD).radius, Math.min(b.width, b.height) / 2));
        int background = image.getRGB(b.x - 1, b.y - 1), maximum = 0, worstX = 0, worstY = 0;
        // 与生产命令分区独立：逐个几何子样本计算 edge-over-fill，再对真实背景求像素均值。
        for (int y = 0; y < radius; y++) for (int x = 0; x < radius; x++) {
            for (int[] point : new int[][] {{x, y}, {b.width - x - 1, y},
                    {x, b.height - y - 1}, {b.width - x - 1, b.height - y - 1}}) {
                int expected = surfacePixel(b, point[0], point[1], radius, theme.panel, theme.focus, background);
                int difference = distance(image.getRGB(b.x + point[0], b.y + point[1]), expected);
                if (difference > maximum) { maximum = difference; worstX = point[0]; worstY = point[1]; }
            }
        }
        check(target.name, "rounded-premultiplied-source-over", maximum <= 2,
                "四角逐样本期望与实际 GPU 像素 max RGB distance=" + maximum + " at=" + worstX + "," + worstY);
        if (radius == 8) {
            int actual = image.getRGB(b.x + 3, b.y + 1);
            int expected = surfacePixel(b, 3, 1, radius, theme.panel, theme.focus, background);
            check(target.name, "outer-nine-inner-zero", distance(actual, expected) <= 2,
                    "r8 local=3,1 outer=9/16 inner=0 actual=" + Integer.toHexString(actual) + " expected=" + Integer.toHexString(expected));
        }
        int x = b.width * 3 / 4, y = b.height / 2;
        int expected = surfacePixel(b, x, y, radius, theme.panel, theme.focus, background);
        check(target.name, "panel-alpha-over-background", distance(image.getRGB(b.x + x, b.y + y), expected) <= 2,
                "正文空白区保留 panel 的透明度，不被描边填实");
    }

    private static int surfacePixel(LayoutBox box, int x, int y, int radius, int fill, int edge, int background) {
        int result = 0xff000000;
        double fillAlpha = (fill >>> 24) / 255.0, edgeAlpha = (edge >>> 24) / 255.0;
        for (int shift = 0; shift < 24; shift += 8) {
            double sum = 0;
            for (int sy = 0; sy < 4; sy++) for (int sx = 0; sx < 4; sx++) {
                double px = x + (sx + .5) / 4, py = y + (sy + .5) / 4;
                double source = 0, alpha = 0;
                if (insideRounded(px, py, box.width, box.height, radius)) {
                    source = (fill >>> shift & 255) * fillAlpha;
                    alpha = fillAlpha;
                    if (!insideRounded(px - 1, py - 1, box.width - 2, box.height - 2, Math.max(0, radius - 1))) {
                        source = (edge >>> shift & 255) * edgeAlpha + source * (1 - edgeAlpha);
                        alpha = edgeAlpha + alpha * (1 - edgeAlpha);
                    }
                }
                sum += source + (background >>> shift & 255) * (1 - alpha);
            }
            result |= (int) Math.round(sum / 16) << shift;
        }
        return result;
    }

    private static boolean insideRounded(double x, double y, int width, int height, int radius) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        double dx = Math.max(0, Math.max(radius - x, x - (width - radius)));
        double dy = Math.max(0, Math.max(radius - y, y - (height - radius)));
        return dx * dx + dy * dy <= (double) radius * radius;
    }

    private void overflow(DesktopWindow w, DesktopTestInput input, Fixture f) throws Exception {
        SceneNode editor = f.editors.get(2), surface = f.surfaces.get(2);
        String text = String.join("", java.util.Collections.nCopies(220, "长路径/中文+name/"));
        w.domain.execute(() -> f.states.get(2).reset(text));
        finitePresentations(w);
        input.click(geometry(w, editor));
        for (boolean end : new boolean[] {false, true}) {
            key(w, input, end ? GLFW_KEY_END : GLFW_KEY_HOME, 0);
            String label = end ? "right" : "left";
            Target target = new Target("overflow", editor, surface);
            BufferedImage image = capture(w, target, label);
            SceneScrollViewport.Snapshot[] view = {null};
            w.domain.execute(() -> view[0] = w.page.runtime().scrollSnapshot(editor, SceneNode.Axis.ROW, geometry(w)));
            check("overflow", label + "-real-scroll-end", view[0] != null && view[0].maximum > 0
                    && view[0].offset == (end ? view[0].maximum : 0), "Home/End 实际回调到达原视口两端");
            int radius = f.custom(true).metrics(SceneControlMetrics.Kind.FIELD).radius;
            LayoutBox outer = geometry(w, surface).box;
            List<SceneNode> bars = rangedScrollbars(w, surface);
            require(bars.size() == 1, "长字段缺少唯一正式组合滚动条");
            LayoutBox bounds = geometry(w, bars.get(0)).box;
            check("overflow", label + "-scrollbar-safe-width", bounds.x >= outer.x + radius
                    && bounds.x + bounds.width <= outer.x + outer.width - radius, "普通行布局将条置于同角色圆角安全宽度内");
            int escaped = 0, outside = image.getRGB(outer.x - 1, outer.y - 1);
            for (int y = 0; y < radius; y++) for (int x = 0; x < radius; x++) {
                int nearX = radius - x - 1, nearY = radius - y - 1;
                if (nearX * nearX + nearY * nearY <= radius * radius) continue;
                for (int[] p : new int[][] {{outer.x + x, outer.y + y}, {outer.x + outer.width - x - 1, outer.y + y},
                        {outer.x + x, outer.y + outer.height - y - 1}, {outer.x + outer.width - x - 1, outer.y + outer.height - y - 1}})
                    escaped = Math.max(escaped, distance(image.getRGB(p[0], p[1]), outside));
            }
            check("overflow", label + "-thumb-inside-rounded-surface", escaped <= 2, "全部完全位于圆弧外的角像素保持背景，max RGB distance=" + escaped);
        }
    }

    private void move(DesktopWindow w, DesktopTestInput input, Fixture f) throws Exception {
        SceneNode editor = f.editors.get(1), original = f.surfaces.get(1);
        w.domain.execute(() -> f.states.get(1).reset(String.join("", java.util.Collections.nCopies(220, "move/长路径/"))));
        finitePresentations(w);
        input.click(geometry(w, editor)); finitePresentations(w);
        w.domain.execute(() -> f.destination.set(f.moveTarget)); finitePresentations(w);
        check("move", "same-mounted-editor", editor.parent() == f.moveTarget && editor.runtime() == w.page.runtime()
                && focused(w) == editor, "正式 NodeBinding.move 保留原节点、挂载和唯一焦点");
        Target target = new Target("moved-editor", editor, editor);
        BufferedImage image = capture(w, target, "before-original-removal");
        LayoutBox restored = geometry(w, editor).box;
        rounded(target, restored, image, "restored-framed");
        int[] focusColor = {0};
        w.domain.execute(() -> focusColor[0] = f.theme.get().focus);
        check("move", "restored-framed-edge", distance(image.getRGB(restored.x + restored.width / 2, restored.y), focusColor[0]) <= 2,
                "旧组合仍挂载时，新位置边框已绘制原主题焦点色");
        LayoutBox former = geometry(w, original).box;
        check("move", "old-surface-empty", distance(image.getRGB(former.x + former.width / 2, former.y), image.getRGB(former.x - 1, former.y)) <= 2,
                "仍挂载的旧外表面不再画空框");
        check("move", "old-scrollbar-disconnected", rangedScrollbars(w, original).isEmpty(), "旧组合不能保留远程滚动范围");
        boolean[] automatic = {false};
        w.domain.execute(() -> automatic[0] = !w.page.runtime().hasExplicitScrollbar(editor, SceneNode.Axis.ROW)
                && w.semantics().entries.stream().anyMatch(e -> e.values.role == club.heiqi.uilib.ui.scene.semantics.SceneSemantics.Role.SCROLLBAR
                        && e.values.maximum != null && e.values.maximum > 0 && e.box.y >= geometry(w, editor).box.y
                        && e.box.y + e.box.height <= geometry(w, editor).box.y + geometry(w, editor).box.height
                        && e.box.x >= geometry(w, editor).box.x && e.box.x + e.box.width <= geometry(w, editor).box.x + geometry(w, editor).box.width));
        check("move", "automatic-scrollbar-restored", automatic[0], "旧组合仍挂载时，新位置已有自动水平条且没有显式远程claim");
        String[] before = {null};
        w.domain.execute(() -> before[0] = f.states.get(1).text().get());
        input.character.invoke(w.handle(), 'm'); finitePresentations(w);
        boolean[] edited = {false};
        w.domain.execute(() -> edited[0] = f.states.get(1).text().get().length() == before[0].length() + 1);
        check("move", "original-editor-still-edits", edited[0] && focused(w) == editor, "原字符回调继续写入原草稿");
        w.domain.execute(() -> {
            List<SceneNode> remaining = new ArrayList<>(f.children.get()); remaining.remove(original); f.children.set(remaining);
        });
        finitePresentations(w);
        check("move", "old-surface-release", original.runtime() == null && geometry(w).get(original) == null
                && editor.runtime() == w.page.runtime() && focused(w) == editor, "ownedChildren 结束旧组合，已迁移编辑身份/焦点继续存续");
        capture(w, target, "after-original-removal");
    }

    private void rounded(Target t, LayoutBox b, BufferedImage image, String mode) {
        int max = 0;
        for (int[] corner : new int[][] {{b.x, b.y, -1, -1}, {b.x + b.width - 1, b.y, 1, -1},
                {b.x, b.y + b.height - 1, -1, 1}, {b.x + b.width - 1, b.y + b.height - 1, 1, 1}})
            max = Math.max(max, distance(image.getRGB(corner[0], corner[1]), image.getRGB(corner[0] + corner[2], corner[1] + corner[3])));
        check(t.name, mode + "-rounded-corners", max <= 2, "corner/outside max RGB distance=" + max);
    }

    private void singleSurface(DesktopWindow w, Target t, BufferedImage image, String mode) {
        if (t.surface == t.editor) return;
        LayoutBox editor = geometry(w, t.editor).box, surface = geometry(w, t.surface).box;
        if (editor.y + editor.height >= surface.y + surface.height - 1) return;
        int x = editor.x + editor.width * 3 / 4, y = editor.y + editor.height - 1;
        int seam = distance(image.getRGB(x, y), image.getRGB(x, y - 1));
        check(t.name, mode + "-no-inner-bottom-stroke", seam <= 2, "editor bottom/fill RGB distance=" + seam);
    }

    private BufferedImage capture(DesktopWindow w, Target t, String mode) throws Exception {
        BufferedImage image = pixels(w);
        ImageIO.write(image, "png", output.resolve(t.name + "-" + mode + ".png").toFile());
        if (t.name.startsWith("inputs-") && mode.equals("mouse")) section(w, t, image);
        boxes.append(t.name).append('-').append(mode).append('\t').append(box(geometry(w, t.editor).box))
                .append('\t').append(box(geometry(w, t.surface).box)).append('\t').append(t.editor.padding()).append('\n');
        return image;
    }

    private void section(DesktopWindow w, Target target, BufferedImage image) throws Exception {
        SceneNode title = geometry(w).ordered().stream()
                .filter(e -> e.text != null && "单行与多行编辑".equals(e.text.logicalText))
                .map(e -> e.node).findFirst().orElseThrow(() -> new AssertionError("缺少截图对应的正式编辑区块"));
        SceneNode common = target.editor;
        while (common != null && !contains(common, title)) common = common.parent();
        require(common != null, "编辑器与区块标题没有共同所属节点");
        LayoutBox crop = hit(geometry(w, common));
        ImageIO.write(image.getSubimage(crop.x, crop.y, crop.width, crop.height), "png",
                output.resolve(target.name + "-section.png").toFile());
    }

    private static boolean contains(SceneNode root, SceneNode node) {
        for (SceneNode at = node; at != null; at = at.parent()) if (at == root) return true;
        return false;
    }

    private static BufferedImage pixels(DesktopWindow w) {
        // 沿已有桌面取证方法在真实 back buffer 回放当前不可变计划，避免隐藏窗口 front buffer 的合成器差异。
        w.fonts.beginPresentation(1_000_000);
        try { w.replay(w.lastPlan); }
        finally { w.fonts.endPresentation(); }
        return DesktopTestReadback.readBack(w);
    }

    private static List<SceneNode> rangedScrollbars(DesktopWindow w, SceneNode root) {
        List<SceneNode> result = new ArrayList<>();
        w.domain.execute(() -> {
            for (SceneGeometryView.Entry e : geometry(w).ordered()) {
                if (!contains(root, e.node) || e.node.control() == null) continue;
                club.heiqi.uilib.ui.scene.semantics.SceneSemantics semantics = e.node.control().semantics(e.node);
                if (semantics != null && semantics.values.role == club.heiqi.uilib.ui.scene.semantics.SceneSemantics.Role.SCROLLBAR
                        && semantics.values.maximum != null && semantics.values.maximum > 0) result.add(e.node);
            }
        });
        return result;
    }

    private static List<SceneNode> editors(DesktopWindow w) {
        List<SceneNode> result = new ArrayList<>();
        w.domain.execute(() -> {
            for (SceneGeometryView.Entry e : geometry(w).ordered())
                if (e.node.control() instanceof SceneCompositionTarget && w.page.runtime().interactive(e.node)) result.add(e.node);
        });
        return result;
    }

    private static void reveal(DesktopWindow w, DesktopTestInput input, SceneNode node) {
        for (int attempt = 0; attempt < 40; attempt++) {
            SceneGeometryView.Entry e = geometry(w, node);
            LayoutBox visible = hit(e);
            if (visible.width == e.box.width && visible.height == e.box.height && e.box.y > 3 && e.box.y + e.box.height < w.height() - 1) return;
            LayoutBox clip = e.clip == null ? new LayoutBox(0, 0, w.width(), w.height()) : e.clip;
            input.point(clip.x + clip.width / 2.0, clip.y + clip.height / 2.0);
            input.scroll.invoke(w.handle(), 0, e.box.y < clip.y + 4 ? 3 : -3);
            finitePresentations(w);
        }
        throw new AssertionError("字段外表面无法沿正式滚轮显露");
    }

    private static void tabTo(DesktopWindow w, DesktopTestInput input, SceneNode target) {
        int limit = geometry(w).ordered().size() + 1;
        for (int i = 0; i < limit; i++) {
            key(w, input, GLFW_KEY_TAB, 0);
            if (focused(w) == target) return;
        }
        throw new AssertionError("Tab 无法到达原字段");
    }

    private static void key(DesktopWindow w, DesktopTestInput input, int key, int modifiers) {
        input.key.invoke(w.handle(), key, 0, GLFW_PRESS, modifiers);
        input.key.invoke(w.handle(), key, 0, GLFW_RELEASE, modifiers);
        finitePresentations(w);
    }

    private static SceneNode focused(DesktopWindow w) {
        SceneNode[] value = {null};
        w.domain.execute(() -> value[0] = w.page.runtime().focusRouter().focused().get());
        return value[0];
    }

    private static boolean focusVisible(DesktopWindow w, SceneNode node) {
        boolean[] value = {false};
        w.domain.execute(() -> value[0] = w.page.runtime().focusRouter().focusVisible(node));
        return value[0];
    }

    private static long focusedCount(DesktopWindow w) { return w.semantics().entries.stream().filter(e -> e.focused).count(); }
    private static int edgeDifference(BufferedImage a, BufferedImage b, LayoutBox box) {
        int x = box.x + box.width / 2, y = box.y;
        return distance(a.getRGB(x, y), b.getRGB(x, y));
    }
    private static int distance(int a, int b) {
        int difference = 0;
        for (int shift = 0; shift < 24; shift += 8) difference = Math.max(difference, Math.abs((a >>> shift & 255) - (b >>> shift & 255)));
        return difference;
    }
    private static boolean same(LayoutBox a, LayoutBox b) { return a.x == b.x && a.y == b.y && a.width == b.width && a.height == b.height; }
    private static String box(LayoutBox b) { return b.x + "," + b.y + "," + b.width + "," + b.height; }
    private void check(String name, String label, boolean passed, String detail) {
        report.append(name).append('\t').append(label).append('\t').append(passed).append('\t').append(detail).append('\n');
        if (!passed) failures.add(name + "/" + label + " " + detail);
        System.out.println("INPUT_FOCUS_CHECK case=" + name + " check=" + label + " passed=" + passed + " " + detail);
    }
    private static void require(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
    private static final class Target {
        final String name;
        final SceneNode editor, surface;
        Target(String name, SceneNode editor, SceneNode surface) { this.name = name; this.editor = editor; this.surface = surface; }
    }

    private static final class Fixture {
        Signal<SceneTheme> theme;
        Signal<Boolean> enabled;
        Signal<SceneNode> destination;
        Signal<List<SceneNode>> children;
        SceneNode moveTarget;
        final List<SceneNode> editors = new ArrayList<>(), surfaces = new ArrayList<>();
        final List<SceneEditState> states = new ArrayList<>();
        SceneTheme custom(boolean changed) {
            return new SceneTheme(SceneTheme.light().textStyle, changed ? 0xffe1f2dc : 0xffedf3ff,
                    0xffa8b4c4, 0xffd5e4f2, 0xffbed3e3, changed ? 0xff75539c : 0xff267a68,
                    0xffd9dcdf, 0xff717477, 0xff252830, 0xffffffff, 6, 8);
        }
        void build(club.heiqi.uilib.ui.scene.host.ScenePageContext p) {
            theme = p.domain().signal(custom(false)); enabled = p.domain().signal(true);
            SceneAppearance style = new SceneAppearance(p, theme);
            SceneNode root = SceneAppearance.column(24).padding(40).size(Size.fill(1), Size.fill(1)).background(0xfffafafa).own(style.scope);
            for (int i = 0; i < 4; i++) {
                SceneEditState state = new SceneEditState(p.domain(), i == 3 ? "多行合同\n第二行" : "保留作者主题 " + i,
                        i == 3 ? SceneEditState.Mode.MULTI_LINE : SceneEditState.Mode.SINGLE_LINE);
                SceneNode editor = i == 3 ? SceneTextArea.create(p, state, theme, () -> false, s -> {})
                        : SceneTextField.create(p, state, theme, () -> false, s -> {}, SceneTextField.SubmitPolicy.CONFIRM, () -> "", i != 2);
                editor.padding(13).bind(NodeBinding.value(enabled, SceneNode::enabled));
                if (i == 3) editor.size(Size.fill(1), Size.fixed(100));
                SceneNode surface = i == 0 ? editor : style.field(editor);
                states.add(state); editors.add(editor); surfaces.add(surface); root.add(surface);
            }
            moveTarget = SceneAppearance.column(0).size(Size.fill(1), Size.natural());
            root.add(moveTarget);
            destination = p.domain().signal(surfaces.get(1));
            editors.get(1).bind(NodeBinding.move(destination));
            List<SceneNode> declared = new ArrayList<>(surfaces); declared.add(moveTarget);
            children = p.domain().signal(declared);
            root.bind(NodeBinding.ownedChildren(children));
            p.mountOwned(root);
        }
    }
}
