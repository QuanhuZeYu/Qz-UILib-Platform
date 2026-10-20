package club.heiqi.uilib.host.desktop;

import static club.heiqi.uilib.host.desktop.DesktopTestFrames.settle;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.geometry;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneAppearance;
import club.heiqi.uilib.ui.scene.control.SceneScrollViewport;
import club.heiqi.uilib.ui.scene.control.SceneTextArea;
import club.heiqi.uilib.ui.scene.control.SceneTextField;
import club.heiqi.uilib.ui.scene.control.SceneTheme;
import club.heiqi.uilib.ui.scene.edit.SceneEditState;
import club.heiqi.uilib.ui.scene.host.ScenePage;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.SceneGeometryView;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.PaintCommand;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;
import club.heiqi.uilib.ui.scene.semantics.SceneSemantics;
import club.heiqi.uilib.ui.scene.text.TextLayout;
import club.heiqi.uilib.ui.scene.text.TextPolicy;
import club.heiqi.uilib.ui.scene.text.font.nativefont.InkBounds;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/**
 * 真实 GLFW/FT/HB/GL 的透明选区回归；自动事件调用已安装的宿主回调，不冒称人工键盘或 OS IME。
 * 选区是一个覆盖域：文字簇数量、方向、滚动或小数行高不能让同一像素重复合成同一层颜色。
 */
public final class DesktopTextSelectionVerification extends TestApplication {
    private static final String MIXED = "WiWi office abc אבג العربية 中文 e\u0301 👩🏽‍💻   ";
    private Path output;
    private long presentation = 2_000_000;

    public static void main(String[] args) throws Exception {
        new DesktopTextSelectionVerification().run(args);
    }

    @Override protected void setup() throws Exception {
        output = Paths.get(arguments().length == 0 ? "text-selection-report" : arguments()[0]);
        Files.createDirectories(output);
    }

    @Override protected void runTests() throws Exception {
        scenario("单多行浅深色透明选区的实际像素、反向、滚动与混排", () -> {
            List<String> failures = new ArrayList<>();
            for (boolean dark : new boolean[] {false, true})
                for (boolean multi : new boolean[] {false, true}) verify(dark, multi, failures);
            require(failures.isEmpty(), String.join("\n", failures));
        });
        System.out.println("TEXT_SELECTION_DESKTOP visibleGLFW=true installedCallbacks=true actualPixels=true realFont=true osIme=false");
    }

