package club.heiqi.uilib.ui.scene.integration;

import static org.lwjgl.opengl.GL32C.*;

import club.heiqi.uilib.host.client.font.ClientFontServices;
import club.heiqi.uilib.host.client.font.FramebufferCoordinates;
import club.heiqi.uilib.host.client.gl.GlImageResources;
import club.heiqi.uilib.host.client.gl.GlPaintPlanRenderer;
import club.heiqi.uilib.host.client.gl.GlApi;
import club.heiqi.uilib.host.client.gl.GlProgramAttribute;
import club.heiqi.uilib.host.client.gl.GlProgramProvider;
import club.heiqi.uilib.host.client.gl.GlProgramScope;
import club.heiqi.uilib.host.client.gl.GlQuadRenderer;
import club.heiqi.uilib.host.client.gl.GlVertexProducer;
import club.heiqi.uilib.host.client.gl.GlGlyphAtlas;
import club.heiqi.uilib.host.client.font.context.FontProjection;
import club.heiqi.uilib.host.client.font.context.FontRenderContext;
import club.heiqi.uilib.ui.render.batch.OrderedQuadBatch;
import club.heiqi.uilib.host.client.gl.Lwjgl3GlApi;
import club.heiqi.uilib.host.font.IsolatedFontEngine;
import club.heiqi.uilib.testing.GlfwTestEnvironment;
import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.render.batch.OrderedQuadBatch.Quad;
import club.heiqi.uilib.ui.render.batch.OrderedQuadBatch.State;
import club.heiqi.uilib.ui.render.batch.OrderedQuadCollector;
import club.heiqi.uilib.ui.scene.control.SceneControl;
import club.heiqi.uilib.ui.scene.control.SceneImage;
import club.heiqi.uilib.ui.scene.host.SceneHost;
import club.heiqi.uilib.ui.scene.host.ScenePageSession;
import club.heiqi.uilib.ui.scene.image.ImageContent;
import club.heiqi.uilib.ui.scene.image.ImageOptions;
import club.heiqi.uilib.ui.scene.image.ImagePlacement;
import club.heiqi.uilib.ui.scene.image.ImageService;
import club.heiqi.uilib.ui.scene.image.ImageSource;
import club.heiqi.uilib.ui.scene.image.ImageState;
import club.heiqi.uilib.ui.scene.geometry.SceneClipRegion;
import club.heiqi.uilib.ui.scene.geometry.SceneMesh;
import club.heiqi.uilib.ui.scene.geometry.SceneTransform;
import club.heiqi.uilib.ui.scene.layout.ChildPlacements;
import club.heiqi.uilib.ui.scene.layout.Constraints;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.SceneChildMeasurer;
import club.heiqi.uilib.ui.scene.layout.MeasuredSize;
import club.heiqi.uilib.ui.scene.layout.SceneGeometryView;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.PaintCommand;
import club.heiqi.uilib.ui.scene.paint.PaintPlan;
import club.heiqi.uilib.ui.scene.paint.PaintPlanBuilder;
import club.heiqi.uilib.ui.scene.text.SceneTextMeasurer;
import club.heiqi.uilib.ui.scene.text.TextLayout;

import org.lwjgl.system.MemoryUtil;

import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

/** 不依赖数学：唯一scene子树顺序反例与正式GLhost斜线/clip/半透明像素。 */
public final class PaintPrimitiveScenario extends TestApplication {
    private static final int W = 256, H = 192;
    private static final LayoutBox VIEW = new LayoutBox(0, 0, W, H);
    private static final SceneTextMeasurer NO_TEXT =
            (value, style, width) -> new TextLayout(0, 0, style, Collections.emptyList());
    private File output;
    private boolean orderOnly;
    private ClientFontServices fonts;
    private GlPaintPlanRenderer renderer;

    public static void main(String[] args) throws Exception {
        new PaintPrimitiveScenario().run(args);
    }

    @Override
    protected void setup() throws Exception {
        output = new File(arguments().length == 0 ? "build/paint-primitives/gpu" : arguments()[0]);
        orderOnly = Arrays.asList(arguments()).contains("--order-only");
        require(output.isDirectory() || output.mkdirs(), "输出目录创建失败");
        if (orderOnly) return;
        GlfwTestEnvironment environment = own(new GlfwTestEnvironment());
        environment.window(W, H, "UILib paint primitives", GlfwTestEnvironment.Profile.CORE, 2);
        Lwjgl3GlApi gl = new Lwjgl3GlApi();
        fonts =
                own(
                        new ClientFontServices(
                                getClass().getClassLoader(),
                                1,
                                ClientFontServices.Settings.defaults(),
                                IsolatedFontEngine.shared(),
                                gl,
                                GlProgramProvider.NONE));
        GlImageResources images = own(new GlImageResources(gl, 8, 1024 * 1024));
        renderer = fonts.createPlanRenderer(images);
        onClose(() -> fonts.releasePlanRenderer(renderer));
        int texture = glGenTextures(), fbo = glGenFramebuffers();
        onClose(
                () -> {
                    glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    glDeleteFramebuffers(fbo);
                    glDeleteTextures(texture);
                });
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(
                GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "FBO失败");
        glViewport(0, 0, W, H);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_MULTISAMPLE);
        System.out.println(
                "vendor="
                        + glGetString(GL_VENDOR)
                        + " renderer="
                        + glGetString(GL_RENDERER)
                        + " version="
                        + glGetString(GL_VERSION));
    }

    private static int marker(int id) {
        return 0xFF000000 | ((id * 37 & 255) << 16) | ((id * 73 & 255) << 8) | (id * 19 & 255);
    }

    private static final class PlanHost implements SceneHost {
        PaintPlan plan;

        public int width() {
            return W;
        }

        public int height() {
            return H;
        }

        public long metricsRevision() {
            return 0;
        }

        public void replay(PaintPlan value) {
            plan = value;
        }
    }

    /** 只用正式测量依赖改变当前presentation排列，逻辑children保持原顺序。 */
    private static class MarkerControl implements SceneControl {
        final int id, tooltip;
        final Signal<Boolean> reverse;
        SceneNode omitted;
        int paints, posts, overlays;

        MarkerControl(int id, int tooltip, Signal<Boolean> reverse) {
            this.id = id;
            this.tooltip = tooltip;
            this.reverse = reverse;
        }

        @Override
        public List<ReadableSignal<?>> measureDependencies(SceneNode node) {
            return reverse == null ? Collections.emptyList() : Collections.singletonList(reverse);
        }

        @Override
        public MeasuredSize measure(
                SceneNode node, Constraints constraints, SceneTextMeasurer text) {
            return new MeasuredSize(100, 100);
        }

        @Override
        public ChildPlacements arrange(SceneNode node, LayoutBox box, SceneChildMeasurer childMeasurer) {
            List<SceneNode> children = new ArrayList<>(node.children());
            if (reverse != null && reverse.get()) Collections.reverse(children);
            List<ChildPlacements.Entry> result = new ArrayList<>();
            int index = 0;
            for (SceneNode child : children) {
                if (child == omitted) continue;
                result.add(
                        new ChildPlacements.Entry(
                                child, new LayoutBox(8 + index++ * 112, 12, 96, 96)));
            }
            return new ChildPlacements(result);
        }

        @Override
        public void paint(SceneNode node, SceneGeometryView geometry, PaintPlanBuilder target) {
            paints++;
            if (id != 0) {
                SceneGeometryView.Entry e = geometry.get(node);
                target.add(PaintCommand.rect(e.box, e.clip, marker(id)));
            }
        }

        @Override
        public void paintAfterChildren(
                SceneNode node, SceneGeometryView geometry, PaintPlanBuilder target) {
            posts++;
            if (id != 0) {
                SceneGeometryView.Entry e = geometry.get(node);
                target.add(
                        PaintCommand.line(
                                e.box.x,
                                e.box.y + e.box.height,
                                e.box.x + e.box.width,
                                e.box.y,
                                5,
                                e.clip,
                                marker(id + 1)));
            }
        }

        @Override
        public void paintOverlay(
                SceneNode node,
                SceneGeometryView geometry,
                PaintPlanBuilder target,
                SceneTextMeasurer text) {
            overlays++;
            if (tooltip != 0)
                target.add(
                        PaintCommand.rect(
                                new LayoutBox(4 + (tooltip - 90) * 26, H - 16, 24, 12),
                                VIEW,
                                marker(tooltip)));
        }
    }

    private void order(PaintPlan plan, int... ids) {
        List<Integer> actual = new ArrayList<>();
        for (PaintCommand command : plan.commands()) actual.add(command.color);
        List<Integer> expected = new ArrayList<>();
        for (int id : ids) expected.add(marker(id));
        require(actual.equals(expected), "树局部绘制顺序错误，actual=" + actual + " expected=" + expected);
    }

    private void sceneOrder() throws Exception {
        ReactiveDomain domain = ReactiveDomain.direct();
        PlanHost host = new PlanHost();
        Signal<Boolean>[] reverse = new Signal[1];
        SceneNode[] phantom = new SceneNode[2];
        MarkerControl[] controls = new MarkerControl[6];
        try (ScenePageSession page =
                ScenePageSession.open(
                        domain,
                        host,
                        NO_TEXT,
                        ctx -> {
                            reverse[0] = ctx.domain().signal(false);
                            controls[0] = new MarkerControl(0, 0, reverse[0]);
                            controls[1] = new MarkerControl(20, 90, null);
                            controls[2] = new MarkerControl(30, 0, null);
                            controls[3] = new MarkerControl(40, 91, null);
                            controls[4] = new MarkerControl(50, 92, null);
                            controls[5] = new MarkerControl(60, 93, null);
                            SceneNode root =
                                    new SceneNode()
                                            .size(Size.fill(1), Size.fill(1))
                                            .control(controls[0]);
                            SceneNode parent = new SceneNode().control(controls[1]);
                            parent.add(new SceneNode().control(controls[2]));
                            phantom[0] = new SceneNode().control(controls[4]).focusable(true);
                            phantom[1] =
                                    new SceneNode()
                                            .control(controls[5])
                                            .visible(false)
                                            .focusable(true);
                            controls[1].omitted = phantom[0];
                            parent.add(phantom[0]);
                            parent.add(phantom[1]);
                            root.add(parent);
                            root.add(new SceneNode().control(controls[3]));
                            ctx.mount(root);
                        })) {
            page.frame();
            order(host.plan, 20, 30, 31, 21, 40, 41, 90, 91);
            domain.execute(
                    () ->
                            require(
                                    page.pipeline().current().geometry.get(phantom[0]) == null
                                            && page.pipeline().current().geometry.get(phantom[1])
                                                    == null,
                                    "未定位/隐藏孩子进入输入几何"));
            require(
                    controls[4].paints == 0
                            && controls[4].posts == 0
                            && controls[4].overlays == 0
                            && controls[5].paints == 0
                            && controls[5].posts == 0
                            && controls[5].overlays == 0,
                    "未定位/隐藏孩子产生绘制");
            int calls = controls[1].posts;
            page.frame();
            require(controls[1].posts == calls, "无失效重做局部post");
            if (!orderOnly) save("subtree-order.png", render(host.plan));
            domain.execute(() -> reverse[0].set(true));
            page.frame();
            order(host.plan, 40, 41, 20, 30, 31, 21, 91, 90);
            if (!orderOnly) save("subtree-order-reversed.png", render(host.plan));
            System.out.println(
                    "parent→child→childPost→parentPost→sibling→siblingPost→tooltip；signal反转同树顺序通过");
        }
    }

    /** 服务只发布不可变资源事实；捕获与隐藏由真实SceneImage自行处理。 */
    private static final class ImmediateImages implements ImageService {
        public Lease acquire(ImageSource source, ReactiveDomain domain) {
            Signal<ImageState> state = domain.signal(ImageState.ready(source, 1, source.bitmap));
            return new Lease() {
                public ReadableSignal<ImageState> state() {
                    return state;
                }

                public void close() {}
            };
        }

        public void reload(ImageSource source) {}
    }

    private void capturedFallback() throws Exception {
        ReactiveDomain domain = ReactiveDomain.direct();
        PlanHost host = new PlanHost();
        SceneNode[] fallback = new SceneNode[2];
        MarkerControl[] control = new MarkerControl[2];
        try (ScenePageSession page =
                ScenePageSession.open(
                        domain,
                        host,
                        NO_TEXT,
                        ctx -> {
                            ImmediateImages service = new ImmediateImages();
                            ImageContent pixels =
                                    new ImageContent(1, 1, new byte[] {12, 24, 36, (byte) 255});
                            Signal<ImageSource> source =
                                    ctx.domain().signal(ImageSource.bitmap(pixels));
                            Signal<ImageOptions> options =
                                    ctx.domain()
                                            .signal(
                                                    ImageOptions.defaults()
                                                            .size(
                                                                    // MarkerControl 的正式分配是 96；测试输入也必须尊重 fixed 合同。
                                                                    Size.fixed(96),
                                                                    Size.fixed(96)));
                            control[0] = new MarkerControl(70, 94, null);
                            control[1] = new MarkerControl(80, 95, null);
                            fallback[0] = new SceneNode().control(control[0]);
                            fallback[1] = new SceneNode().control(control[1]);
                            fallback[0].add(
                                    SceneImage.create(service, source, options, fallback[1]));
                            SceneNode image =
                                    SceneImage.create(service, source, options, fallback[0]);
                            SceneNode root =
                                    new SceneNode()
                                            .size(Size.fill(1), Size.fill(1))
                                            .control(new MarkerControl(0, 0, null));
                            root.add(image);
                            root.add(new SceneNode().control(new MarkerControl(40, 96, null)));
                            ctx.mount(root);
                        })) {
            page.frame();
            domain.execute(
                    () ->
                            require(
                                    page.pipeline().current().geometry.get(fallback[0]) == null
                                            && page.pipeline().current().geometry.get(fallback[1])
                                                    == null,
                                    "captured fallback变成第二输入树"));
            PaintCommand image = host.plan.commands().get(0);
            require(image.kind == PaintCommand.Kind.IMAGE, "ready图像未封闭备用分支");
            require(image.pendingPaint == image.failedPaint, "备用分支重复捕获");
            List<PaintCommand> inner = image.pendingPaint.commands();
            require(
                    inner.size() == 3
                            && inner.get(0).color == marker(70)
                            && inner.get(1).kind == PaintCommand.Kind.IMAGE
                            && inner.get(2).color == marker(71),
                    "备用parent局部post逃出自身内容");
            order(inner.get(1).pendingPaint, 80, 81);
            require(
                    control[0].paints == 1
                            && control[0].posts == 1
                            && control[0].overlays == 1
                            && control[1].paints == 1
                            && control[1].posts == 1
                            && control[1].overlays == 1,
                    "嵌套captured fallback重复绘制");
            List<PaintCommand> commands = host.plan.commands();
            require(
                    commands.size() == 5
                            && commands.get(1).color == marker(40)
                            && commands.get(2).color == marker(41)
                            && commands.get(3).kind == PaintCommand.Kind.IMAGE_BRANCH_OVERLAY
                            && commands.get(3).branchSource == image
                            && commands.get(4).color == marker(96),
                    "overlay未沿既有全页投影顺序");
            PaintPlan overlays = commands.get(3).pendingPaint;
            require(
                    overlays.commands().size() == 2
                            && overlays.commands().get(0).color == marker(94)
                            && overlays.commands().get(1).kind
                                    == PaintCommand.Kind.IMAGE_BRANCH_OVERLAY
                            && overlays.commands().get(1).branchSource == inner.get(1),
                    "嵌套overlay未复用对应IMAGE资源选择");
            order(overlays.commands().get(1).pendingPaint, 95);
            // 回放闭包不回访已关闭的live节点；准备只按本计划的冻结图像事实选择分支。
            PaintPlan captured = host.plan;
            SceneTransform outer = SceneTransform.translation(12, 8);
            PaintPlan mapped = captured.transformed(outer, SceneClipRegion.rectangle(VIEW));
            PaintCommand mappedImage = mapped.commands().get(0);
            PaintCommand mappedOverlay = mapped.commands().get(3);
            require(mappedOverlay.branchSource == mappedImage
                    && mappedOverlay.pendingPaint.commands().get(1).branchSource == mappedImage.pendingPaint.commands().get(1),
                    "变换备用闭包丢失branchSource同代identity");
            require(mappedImage.pendingPaint.commands().get(0).transform.x(0, 0) == 12
                    && mappedImage.pendingPaint.commands().get(0).transform.y(0, 0) == 8,
                    "备用片段漏变换或重复应用父矩阵");
            page.close();
            if (!orderOnly) {
                int[] ready = render(captured);
                save("captured-ready.png", ready);
                int[] mappedReady = render(mapped);
                require(pixel(mappedReady, 22, 18) == pixel(ready, 10, 10), "READY图片或后继图元未正确应用一次外层变换");
                require(
                        pixel(ready, 110, H - 12) == 0 && pixel(ready, 140, H - 12) == 0,
                        "ready分支泄漏备用全页overlay");
                // 合法上传预算：透明图片先消耗一次upload，冻结闭包的资源本帧真实PENDING。
                ImageContent budgetImage = new ImageContent(1, 1, new byte[4]);
                LayoutBox budgetBox = new LayoutBox(0, 0, 1, 1);
                PaintPlanBuilder bounded = new PaintPlanBuilder();
                bounded.add(
                        PaintCommand.image(
                                budgetBox,
                                VIEW,
                                ImagePlacement.fit(
                                        budgetImage, budgetBox, ImageOptions.defaults())));
                for (PaintCommand command : captured.commands()) bounded.add(command);
                PaintPlan boundedPlan = bounded.build();
                boolean[] pendingFact = {false}, readyFact = {false}, failureFact = {false};
                long identity = image.image.content.identity;
                try (GlImageResources pendingImages =
                        new GlImageResources(
                                new Lwjgl3GlApi(),
                                8,
                                1024 * 1024,
                                4,
                                1,
                                (resource, pending, failure) -> {
                                    if (resource == identity) {
                                        pendingFact[0] |= pending;
                                        readyFact[0] |= !pending && failure == null;
                                        failureFact[0] |= failure != null;
                                    }
                                })) {
                    GlPaintPlanRenderer pendingRenderer = fonts.createPlanRenderer(pendingImages);
                    try {
                        int[] pending = render(boundedPlan, pendingRenderer);
                        require(
                                pendingFact[0] && !readyFact[0] && !failureFact[0],
                                "上传预算未发布合法PENDING资源事实");
                        save("captured-pending.png", pending);
                        require(
                                pixel(pending, 56, 59) == marker(71)
                                        && pixel(pending, 64, 71) == marker(81),
                                "备用父/子post没有在各自子树结束后覆盖内容");
                        require(
                                pixel(pending, 110, H - 12) == marker(94)
                                        && pixel(pending, 140, H - 12) == marker(95)
                                        && pixel(pending, 166, H - 12) == marker(96),
                                "备用嵌套全页overlay投影或tooltip次序错误");
                        int[] nextReady = render(boundedPlan, pendingRenderer);
                        require(
                                readyFact[0] && !failureFact[0] && Arrays.equals(ready, nextReady),
                                "预算恢复后同闭包未真实READY或备用overlay仍泄漏");
                    } finally {
                        fonts.releasePlanRenderer(pendingRenderer);
                    }
                }
            }
            System.out.println("嵌套captured fallback内容/局部post/全页overlay投影闭包通过");
        }
    }

    private static Quad quad(PaintCommand command) {
        PaintCommand.Line line = command.line;
        return Quad.line(
                line.x0, line.y0, line.x1, line.y1, line.thickness, 0, 0, 0, 0, 0, command.color);
    }

    /** 新路径/纹理像素只在原正式GL入口执行；order-only不创建字体、纹理或窗口。 */
    private void gpuVector() throws Exception {
        SceneMesh mesh = SceneMesh.rectangle(0, 0, 44, 34);
        SceneTransform transform = SceneTransform.translation(60, 46).multiply(SceneTransform.rotationDegrees(25));
        SceneClipRegion mask = SceneClipRegion.of(SceneMesh.rectangle(0, 0, 24, 24).transformed(
                SceneTransform.translation(14, 2).multiply(SceneTransform.rotationDegrees(35))).transformed(transform));
        LayoutBox localClip = new LayoutBox(2, 3, 38, 27);
        PaintCommand path = PaintCommand.path(mesh, localClip, 0x804080C0).withGeometry(transform, mask);
        byte[] rgba = new byte[8 * 8 * 4];
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int i = (y * 8 + x) * 4; rgba[i] = (byte) (x < 4 ? 230 : 30);
            rgba[i + 1] = (byte) (y < 4 ? 210 : 40); rgba[i + 2] = 50; rgba[i + 3] = (byte) 255;
        }
        ImageContent pixels = new ImageContent(8, 8, rgba);
        LayoutBox imageBox = new LayoutBox(0, 0, 64, 64);
        PaintCommand texture = PaintCommand.image(imageBox, null, ImagePlacement.fit(pixels, imageBox, ImageOptions.defaults()))
                .withGeometry(SceneTransform.translation(152, 60).multiply(SceneTransform.rotationDegrees(-20)),
                        SceneClipRegion.of(SceneMesh.rectangle(138, 62, 54, 50).transformed(
                                SceneTransform.around(165, 87, SceneTransform.rotationDegrees(15)))));
        PaintPlanBuilder commands = new PaintPlanBuilder(); commands.add(path); commands.add(texture);
        commands.add(PaintCommand.rect(new LayoutBox(228, 165, 10, 10), VIEW, 0xFF22CC66));
        int[] result = render(commands.build()); save("vector-path-and-texture.png", result);
        SceneMesh visiblePath = path.effectiveClip().clip(mesh.transformed(transform));
        int inside = 0, outside = 0;
        for (int y = 30; y < 115; y++) for (int x = 25; x < 115; x++) {
            boolean center = visiblePath.contains(x + .5, y + .5), stable = true;
            for (double dx : new double[] {-.2, .2}) for (double dy : new double[] {-.2, .2})
                stable &= visiblePath.contains(x + .5 + dx, y + .5 + dy) == center;
            if (!stable) continue;
            require(pixel(result, x, y) == (center ? 0x80204060 : 0), "PATH退化quad或shapeclip像素错误 at=" + x + "," + y);
            if (center) inside++; else outside++;
        }
        SceneTransform inverse = texture.transform.inverse(); int samples = 0;
        for (int y = 35; y < 145; y++) for (int x = 125; x < 230; x++) {
            if (!interior(texture.effectiveClip(), x + .5, y + .5)) continue;
            double localX = inverse.x(x + .5, y + .5), localY = inverse.y(x + .5, y + .5);
            if (localX < 8 || localY < 8 || localX > 56 || localY > 56
                    || Math.abs(localX - 32) < 8 || Math.abs(localY - 32) < 8) continue;
            require(pixel(result, x, y) == (0xFF000032 | (localX < 32 ? 230 : 30) << 16
                    | (localY < 32 ? 210 : 40) << 8), "裁剪纹理四顶点UV被重建为包络采样");
            samples++;
        }
        require(inside > 0 && outside > 0 && samples > 0 && pixel(result, 232, 170) == 0xFF22CC66,
                "向量像素覆盖不足或命令scope污染后继identity矩形");
    }
    private void gpuHostVector() throws Exception {
        ImageContent content = new ImageContent(16, 16) {};
        LayoutBox local = new LayoutBox(-80, -60, 32, 24);
        SceneTransform transform = SceneTransform.translation(150, 110).multiply(SceneTransform.rotationDegrees(20));
        SceneClipRegion shape = SceneClipRegion.of(SceneMesh.rectangle(-78, -58, 26, 20).transformed(
                SceneTransform.around(-65, -48, SceneTransform.rotationDegrees(30))).transformed(transform));
        PaintCommand image = PaintCommand.image(local, null, ImagePlacement.fit(content, local,
                ImageOptions.defaults().fit(ImageOptions.Fit.STRETCH))).withGeometry(transform, shape);
        int[] calls = {0};
        try (GlImageResources resources = new GlImageResources(new Lwjgl3GlApi(), 2, 1024 * 1024)) {
            resources.hostItems(new GlImageResources.HostItems() {
                public boolean supports(ImageContent value) { return value == content; }
                public void render(ImagePlacement placement, LayoutBox clip, int width, int height) {
                    calls[0]++;
                    require(width == 32 && height == 24 && placement.x == 0 && placement.y == 0,
                            "变换host image未保持原局部分配capture或被world viewport先裁");
                    glDisable(GL_SCISSOR_TEST); glColorMask(true, true, true, true);
                    glClearColor(1, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
                    glEnable(GL_SCISSOR_TEST); glScissor(16, 0, 16, 24);
                    glClearColor(0, 1, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
                }
            });
            GlPaintPlanRenderer nativeRenderer = fonts.createPlanRenderer(resources);
            try {
                PaintPlanBuilder plan = new PaintPlanBuilder(); plan.add(image);
                plan.add(PaintCommand.rect(new LayoutBox(228, 165, 10, 10), VIEW, 0xFF22CC66));
                int[] result = render(plan.build(), nativeRenderer); save("vector-native-host-image.png", result);
                SceneTransform inverse = transform.inverse(); int samples = 0;
                for (int y = 10; y < 90; y++) for (int x = 70; x < 135; x++) {
                    if (!interior(image.effectiveClip(), x + .5, y + .5)) continue;
                    double px = inverse.x(x + .5, y + .5), py = inverse.y(x + .5, y + .5);
                    if (px < -78 || px > -50 || py < -58 || py > -38 || Math.abs(px + 64) < 2) continue;
                    require(pixel(result, x, y) == (px < -64 ? 0xFFFF0000 : 0xFF00FF00),
                            "宿主局部capture没有通过原batch映射shape/UV");
                    samples++;
                }
                require(calls[0] == 1 && samples > 0 && resources.retainedHostCaptureBytes() == 32L * 24 * 8
                        && pixel(result, 232, 170) == 0xFF22CC66,
                        "宿主图像重复委托/捕获范围错误或外部scope没有还原");
            } finally { fonts.releasePlanRenderer(nativeRenderer); }
        }
    }
    /** 原GL入口观察prepare到宿主producer的真实载荷；纯PATH不能借RECT背景取得white。 */
    private void hostPathPayload() {
        GlApi gl = fonts.gl(); double[] submittedArea = {0}; int[] submitted = {0}; boolean[] configured = {false};
        GlProgramProvider provider = new GlProgramProvider() {
            public boolean preview(FontRenderContext context) { return true; }
            public GlProgramScope open(FontRenderContext context) {
                // 此消费只记录原batch，不模拟宿主shader的GPU像素。
                return new GlProgramScope() {
                    public int programId() { return gl.glGetInteger(GL_CURRENT_PROGRAM); }
                    public List<GlProgramAttribute> attributes() { return Collections.emptyList(); }
                    public float[] normal() { return new float[] {0, 0, 1}; }
                    public int packedLight() { return 0; }
                    public float[] extended(String name, int type) { return null; }
                    public void configure(int vao, int vbo, int ebo, boolean uv, boolean color, boolean lightmap, boolean normal) { }
                    public void pointer(int location, int size, int type, boolean normalized, int stride, long offset) { }
                    public void disable(int location) { }
                    public void bindTexture2D(int texture) { gl.glBindTexture(GL_TEXTURE_2D, texture); }
                    public void beforeDraw() { }
                    public void close() { }
                };
            }
            public GlVertexProducer createVertexProducer(GlApi port, int capacity) {
                return new GlVertexProducer() {
                    public void configure(GlProgramScope scope) { configured[0] = true; }
                    public long consume(OrderedQuadBatch batch, GlProgramScope scope, FontRenderContext context,
                            GlQuadRenderer.ResourceProvider resources) {
                        for (OrderedQuadBatch.Draw draw : batch.draws()) {
                            require(draw.state.material == GlQuadRenderer.MONOCHROME_COVERAGE, "纯PATH hostpass未使用white覆盖度材质");
                            GlQuadRenderer.Texture texture = resources.resolve(draw.state.resource, draw.state.generation);
                            require(texture.target == GL_TEXTURE_2D && glIsTexture(texture.id), "宿主pass未准备真实white/图像纹理");
                        }
                        for (Quad q : batch.quads()) {
                            submitted[0]++;
                            double[] xs = {q.x0, q.x1, q.x2, q.x3}, ys = {q.y0, q.y1, q.y2, q.y3};
                            float[] ss = {q.s0, q.s1, q.s2, q.s3}, ts = {q.t0, q.t1, q.t2, q.t3};
                            for (int i = 0; i < 4; i++) {
                                require(xs[i] >= 2 - .0001 && xs[i] <= 5 + .0001
                                        && ys[i] >= 0 - .0001 && ys[i] <= 10 + .0001,
                                        "world矩形callerclip只留在被忽略的State.clip，未裁真实顶点");
                                require(ss[i] == q.s0 && ts[i] == q.t0, "PATH新裁剪点丢失逐顶点white UV");
                            }
                            submittedArea[0] += triangleArea(q.x0, q.y0, q.x1, q.y1, q.x2, q.y2)
                                    + triangleArea(q.x0, q.y0, q.x2, q.y2, q.x3, q.y3);
                        }
                        return 0;
                    }
                    public void close() { }
                };
            }
        };
        float[] matrix = FramebufferCoordinates.orthographic(W, H);
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        FontRenderContext.MatrixState state = new FontRenderContext.MatrixState(matrix, identity, new float[] {1, 1, 1, 1},
                false, GL_ALWAYS, 0, false, 0, 0, 0, 0, new float[] {0, 0, 1}, new float[] {1, 1, 1}, -1);
        FontRenderContext context = new FontRenderContext(new FontProjection(matrix, identity, new int[] {0, 0, W, H}),
                gl.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), H, false, GL_LESS, false, false, new int[] {0, 0, W, H},
                state, gl.capabilities().contextIdentity, 1, 1);
        SceneClipRegion worldClip = SceneClipRegion.rectangle(new LayoutBox(2, 0, 3, 10));
        PaintCommand rawPath = PaintCommand.path(SceneMesh.rectangle(0, 0, 10, 10), new LayoutBox(2, 0, 3, 10), 0x80667788);
        require(rawPath.sceneGeometry && !PaintCommand.rect(new LayoutBox(0, 0, 10, 10), null, -1).sceneGeometry,
                "PATH与旧RECT工厂没有保留scene/caller几何联系的区别");
        PaintCommand path = PaintCommand.path(SceneMesh.rectangle(0, 0, 10, 10), null, 0x80667788)
                .withGeometry(SceneTransform.IDENTITY, worldClip);
        require(path.sceneGeometry, "显式identity几何入口丢失sceneGeometry标记");
        PaintPlan plan = new PaintPlanBuilder().add(path).build();
        try (GlPaintPlanRenderer backend = new GlPaintPlanRenderer(gl, fonts.rasterizer(), fonts.atlas(), 2, 16, provider);
                GlPaintPlanRenderer.Prepared prepared = backend.prepareDemandContext(plan, context,
                        new GlGlyphAtlas.UploadBudget(1024, 100_000_000L, 8), Collections.emptyList(), () -> {})) {
            require(prepared.demandCount() == 0, "纯PATH验证混入glyph需求");
            prepared.render(context);
        }
        require(configured[0] && submitted[0] > 0 && Math.abs(submittedArea[0] - 30) < .001,
                "纯PATH静默丢失或identity有限world矩形没有提交实际裁剪面积");
    }
    private void geometryContract() {
        PaintCommand command =
                PaintCommand.line(10, 20, 40, 60, 10, new LayoutBox(12, 24, 6, 9), 0x804080C0);
        Quad q = quad(command);
        // 3-4-5斜率：法线(4,-3)，两端不能被clip包络替换。
        require(
                q.x0 == 14
                        && q.y0 == 17
                        && q.x1 == 44
                        && q.y1 == 57
                        && q.x2 == 36
                        && q.y2 == 63
                        && q.x3 == 6
                        && q.y3 == 23,
                "LINE未成为恒定法向线宽的四角quad");
        Quad reversed = quad(PaintCommand.line(40, 60, 10, 20, 10, null, command.color));
        require(
                reversed.x0 == q.x2
                        && reversed.y0 == q.y2
                        && reversed.x1 == q.x3
                        && reversed.y1 == q.y3,
                "反向端点改变线几何");
        List<club.heiqi.uilib.ui.render.batch.OrderedQuadBatch> batches = new ArrayList<>();
        OrderedQuadCollector collector = new OrderedQuadCollector(8, batches::add);
        State state = new State(0, 0, 0, null);
        collector.add(state, quad(PaintCommand.line(5.5f, 9.5f, 5.5f, 9.5f, 4, null, -1)));
        collector.flush();
        require(batches.isEmpty(), "零长度直接端点线产生draw");
        for (float bad : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            try {
                PaintCommand.line(1, 2, 3, 4, bad, null, -1);
                throw new AssertionError("接受非法线宽=" + bad);
            } catch (IllegalArgumentException expected) {
            }
        }
        System.out.println("封闭端点、法向宽度、反向端点、零长度和非法线宽通过");
    }

    private void vectorBatchContract() {
        Quad old = new Quad(0, 0, 20, 20, .1f, .2f, .9f, .8f, 3, 0x804080C0);
        require(old.s0 == old.u0 && old.t0 == old.v0 && old.s1 == old.u1 && old.t1 == old.v0
                && old.s2 == old.u1 && old.t2 == old.v1 && old.s3 == old.u0 && old.t3 == old.v1,
                "旧quad构造未保留TL/TR/BR/BL采样合同");
        SceneTransform transform = SceneTransform.of(1, .25, .5, 1, 37, 29);
        SceneTransform inverse = transform.inverse();
        LayoutBox localClip = new LayoutBox(7, 3, 10, 14);
        SceneMesh diamond = SceneMesh.rectangle(0, 0, 8, 8).transformed(
                SceneTransform.translation(10, 4).multiply(SceneTransform.rotationDegrees(45))).transformed(transform);
        SceneClipRegion region = SceneClipRegion.of(diamond);
        List<club.heiqi.uilib.ui.render.batch.OrderedQuadBatch> batches = new ArrayList<>();
        OrderedQuadCollector collector = new OrderedQuadCollector(2, batches::add);
        collector.add(new State(1, 17, 23, localClip, transform, region), old);
        Quad after = new Quad(80, 70, 4, 6, 0, 0, 1, 1, 0, -1);
        collector.add(new State(0, 0, 0, null), after); collector.flush();
        double area = 0; int clippedQuads = 0; Quad last = null;
        for (club.heiqi.uilib.ui.render.batch.OrderedQuadBatch batch : batches)
            for (Quad q : batch.quads()) {
                last = q;
                if (q.layer != old.layer) continue;
                clippedQuads++;
                double[] xs = {q.x0, q.x1, q.x2, q.x3}, ys = {q.y0, q.y1, q.y2, q.y3};
                float[] ss = {q.s0, q.s1, q.s2, q.s3}, ts = {q.t0, q.t1, q.t2, q.t3};
                for (int i = 0; i < 4; i++) {
                    double x = inverse.x(xs[i], ys[i]), y = inverse.y(xs[i], ys[i]);
                    require(x >= localClip.x - .001 && x <= localClip.x + localClip.width + .001
                            && y >= localClip.y - .001 && y <= localClip.y + localClip.height + .001,
                            "变换后把局部clip当作world scissor");
                    require(Math.abs(ss[i] - (.1 + .8 * x / 20)) < .0001
                            && Math.abs(ts[i] - (.2 + .6 * y / 20)) < .0001,
                            "裁剪新顶点没有按原纹理坐标插值");
                }
                area += triangleArea(q.x0, q.y0, q.x1, q.y1, q.x2, q.y2)
                        + triangleArea(q.x0, q.y0, q.x2, q.y2, q.x3, q.y3);
            }
        double expected = region.clip(SceneMesh.rectangle(localClip.x, localClip.y, localClip.width, localClip.height)
                .transformed(transform)).area();
        require(clippedQuads > 0 && Math.abs(area - expected) < .001, "共享collector丢失精确shape交集或重复画三角");
        require(last != null && last.x0 == after.x0 && last.y0 == after.y0 && last.x2 == after.x2
                && last.y2 == after.y2 && last.layer == after.layer, "变换批次改变原有序输出或污染下一identity命令");
        // 同一矩形输入的非恒定UV由共享collector核对，不把framebuffer专用IMAGE送进world入口。
        SceneClipRegion effectiveRegion = SceneClipRegion.rectangle(new LayoutBox(2, 0, 3, 10));
        List<Quad> textured = new ArrayList<>();
        OrderedQuadCollector worldCollector = new OrderedQuadCollector(2, batch -> textured.addAll(batch.quads()));
        worldCollector.add(new State(GlQuadRenderer.COLOR_PREMULT, 0, 0, null, SceneTransform.IDENTITY,
                SceneClipRegion.of(effectiveRegion.mesh())), new Quad(0, 0, 10, 10, 0, 0, 1, 1, 0, -1));
        worldCollector.flush(); double worldArea = 0;
        for (Quad q : textured) {
            double[] xs = {q.x0, q.x1, q.x2, q.x3}, ys = {q.y0, q.y1, q.y2, q.y3};
            float[] ss = {q.s0, q.s1, q.s2, q.s3}, ts = {q.t0, q.t1, q.t2, q.t3};
            for (int i = 0; i < 4; i++) require(ss[i] >= .2f - .0001 && ss[i] <= .5f + .0001
                    && ts[i] >= -.0001 && ts[i] <= 1 + .0001 && Math.abs(ss[i] - xs[i] / 10) < .0001
                    && Math.abs(ts[i] - ys[i] / 10) < .0001, "identity有限worldclip未保持u[.2,.5]/v[0,1]插值");
            worldArea += triangleArea(q.x0, q.y0, q.x1, q.y1, q.x2, q.y2) + triangleArea(q.x0, q.y0, q.x2, q.y2, q.x3, q.y3);
        }
        require(!textured.isEmpty() && Math.abs(worldArea - 30) < .001, "同输入UV载荷没有保留精确面积30");
        System.out.println("共享collector仿射/精确裁剪/逐顶点UV与旧quad入口通过");
    }
    private static boolean interior(SceneClipRegion region, double x, double y) {
        return region.contains(x - .2, y - .2) && region.contains(x - .2, y + .2)
                && region.contains(x + .2, y - .2) && region.contains(x + .2, y + .2);
    }
    private static double triangleArea(double ax, double ay, double bx, double by, double cx, double cy) {
        return Math.abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) / 2;
    }
    private static Path2D path(Quad q) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(q.x0, q.y0);
        path.lineTo(q.x1, q.y1);
        path.lineTo(q.x2, q.y2);
        path.lineTo(q.x3, q.y3);
        path.closePath();
        return path;
    }

    /** 软件consumer逐像素采样同四角；没有bbox填充或小RECT线段近似。 */
    private static int[] software(PaintPlan plan) {
        int[] pixels = new int[W * H * 4];
        for (PaintCommand command : plan.commands()) {
            if (command.line.empty()) continue;
            Path2D polygon = path(quad(command));
            float alpha = (command.color >>> 24) / 255f;
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++) {
                    if (!polygon.contains(x + .5, y + .5)
                            || command.clip != null && !command.clip.contains(x, y)) continue;
                    int i = (y * W + x) * 4;
                    for (int channel = 0; channel < 4; channel++) {
                        int value = channel == 3 ? 255 : command.color >>> (16 - channel * 8) & 255;
                        pixels[i + channel] =
                                Math.round(value * alpha + pixels[i + channel] * (1 - alpha));
                    }
                }
        }
        return pixels;
    }

    private static int pixel(int[] data, int x, int y) {
        int i = (y * W + x) * 4;
        return data[i + 3] << 24 | data[i] << 16 | data[i + 1] << 8 | data[i + 2];
    }

    private int[] render(PaintPlan plan) {
        return render(plan, renderer);
    }

    private int[] render(PaintPlan plan, GlPaintPlanRenderer backend) {
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        try (GlPaintPlanRenderer.Prepared prepared = fonts.prepare(backend, plan, VIEW)) {
            prepared.render(FramebufferCoordinates.orthographic(W, H), H, false);
        }
        ByteBuffer buffer = MemoryUtil.memAlloc(W * H * 4);
        try {
            glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
            int[] pixels = new int[W * H * 4];
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++)
                    for (int c = 0; c < 4; c++)
                        pixels[(y * W + x) * 4 + c] =
                                buffer.get(((H - 1 - y) * W + x) * 4 + c) & 255;
            return pixels;
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    private void save(String name, int[] pixels) throws Exception {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4, alpha = pixels[i + 3];
                int rgb = alpha << 24;
                for (int c = 0; c < 3; c++) {
                    int straight =
                            alpha == 0
                                    ? 0
                                    : Math.min(255, Math.round(pixels[i + c] * 255f / alpha));
                    rgb |= straight << (16 - c * 8);
                }
                image.setRGB(x, y, rgb);
            }
        require(ImageIO.write(image, "png", new File(output, name)), "PNG写入失败");
    }

    private void roundedCoverage() throws Exception {
        PaintPlanBuilder target=new PaintPlanBuilder();
        LayoutBox[] boxes={new LayoutBox(12,14,90,64),new LayoutBox(72,40,100,72),new LayoutBox(190,14,36,28)};
        LayoutBox[] clips={null,new LayoutBox(84,48,72,48),null};
        int[] radius={12,16,0},colors={0x80ff6040,0x900060ff,0xff00aa00};
        for(int i=0;i<boxes.length;i++)club.heiqi.uilib.ui.scene.paint.ScenePaintShapes.rounded(target,boxes[i],clips[i],colors[i],radius[i]);
        int[] actual=render(target.build()),expected=new int[W*H*4];int partial=0,maxDifference=0;
        // 独立 RoundRectangle2D + 更密的面积采样作为几何参考，不消费被测 RECT 条带。
        for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
            double[] pixel=new double[4];
            for(int shape=0;shape<boxes.length;shape++) {
                LayoutBox b=boxes[shape],clip=clips[shape];
                if(x<b.x||y<b.y||x>=b.x+b.width||y>=b.y+b.height
                        ||clip!=null&&(x<clip.x||y<clip.y||x>=clip.x+clip.width||y>=clip.y+clip.height))continue;
                java.awt.geom.RoundRectangle2D rectangle=new java.awt.geom.RoundRectangle2D.Double(b.x,b.y,b.width,b.height,radius[shape]*2,radius[shape]*2);
                int count=0,density=32;
                for(int sy=0;sy<density;sy++)for(int sx=0;sx<density;sx++)
                    if(rectangle.contains(x+(sx+.5)/density,y+(sy+.5)/density))count++;
                double alpha=(colors[shape]>>>24)/255d*count/(density*density);
                for(int channel=0;channel<3;channel++)pixel[channel]=((colors[shape]>>>(16-channel*8))&255)*alpha+pixel[channel]*(1-alpha);
                pixel[3]=255*alpha+pixel[3]*(1-alpha);
                if(count>0&&count<density*density)partial++;
            }
            for(int channel=0;channel<4;channel++) {
                int at=(y*W+x)*4+channel;expected[at]=(int)Math.round(pixel[channel]);
                maxDifference=Math.max(maxDifference,Math.abs(expected[at]-actual[at]));
            }
        }
        save("rounded-coverage-gl.png",actual);save("rounded-coverage-reference.png",expected);
        require(partial>0&&maxDifference<=12,"圆角coverage/透明混合偏离独立面积参考: partial="+partial+" max="+maxDifference);
        require(actual[(70*W+160)*4+3]==0,"圆角图形越过正式clip");
        require(Math.abs(actual[(40*W+40)*4+3]-128)<=1,"圆角内部半透明被重复叠加");
        System.out.println("ROUNDED_COVERAGE partialPixels="+partial+" maxChannelDifference="+maxDifference+" independentSamples=32x32");
    }

    private void gpuLines() throws Exception {
        PaintPlanBuilder target = new PaintPlanBuilder();
        target.add(
                PaintCommand.line(20, 20, 100, 80, 8, new LayoutBox(32, 16, 52, 68), 0x80FF0000));
        target.add(
                PaintCommand.line(
                        120, 80, 200, 20, 5.5f, new LayoutBox(116, 24, 80, 52), 0x8000FF00));
        target.add(PaintCommand.line(20, 112, 100, 122, 3.25f, null, 0x800000FF));
        target.add(
                PaintCommand.line(
                        126, 104, 136, 170, 7, new LayoutBox(120, 116, 32, 40), 0x80FF00FF));
        target.add(PaintCommand.line(20, 150, 100, 150, 4, null, 0x80FFFF00));
        target.add(PaintCommand.line(220, 20, 220, 100, 6, null, 0x8000FFFF));
        target.add(PaintCommand.line(196, 132, 148, 164, 9, null, 0x8000FFFF));
        target.add(PaintCommand.line(196, 164, 148, 132, 9, null, 0x80FF0000));
        target.add(PaintCommand.line(8, 8, 8, 8, 12, null, -1));
        PaintPlan plan = target.build();
        int[] expected = software(plan), actual = render(plan);
        save("line-clip-alpha-software.png", expected);
        save("line-clip-alpha-gl.png", actual);
        int mismatch = 0, samples = 0;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                // GPU有有限子像素精度；只跳过数学边线上样本，其余逐通道比较含clip外的透明区。
                if (onBoundary(plan, x + .5, y + .5)) continue;
                samples++;
                int i = (y * W + x) * 4;
                for (int c = 0; c < 4; c++)
                    if (Math.abs(expected[i + c] - actual[i + c]) > 2) {
                        if (mismatch++ == 0)
                            System.err.println(
                                    "首个差异x="
                                            + x
                                            + " y="
                                            + y
                                            + " channel="
                                            + c
                                            + " expected="
                                            + expected[i + c]
                                            + " actual="
                                            + actual[i + c]);
                    }
            }
        require(mismatch == 0, "斜线软件/GL几何栅格不一致：" + mismatch);
        require(actual[(40 * W + 40) * 4 + 3] == 0, "clip内bbox被当成斜线填满");
        require(actual[(20 * W + 20) * 4 + 3] == 0, "裁剪外端点漏绘");
        require(
                actual[(150 * W + 50) * 4 + 3] >= 126 && actual[(150 * W + 50) * 4 + 3] <= 130,
                "半透明线变成不透明");
        require(
                actual[(148 * W + 172) * 4 + 3] >= 190 && actual[(148 * W + 172) * 4 + 3] <= 194,
                "线交叠未沿原序alpha混合");
        System.out.println("真实GPU line clip alpha，比较像素=" + samples + " mismatch=" + mismatch);
    }

    private static boolean onBoundary(PaintPlan plan, double x, double y) {
        for (PaintCommand command : plan.commands()) {
            Quad q = quad(command);
            double[] xs = {q.x0, q.x1, q.x2, q.x3};
            double[] ys = {q.y0, q.y1, q.y2, q.y3};
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                double dx = xs[j] - xs[i], dy = ys[j] - ys[i];
                double length = Math.hypot(dx, dy);
                if (length == 0) continue;
                double along = ((x - xs[i]) * dx + (y - ys[i]) * dy) / (length * length);
                if (along >= 0
                        && along <= 1
                        && Math.abs(dx * (y - ys[i]) - dy * (x - xs[i])) / length < .002)
                    return true;
            }
        }
        return false;
    }

    @Override
    protected void runTests() throws Exception {
        scenario("封闭line及四角几何", this::geometryContract);
        scenario("共享collector仿射形状裁剪及四顶点UV兼容", this::vectorBatchContract);
        scenario("当前唯一树局部post和全页tooltip顺序", this::sceneOrder);
        scenario("真实SceneImage嵌套captured fallback分支与overlay投影", this::capturedFallback);
        if (!orderOnly) {
            scenario("正式GLhost斜线线宽半透明clip与软件栅格", this::gpuLines);
            scenario("正式GL路径三角形与变换纹理精确裁剪", this::gpuVector);
            scenario("纯PATH宿主pass准备white并提交world精确裁剪载荷", this::hostPathPayload);
            scenario("宿主图片保留局部分配capture后经共享batch变换", this::gpuHostVector);
            scenario("圆角coverage真实像素与独立面积参考",this::roundedCoverage);
        }
    }
}