    private void verify(boolean dark, boolean multi, List<String> failures) throws Exception {
        String name = (dark ? "dark-" : "light-") + (multi ? "area" : "field");
        Fixture page = new Fixture(dark, multi);
        Path configuration = Files.createTempDirectory(output, name + "-").resolve("desktop.yaml");
        try (DesktopWindow window = DesktopWindow.openWithServices(configuration, true, services -> page)) {
            glfwSetWindowSize(window.handle(), 820, 520);
            settle(window);
            require(glfwGetWindowAttrib(window.handle(), GLFW_VISIBLE) == GLFW_TRUE,
                    "选区验收需要真实可见 GLFW 窗口");
            DesktopTestInput input = new DesktopTestInput(window);
            input.focus.invoke(window.handle(), true);
            window.frame();
            try (DesktopTestInput.ScriptedCursor cursor = input.scriptedCursor()) {
                input.click(geometry(window, page.field));
                require(read(window, () -> window.page.runtime().focusRouter().focused().get()) == page.field,
                        "生产指针回调没有聚焦正式编辑控件");
                key(window, input, GLFW_KEY_A, GLFW_MOD_CONTROL);
                expectSelection(window, page, 0, page.text.length());
                sample(window, page, name + "-forward", failures);

                key(window, input, GLFW_KEY_END, GLFW_MOD_CONTROL);
                key(window, input, GLFW_KEY_HOME, GLFW_MOD_CONTROL | GLFW_MOD_SHIFT);
                expectSelection(window, page, page.text.length(), 0);
                sample(window, page, name + "-reverse", failures);

                SceneEditState.Snapshot before = read(window, () -> page.state.snapshot().get());
                SceneGeometryView beforeScroll = geometry(window);
                SceneScrollViewport.Snapshot viewport = read(window, () -> page.horizontal.snapshot(beforeScroll));
                require(viewport != null && viewport.maximum > 0, "反例没有形成真实水平溢出");
                int target = Math.max(1, viewport.maximum / 2);
                window.domain.execute(() -> page.horizontal.moveToPixels(target));
                window.frame();
                SceneGeometryView afterScroll = geometry(window);
                SceneScrollViewport.Snapshot moved = read(window, () -> page.horizontal.snapshot(afterScroll));
                require(moved.offset == target, "正式编辑视口没有按现有滚动端口移动");
                SceneEditState.Snapshot after = read(window, () -> page.state.snapshot().get());
                require(after.anchor == before.anchor && after.caret == before.caret && after.text.equals(before.text),
                        "水平查看修改了草稿或选区");
                sample(window, page, name + "-scrolled", failures);

                // 逻辑区间在 Bidi 行里可能分成两个视觉片段，不能用总包围盒填掉中间未选文字。
                int rtl = page.text.indexOf("אבג"), start = rtl - 2;
                key(window, input, GLFW_KEY_HOME, GLFW_MOD_CONTROL);
                for (int i = 0; i < start; i++) key(window, input, GLFW_KEY_RIGHT, 0);
                for (int i = start; i < rtl + 1; i++) key(window, input, GLFW_KEY_RIGHT, GLFW_MOD_SHIFT);
                expectSelection(window, page, start, rtl + 1);
                sample(window, page, name + "-bidi-partial", failures);

                if (multi) {
                    key(window, input, GLFW_KEY_END, GLFW_MOD_CONTROL);
                    key(window, input, GLFW_KEY_LEFT, GLFW_MOD_SHIFT);
                    key(window, input, GLFW_KEY_LEFT, GLFW_MOD_SHIFT);
                    expectSelection(window, page, page.text.length(), page.text.length() - 2);
                    sample(window, page, name + "-bidi-lf", failures);
                    window.domain.execute(() -> page.policy.set(new TextPolicy(
                            TextPolicy.Direction.AUTO, TextPolicy.Wrap.NORMAL, "und", 0, 20.5f)));
                    window.frame();
                    key(window, input, GLFW_KEY_HOME, GLFW_MOD_CONTROL);
                    key(window, input, GLFW_KEY_A, GLFW_MOD_CONTROL);
                    require(geometry(window, page.field).text.lines().size() > 2,
                            "多行反例没有形成软换行与小数行高");
                    sample(window, page, name + "-fractional-lines", failures);
                }
            }
        }
    }

    private void sample(DesktopWindow window, Fixture page, String name, List<String> failures) throws Exception {
        settle(window);
        int selection = read(window, () -> page.style.colors.get().editorTheme().hover);
        int textColor = read(window, () -> page.style.colors.get().editorTheme().textStyle.color);
        require((selection >>> 24) > 0 && (selection >>> 24) < 255, "必须使用正式半透明编辑主题");
        SceneGeometryView current = geometry(window);
        SceneGeometryView.Entry entry = current.get(page.field);
        LayoutBox inner = new LayoutBox(entry.box.x + page.field.padding(), entry.box.y + page.field.padding(),
                Math.max(0, entry.box.width - page.field.padding() * 2),
                Math.max(0, entry.box.height - page.field.padding() * 2));
        LayoutBox visible = entry.clip == null ? inner : inner.intersect(entry.clip);
        LayoutBox framebuffer = new LayoutBox(0, 0, window.width(), window.height());
        visible = visible.intersect(framebuffer);
        final LayoutBox sampled = visible;
        // 自动滚动条在文本之后覆盖选区；从公开语义和同帧几何取遮挡，不把它的颜色误报为选区合成。
        List<LayoutBox> scrollbars = read(window, () -> {
            List<LayoutBox> boxes = new ArrayList<>();
            for (SceneGeometryView.Entry candidate : current.ordered()) {
                SceneSemantics semantics = candidate.node.control() == null
                        ? null : candidate.node.control().semantics(candidate.node);
                if (semantics == null || semantics.values.role != SceneSemantics.Role.SCROLLBAR) continue;
                LayoutBox box = candidate.clip == null ? candidate.box : candidate.box.intersect(candidate.clip);
                box = box.intersect(sampled);
                if (box.width > 0 && box.height > 0) boxes.add(box);
            }
            return boxes;
        });
        List<LayoutBox> ranges = new ArrayList<>();
        List<LayoutBox> carets = new ArrayList<>();
        for (PaintCommand command : window.lastPlan.commands()) {
            if (command.kind != PaintCommand.Kind.RECT) continue;
            LayoutBox box = command.clip == null ? command.box : command.box.intersect(command.clip);
            box = box.intersect(visible);
            if (box.width == 0 || box.height == 0) continue;
            if (command.color == selection) ranges.add(box);
            else if (command.box.width == 1) carets.add(box);
        }
        require(!ranges.isEmpty(), "当前合法选区没有产生可见绘制");
        int width = window.width(), height = window.height();
        int[] coverage = new int[Math.multiplyExact(width, height)];
        boolean[] ink = new boolean[coverage.length];
        for (LayoutBox range : ranges)
            for (int y = range.y; y < range.y + range.height; y++)
                for (int x = range.x; x < range.x + range.width; x++) coverage[y * width + x]++;
        for (TextLayout.Line line : entry.text.lines())
            for (TextLayout.Glyph glyph : line.glyphs()) {
                InkBounds bounds = glyph.inkBounds;
                require(bounds.known, "实际字体缺少可用于避开文字墨迹的公开几何");
                int left = (int) Math.floor(inner.x + glyph.x + bounds.left) - 1;
                int top = (int) Math.floor(inner.y + glyph.y + bounds.top) - 1;
                int right = (int) Math.ceil(inner.x + glyph.x + bounds.right) + 1;
                int bottom = (int) Math.ceil(inner.y + glyph.y + bounds.bottom) + 1;
                LayoutBox box = new LayoutBox(left, top, Math.max(0, right - left), Math.max(0, bottom - top)).intersect(framebuffer);
                for (int y = box.y; y < box.y + box.height; y++)
                    for (int x = box.x; x < box.x + box.width; x++) ink[y * width + x] = true;
            }

        // 重新回放前先清目标；否则截图动作本身会把半透明计划重复合成，伪造本次缺陷。
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        glDisable(GL_SCISSOR_TEST);
        glClearColor((page.background >>> 16 & 255) / 255f, (page.background >>> 8 & 255) / 255f,
                (page.background & 255) / 255f, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        if (scissor) glEnable(GL_SCISSOR_TEST);
        window.fonts.beginPresentation(++presentation);
        try { window.replay(window.lastPlan); }
        finally { window.fonts.endPresentation(); }
        require(glGetError() == GL_NO_ERROR, "选区回放出现 GL 错误");
        BufferedImage image = DesktopTestReadback.readBack(window);
        ImageIO.write(image, "png", output.resolve(name + ".png").toFile());

        int expected = over(selection, page.background);
        int samples = 0, mismatch = 0, overlaps = 0, maximum = 0, glyphPixels = 0, gapSamples = 0, gapMismatch = 0;
        int scrollbarPixels = 0;
        String first = "";
        for (int y = visible.y; y < visible.y + visible.height; y++)
            for (int x = visible.x; x < visible.x + visible.width; x++) {
                int index = y * width + x, layers = coverage[index];
                maximum = Math.max(maximum, layers);
                if (layers > 1) overlaps++;
                // 覆盖层数仍检查全部范围，只有最终像素比较避开正式前景装饰。
                if (contains(scrollbars, x, y)) {
                    if (layers > 0) scrollbarPixels++;
                    continue;
                }
                int actual = image.getRGB(x, y);
                if (ink[index] && distance(actual, textColor) <= 12) glyphPixels++;
                if (layers == 0 || ink[index] || contains(carets, x, y)) continue;
                samples++;
                if (distance(actual, expected) > 2) {
                    mismatch++;
                    if (first.isEmpty()) first = "x=" + x + " y=" + y + " actual=" + hex(actual)
                            + " expected=" + hex(expected) + " layers=" + layers;
                }
            }
        SceneEditState.Snapshot state = read(window, () -> page.state.snapshot().get());
        int selectedStart = Math.min(state.anchor, state.caret), selectedEnd = Math.max(state.anchor, state.caret);
        for (TextLayout.Line line : entry.text.lines())
            for (TextLayout.Cluster cluster : line.clusters()) {
                if (cluster.advance < 4 || cluster.sourceEnd > selectedStart && cluster.sourceStart < selectedEnd) continue;
                int x = (int) Math.floor(inner.x + cluster.x + cluster.advance / 2);
                for (int y = inner.y + line.y; y < inner.y + line.y + line.lineHeight; y++) {
                    if (!visible.contains(x, y) || ink[y * width + x]
                            || contains(carets, x, y) || contains(scrollbars, x, y)) continue;
                    gapSamples++;
                    int actual = image.getRGB(x, y);
                    if (coverage[y * width + x] > 0 || distance(actual, page.background) > 2) {
                        gapMismatch++;
                        if (first.isEmpty()) first = "未选文字簇中心被覆盖 source=" + cluster.sourceStart
                                + " x=" + x + " y=" + y + " actual=" + hex(actual);
                    }
                }
            }
        StringBuilder evidence = new StringBuilder(name).append("\nselection=").append(hex(selection))
                .append(" background=").append(hex(page.background)).append(" expected=").append(hex(expected))
                .append("\nsamples=").append(samples).append(" mismatches=").append(mismatch)
                .append(" overlapPixels=").append(overlaps).append(" maximumLayers=").append(maximum)
                .append(" glyphPixels=").append(glyphPixels).append(" scrollbarPixels=").append(scrollbarPixels)
                .append(" gapSamples=").append(gapSamples)
                .append(" gapMismatches=").append(gapMismatch).append("\nfirstMismatch=").append(first).append('\n');
        for (LayoutBox range : ranges) evidence.append("range ").append(range.x).append(' ').append(range.y)
                .append(' ').append(range.width).append(' ').append(range.height).append('\n');
        for (LayoutBox bar : scrollbars) evidence.append("scrollbar ").append(bar.x).append(' ').append(bar.y)
                .append(' ').append(bar.width).append(' ').append(bar.height).append('\n');
        for (TextLayout.Line line : entry.text.lines()) {
            evidence.append("line y=").append(line.y).append(" height=").append(line.lineHeight).append('\n');
            for (TextLayout.Cluster cluster : line.clusters()) evidence.append("cluster source=")
                    .append(cluster.sourceStart).append("..").append(cluster.sourceEnd).append(" x=")
                    .append(cluster.x).append(" advance=").append(cluster.advance).append(" rtl=")
                    .append(cluster.rtl).append('\n');
        }
        Files.write(output.resolve(name + ".txt"), evidence.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("TEXT_SELECTION_SAMPLE " + name + " samples=" + samples + " mismatches=" + mismatch
                + " overlapPixels=" + overlaps + " maximumLayers=" + maximum + " glyphPixels=" + glyphPixels
                + " scrollbarPixels=" + scrollbarPixels);
        require(samples > 0 && glyphPixels > 0, "验收没有取得真实文字及选区空白像素");
        if (name.endsWith("-bidi-partial")) require(gapSamples > 0, "混排反例没有取得可见未选文字簇中心");
        if (overlaps != 0 || mismatch != 0 || gapMismatch != 0)
            failures.add(name + " 透明选区重复覆盖=" + overlaps + " 像素不符=" + mismatch
                    + " 未选区被覆盖=" + gapMismatch + " " + first);
    }

    private static final class Fixture implements ScenePage {
        final boolean dark, multi;
        final String text;
        final int background;
        SceneEditState state;
        Signal<TextPolicy> policy;
        SceneAppearance style;
        SceneNode field;
        SceneScrollViewport horizontal;

        Fixture(boolean dark, boolean multi) {
            this.dark = dark;
            this.multi = multi;
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < 5; i++) line.append(MIXED);
            text = line.toString() + (multi ? "\n" + line + "\nabc אבג\n" : "");
            background = new SceneAppearance.Palette(dark).background;
        }

        @Override public void build(ScenePageContext context) {
            Signal<SceneTheme> theme = context.domain().signal(dark ? SceneTheme.dark() : SceneTheme.light());
            style = new SceneAppearance(context, theme);
            state = new SceneEditState(context.domain(), text,
                    multi ? SceneEditState.Mode.MULTI_LINE : SceneEditState.Mode.SINGLE_LINE);
            policy = context.domain().signal(new TextPolicy(TextPolicy.Direction.AUTO, TextPolicy.Wrap.NONE, "und", 0, 0));
            field = (multi
                    ? SceneTextArea.create(context, state, () -> style.colors.get().editorTheme(), () -> false,
                            submission -> {}, SceneTextField.SubmitPolicy.CONFIRM, () -> SceneTextArea.TabMode.NAVIGATE, false)
                    : SceneTextField.create(context, state, () -> style.colors.get().editorTheme(), () -> false,
                            submission -> {}, SceneTextField.SubmitPolicy.CONFIRM, () -> "", false))
                    .size(Size.fixed(360), Size.fixed(multi ? 170 : 70))
                    .bind(NodeBinding.value(policy, SceneNode::textPolicy));
            horizontal = SceneScrollViewport.of(context, field, SceneNode.Axis.ROW);
            context.mountOwned(new SceneNode().size(Size.fill(1), Size.fill(1)).padding(30).background(background).add(field));
        }
    }

    private static void expectSelection(DesktopWindow window, Fixture page, int anchor, int caret) {
        SceneEditState.Snapshot value = read(window, () -> page.state.snapshot().get());
        require(value.anchor == anchor && value.caret == caret && value.text.equals(page.text),
                "生产键回调没有建立预期逻辑选区：" + value.anchor + ".." + value.caret);
    }

    private static void key(DesktopWindow window, DesktopTestInput input, int key, int mods) {
        input.key.invoke(window.handle(), key, 0, GLFW_PRESS, mods);
        input.key.invoke(window.handle(), key, 0, GLFW_RELEASE, mods);
        window.frame();
    }

    private static boolean contains(List<LayoutBox> boxes, int x, int y) {
        for (LayoutBox box : boxes) if (box.contains(x, y)) return true;
        return false;
    }

    /** 公开 ARGB source-over 的独立期望，只在背景空白像素核对一次选区覆盖。 */
    private static int over(int foreground, int background) {
        double alpha = (foreground >>> 24) / 255d;
        int value = 0xff000000;
        for (int shift : new int[] {16, 8, 0}) {
            int channel = (int) Math.round((foreground >>> shift & 255) * alpha
                    + (background >>> shift & 255) * (1 - alpha));
            value |= channel << shift;
        }
        return value;
    }

    private static int distance(int first, int second) {
        int maximum = 0;
        for (int shift : new int[] {16, 8, 0})
            maximum = Math.max(maximum, Math.abs((first >>> shift & 255) - (second >>> shift & 255)));
        return maximum;
    }

    private static String hex(int color) { return String.format("%08x", color); }

    private static <T> T read(DesktopWindow window, Supplier<T> action) {
        Object[] value = {null};
        window.domain.execute(() -> value[0] = action.get());
        @SuppressWarnings("unchecked") T result = (T) value[0];
        return result;
    }
}
