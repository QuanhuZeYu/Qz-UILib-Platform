package club.heiqi.uilib.ui.scene.integration;

import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.testing.GlfwTestEnvironment;
import club.heiqi.uilib.testing.GlfwTestEnvironment.Profile;
import club.heiqi.uilib.host.font.IsolatedFontEngine;
import club.heiqi.uilib.host.client.gl.Lwjgl3GlApi;
import club.heiqi.uilib.host.client.gl.GlApi;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import club.heiqi.uilib.host.client.gl.GlProgramProvider;
import club.heiqi.uilib.host.client.gl.GlProgramScope;
import club.heiqi.uilib.host.client.font.context.FontProjection;
import club.heiqi.uilib.host.client.font.context.FontRenderContext;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.lwjgl.system.MemoryUtil;
import club.heiqi.uilib.host.client.font.ClientFontServices;
import club.heiqi.uilib.host.client.font.FramebufferCoordinates;
import club.heiqi.uilib.host.client.gl.GlGlyphAtlas;
import club.heiqi.uilib.host.client.gl.GlPaintPlanRenderer;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.paint.PaintCommand;
import club.heiqi.uilib.ui.scene.paint.PaintPlan;
import club.heiqi.uilib.ui.scene.paint.PaintPlanBuilder;
import club.heiqi.uilib.ui.scene.text.TextLayout;
import club.heiqi.uilib.ui.scene.text.TextStyle;
import club.heiqi.uilib.ui.scene.text.font.glyph.AsyncGlyphRasterizer;
import club.heiqi.uilib.ui.scene.text.font.glyph.GlyphKey;
import static org.lwjgl.opengl.GL32C.*;

/** 多consumer共享epoch真实GL场景；不启动MC，不以旧scene结果冒充新预算合同。 */
public final class PresentationEpochScenario extends TestApplication {
    private static final int W = 300, H = 120;
    private static final LayoutBox VIEW = new LayoutBox(0, 0, W, H);

    private static ClientFontServices owner(
            long bytes, long nanos, int calls, int demand, int ready) throws Exception {
        return owner(bytes, nanos, calls, demand, ready, new Lwjgl3GlApi());
    }

    private static ClientFontServices owner(
            long bytes, long nanos, int calls, int demand, int ready, GlApi gl) throws Exception {
        return new ClientFontServices(
                PresentationEpochScenario.class.getClassLoader(),
                1,
                new ClientFontServices.Settings(
                        new AsyncGlyphRasterizer.Budget(
                                128, 256, ready, 8 * 1024 * 1024, 4, 96 * 1024 * 1024, 16, 96),
                        GlGlyphAtlas.Mode.TEXTURE_2D,
                        new GlGlyphAtlas.Budget(256, 1, 8, 256, 128, 2 * 1024 * 1024),
                        new GlGlyphAtlas.UploadBudget(bytes, nanos, calls),
                        64,
                        demand),
                IsolatedFontEngine.shared(),
                gl,
                GlProgramProvider.NONE);
    }

    private static PaintPlan text(ClientFontServices owner, String text, int x) {
        TextLayout layout = owner.layout(text, new TextStyle("default", 24, 0xffe0c040), W);
        return new PaintPlanBuilder()
                .add(PaintCommand.text(new LayoutBox(x, 4, 90, H), VIEW, layout))
                .build();
    }

    private static List<TextLayout.Glyph> glyphs(PaintPlan plan) {
        List<TextLayout.Glyph> out = new ArrayList<TextLayout.Glyph>();
        for (PaintCommand c : plan.commands())
            if (c.text != null)
                for (TextLayout.Line line : c.text.lines()) out.addAll(line.glyphs());
        return out;
    }

    private static int[] pixels() {
        ByteBuffer bytes = MemoryUtil.memAlloc(W * H * 4);
        try {
            glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
            int[] out = new int[W * H * 4];
            for (int i = 0; i < out.length; i++) out[i] = bytes.get(i) & 255;
            return out;
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    private static void expect(Runnable action) {
        try {
            action.run();
            throw new AssertionError("生命周期拒绝没有发生");
        } catch (IllegalStateException expected) {
        }
    }

    private static void close(ClientFontServices owner) throws Exception {
        owner.close();
        if (!owner.awaitRasterizerClose(10000)) throw new AssertionError("worker未关闭");
    }

    private static PaintPlan hidden(PaintPlan visible) {
        TextLayout.Glyph g = glyphs(visible).get(0);
        if (!g.inkBounds.known) throw new AssertionError("缺少known墨迹");
        List<TextLayout.Glyph> all = new ArrayList<TextLayout.Glyph>();
        for (int i = 0; i < 10000; i++)
            all.add(
                    new TextLayout.Glyph(
                            g.source,
                            g.size,
                            g.glyphId,
                            0,
                            1,
                            0,
                            1,
                            10000 + i,
                            10000,
                            0,
                            0,
                            false,
                            false,
                            g.inkBounds));
        TextLayout.Line line =
                new TextLayout.Line("hidden", 0, 0, 0, 1, 0, 1, 20000, 0, 24, 4, 28, all);
        TextLayout layout =
                new TextLayout(
                        20000,
                        20000,
                        new TextStyle("default", 24, -1),
                        Collections.singletonList(line));
        return new PaintPlanBuilder()
                .add(PaintCommand.text(new LayoutBox(0, 0, 20000, 20000), VIEW, layout))
                .build();
    }

    private void coldAndCancellation() throws Exception {
        ClientFontServices owner = owner(0, 0, 0, 128, 1);
        onClose(() -> close(owner));
        {
            GlPaintPlanRenderer a = owner.createPlanRenderer(), b = owner.createPlanRenderer();
            PaintPlan pa = text(owner, "中文", 4), pb = text(owner, "AB", 100);
            owner.beginPresentation(0);
            try (GlPaintPlanRenderer.Prepared hidden = owner.prepare(a, hidden(pa), VIEW)) {
                if (hidden.demandCount() != 0 || owner.rasterizer().stats().started)
                    throw new AssertionError("clip10000进入union");
            }
            try (GlPaintPlanRenderer.Prepared p = owner.prepare(a, pa, VIEW)) {
                if (p.readyCount() != 0) throw new AssertionError("frame0不blank");
            }
            try (GlPaintPlanRenderer.Prepared p = owner.prepare(b, pb, VIEW)) {
                if (p.readyCount() != 0 || p.demandCount() != 2
                        || owner.presentationStats().demand != 4)
                    throw new AssertionError("局部Prepared或整帧需求并集错误");
                if (owner.rasterizer().stats().cancelled != 0)
                    throw new AssertionError("后consumer取消此前需求");
            }
            owner.endPresentation();
            long end = System.nanoTime() + 10000000000L;
            while (owner.rasterizer().stats().ready == 0) {
                if (System.nanoTime() > end) throw new AssertionError("worker没有进入ready容量屏障");
                Thread.sleep(1);
            }
            long cancelled = owner.rasterizer().stats().cancelled;
            int pending = owner.rasterizer().stats().pending;
            owner.beginPresentation(1);
            if (owner.rasterizer().stats().cancelled != cancelled)
                throw new AssertionError("begin取消尚未收集的持续需求");
            owner.endPresentation();
            long difference = owner.rasterizer().stats().cancelled - cancelled;
            if (difference < Math.max(0, pending - 1)) throw new AssertionError("end未取消queued过期需求");
            System.out.println(
                    "cold frame0blank union4 clip10000=0 nextEpochCancelled="
                            + difference
                            + " oldPending="
                            + pending
                            + " inflight<=1");
        }
    }

    private void sharedBudget() throws Exception {
        ClientFontServices owner = owner(1024, 1000000000, 1, 8, 128);
        onClose(() -> close(owner));
        {
            GlPaintPlanRenderer a = owner.createPlanRenderer(), b = owner.createPlanRenderer();
            PaintPlan[] plans = {text(owner, "中", 4), text(owner, "A", 94), text(owner, "😀", 184)};
            GlPaintPlanRenderer[] consumers = {a, b, b};
            int frame = 0;
            long totalBytes = 0, totalCalls = 0, partialFrames = 0;
            long end = System.nanoTime() + 20000000000L;
            for (; ; ) {
                owner.beginPresentation(frame);
                glClearColor(0, 0, 0, 0);
                glClear(GL_COLOR_BUFFER_BIT);
                boolean all = true;
                for (int i = 0; i < plans.length; i++)
                    try (GlPaintPlanRenderer.Prepared p =
                            owner.prepare(consumers[i], plans[i], VIEW)) {
                        for (TextLayout.Glyph g : glyphs(plans[i]))
                            if (p.resident(g) == null) all = false;
                        p.render(FramebufferCoordinates.orthographic(W, H), H, false);
                        ClientFontServices.PresentationStats s = owner.presentationStats();
                        if (s.uploadedBytes > 1024
                                || s.uploadCalls > 1
                                || s.demand > 8
                                || s.remainingBytes != 1024 - s.uploadedBytes
                                || s.remainingCalls != 1 - s.uploadCalls
                                || s.remainingNanos != Math.max(0, 1000000000L - s.prepareNanos))
                            throw new AssertionError("跨draw共享预算重置");
                    }
                // 相同可见key不能再次增加epoch需求或申请新的上传预算。
                try (GlPaintPlanRenderer.Prepared duplicate = owner.prepare(a, plans[0], VIEW)) {
                    if (owner.presentationStats().demand != 3)
                        throw new AssertionError("重复key扩大union");
                }
                ClientFontServices.PresentationStats s = owner.presentationStats();
                owner.endPresentation();
                totalBytes += s.uploadedBytes;
                totalCalls += s.uploadCalls;
                if (s.uploadCalls != 0 && s.demand > s.uploadCalls) partialFrames++;
                if (all) break;
                if (System.nanoTime() > end || frame++ > 1000)
                    throw new AssertionError(
                            "持续多consumer未全部ready frames="
                                    + frame
                                    + " rasterized="
                                    + owner.rasterizer().stats().rasterized);
                Thread.sleep(1);
            }
            int[] combined = pixels();
            glClear(GL_COLOR_BUFFER_BIT);
            owner.beginPresentation(++frame);
            for (int i = 0; i < plans.length; i++)
                try (GlPaintPlanRenderer.Prepared p = owner.prepare(consumers[i], plans[i], VIEW)) {
                    p.render(FramebufferCoordinates.orthographic(W, H), H, false);
                }
            owner.endPresentation();
            if (!Arrays.equals(combined, pixels())) throw new AssertionError("跨帧已ready画面改变");
            for (int i = 0; i < 3; i++) {
                int nonzero = 0;
                int x0 = i * 90;
                for (int y = 0; y < H; y++)
                    for (int x = x0; x < Math.min(W, x0 + 90); x++)
                        if (combined[(y * W + x) * 4 + 3] != 0) nonzero++;
                if (nonzero == 0) throw new AssertionError("consumer " + i + "最终留白");
            }
            if (owner.rasterizer().stats().rasterized != 3 || partialFrames == 0)
                throw new AssertionError("key去重/partial upload探针未命中");
            System.out.println(
                    "shared frames="
                            + frame
                            + " allConsumersReady=3 nativeRasterized=3 totalUploadedBytes="
                            + totalBytes
                            + " totalCalls="
                            + totalCalls
                            + " perEpochBytes<=1024 calls<=1 partialFrames="
                            + partialFrames
                            + " stableReadback=true");
            // begin/end、Prepared和异常路径均不允许隐式重置预算或泄漏Frame。
            owner.beginPresentation(10000);
            expect(() -> owner.beginPresentation(10000));
            try (GlPaintPlanRenderer.Prepared p = owner.prepare(a, plans[0], VIEW)) {
                expect(owner::endPresentation);
                expect(() -> owner.prepare(b, plans[1], VIEW));
                try {
                    p.render(new float[1], H, false);
                    throw new AssertionError("invalidMVP未拒绝");
                } catch (IllegalArgumentException expected) {
                }
            }
            owner.endPresentation();
            try {
                owner.prepare(a, null, VIEW);
                throw new AssertionError("nullPlan未拒绝");
            } catch (NullPointerException expected) {
            }
            if (owner.presentationStats().active || owner.presentationStats().activePrepared != 0)
                throw new AssertionError("auto异常泄漏epoch");
            try (GlPaintPlanRenderer.Prepared p = owner.prepare(a, plans[0], VIEW)) {
                if (!owner.presentationStats().active) throw new AssertionError("autoepoch缺失");
            }
            if (owner.presentationStats().active) throw new AssertionError("autoPrepared关闭未end");
            owner.beginPresentation(10001);
            try {
                try (GlPaintPlanRenderer.Prepared p = owner.prepare(a, plans[0], VIEW)) {
                    throw new IllegalArgumentException("consumer异常");
                }
            } catch (IllegalArgumentException expected) {
            } finally {
                owner.endPresentation();
            }
            expect(owner::endPresentation);
            System.out.println(
                    "lifecycle reentryRejected endWithPreparedRejected autoFailureClosed consumerFailureFinallyClosed=true");
        }
    }

    private void boundaryMode() throws Exception {
        ClientFontServices.Settings settings = ClientFontServices.Settings.defaults();
        ClientFontServices owner =
                new ClientFontServices(
                        PresentationEpochScenario.class.getClassLoader(),
                        1,
                        settings,
                        true,
                        IsolatedFontEngine.shared(),
                        new Lwjgl3GlApi(),
                        GlProgramProvider.NONE);
        onClose(() -> close(owner));
        {
            if (owner.hasGlResources()
                    || owner.presentationStats().active
                    || owner.rasterizer().stats().started)
                throw new AssertionError("无draw边界owner不lazy");
            GlPaintPlanRenderer a = owner.createPlanRenderer(), b = owner.createPlanRenderer();
            PaintPlan pa = text(owner, "中", 4), pb = text(owner, "A", 94);
            try (GlPaintPlanRenderer.Prepared p = owner.prepare(a, pa, VIEW)) {}
            ClientFontServices.PresentationStats first = owner.presentationStats();
            if (!first.active || first.activePrepared != 0 || first.demand != 1)
                throw new AssertionError("Prepared.close提前结束production epoch");
            try (GlPaintPlanRenderer.Prepared p = owner.prepare(b, pb, VIEW)) {}
            ClientFontServices.PresentationStats second = owner.presentationStats();
            if (!second.active
                    || second.epochId != first.epochId
                    || second.demand != 2
                    || second.uploadCalls > settings.upload.calls
                    || second.uploadedBytes > settings.upload.bytes
                    || second.prepareNanos < first.prepareNanos)
                throw new AssertionError("present前多个consumer没有共享同epoch");
            owner.endPresentation();
            if (owner.presentationStats().active) throw new AssertionError("模拟beforePresent未结束");
            try (GlPaintPlanRenderer.Prepared p = owner.prepare(a, pa, VIEW)) {}
            ClientFontServices.PresentationStats next = owner.presentationStats();
            if (!next.active || next.epochId == first.epochId || next.demand != 1)
                throw new AssertionError("present后下次draw没有新epoch");
            try {
                try (GlPaintPlanRenderer.Prepared p = owner.prepare(b, pb, VIEW)) {
                    throw new IllegalArgumentException("consumer异常");
                }
            } catch (IllegalArgumentException expected) {
            } finally {
                owner.endPresentation();
            }
            if (owner.presentationStats().active || owner.presentationStats().activePrepared != 0)
                throw new AssertionError("真实边界异常泄漏");
            System.out.println(
                    "productionBoundary lazyBeforeDraw=true multipleConsumersSameEpoch=true PreparedCloseKeepsEpoch=true beforePresentEnds=true nextDrawNewEpoch=true exceptionFinallyClosed=true");
        }
    }

    private void unionAndTime() throws Exception {
        ClientFontServices owner = owner(1024, 0, 1, 2, 128);
        onClose(() -> close(owner));
        {
            GlPaintPlanRenderer renderer = owner.createPlanRenderer();
            owner.beginPresentation(1);
            String[] texts = {"A", "B", "C", "D", "E"};
            for (String text : texts)
                try (GlPaintPlanRenderer.Prepared p =
                        owner.prepare(renderer, text(owner, text, 0), VIEW)) {}
            ClientFontServices.PresentationStats s = owner.presentationStats();
            owner.endPresentation();
            if (s.demand != 2
                    || s.rejectedDemand != 3
                    || s.uploadCalls != 0
                    || s.uploadedBytes != 0
                    || s.remainingNanos != 0) throw new AssertionError("union/time预算穿透");
            if (owner.rasterizer().stats().pending + owner.rasterizer().stats().cacheEntries > 2)
                throw new AssertionError("未获union名额key被排队");
            System.out.println(
                    "union bounded=2 rejected=3 zeroNanosAcross5Consumers uploadCalls=0");
        }
    }

    /** 稳定字形分成多次标准文字绘制时，不能反复准备此前consumer的累计前缀。 */
    private void warmConsumers() throws Exception {
        boolean[] measuring = {false};
        int[] unpackReads = {0}, unpackWrites = {0};
        GlApi actual = new Lwjgl3GlApi();
        GlApi counted = (GlApi) Proxy.newProxyInstance(
                GlApi.class.getClassLoader(), new Class<?>[] {GlApi.class},
                (proxy, method, args) -> {
                    if (measuring[0]) {
                        if (method.getName().equals("glGetInteger") && unpack((Integer) args[0]))
                            unpackReads[0]++;
                        if (method.getName().equals("glPixelStorei")) unpackWrites[0]++;
                    }
                    try {
                        return method.invoke(actual, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        ClientFontServices owner = owner(65536, 1000000000, 128, 128, 128, counted);
        onClose(() -> close(owner));
        GlPaintPlanRenderer renderer = owner.createPlanRenderer();
        PaintPlan[] plans = {text(owner, "A", 4), text(owner, "B", 74),
                text(owner, "C", 144), text(owner, "D", 214)};
        long end = System.nanoTime() + 10000000000L;
        int epoch = 0;
        for (;;) {
            boolean ready = true;
            owner.beginPresentation(epoch++);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            for (PaintPlan plan : plans)
                try (GlPaintPlanRenderer.Prepared prepared = owner.prepare(renderer, plan, VIEW)) {
                    if (glyphs(plan).size() != 1) throw new AssertionError("夹具须为单字形");
                    if (prepared.resident(glyphs(plan).get(0)) == null) ready = false;
                    prepared.render(FramebufferCoordinates.orthographic(W, H), H, false);
                }
            owner.endPresentation();
            if (ready) break;
            if (System.nanoTime() > end) throw new AssertionError("热字形未就绪");
            Thread.sleep(1);
        }
        int[] expected = pixels();
        long rasterized = owner.rasterizer().stats().rasterized;
        long uploads = owner.atlas().stats().uploadCalls;
        int[] local = new int[plans.length], union = new int[plans.length];
        owner.beginPresentation(epoch);
        glClear(GL_COLOR_BUFFER_BIT);
        try {
            for (int i = 0; i < plans.length; i++) {
                GlPaintPlanRenderer.Prepared prepared;
                measuring[0] = true;
                try {
                    prepared = owner.prepare(renderer, plans[i], VIEW);
                } finally {
                    measuring[0] = false;
                }
                try (GlPaintPlanRenderer.Prepared current = prepared) {
                    local[i] = current.demandCount();
                    union[i] = owner.presentationStats().demand;
                    if (current.resident(glyphs(plans[i]).get(0)) == null)
                        throw new AssertionError("稳定字形丢失");
                    current.render(FramebufferCoordinates.orthographic(W, H), H, false);
                }
            }
        } finally {
            owner.endPresentation();
        }
        System.out.println("warmConsumers local=" + Arrays.toString(local)
                + " union=" + Arrays.toString(union) + " unpackReads=" + unpackReads[0]
                + " unpackWrites=" + unpackWrites[0]);
        if (!Arrays.equals(union, new int[] {1, 2, 3, 4}))
            throw new AssertionError("整帧需求并集被局部准备覆盖");
        if (!Arrays.equals(expected, pixels()) || owner.rasterizer().stats().rasterized != rasterized
                || owner.atlas().stats().uploadCalls != uploads)
            throw new AssertionError("稳定字形重新生成、上传或画面改变");
        if (!Arrays.equals(local, new int[] {1, 1, 1, 1})
                || unpackReads[0] != 0 || unpackWrites[0] != 0)
            throw new AssertionError("热consumer重复准备累计需求或无效上传状态");
    }

    /** 两种实际格式各可单独装页；同epoch先就绪的可见组不能被后consumer反复淘汰。 */
    private void tightVisibleGroups() throws Exception {
        ClientFontServices.Settings defaults = ClientFontServices.Settings.defaults();
        ClientFontServices owner = new ClientFontServices(
                PresentationEpochScenario.class.getClassLoader(), 1,
                new ClientFontServices.Settings(defaults.cpu, GlGlyphAtlas.Mode.TEXTURE_2D,
                        new GlGlyphAtlas.Budget(256, 1, 1, 8, 8, 256L * 256 * 4),
                        new GlGlyphAtlas.UploadBudget(1024 * 1024, 1000000000, 8), 64, 8),
                IsolatedFontEngine.shared(), new Lwjgl3GlApi(), GlProgramProvider.NONE);
        onClose(() -> close(owner));
        GlPaintPlanRenderer a = owner.sharedPlanRenderer(), b = owner.createPlanRenderer();
        float[] projection = FramebufferCoordinates.orthographic(W, H);
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        FontRenderContext context = new FontRenderContext(
                new FontProjection(projection, identity, new int[] {0, 0, W, H}),
                glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), H, false, GL_LESS, false, false,
                new int[] {0, 0, W, H}, FontRenderContext.MatrixState.ui(projection, identity),
                owner.gl().capabilities().contextIdentity, 1, 0);
        PaintPlan pa = text(owner, "A", 4), pb = text(owner, "😀", 94);
        if (glyphs(pa).size() != 1 || glyphs(pb).size() != 1)
            throw new AssertionError("紧预算夹具须各含一个实际字形");
        GlyphKey ka = GlyphKey.of(glyphs(pa).get(0)), kb = GlyphKey.of(glyphs(pb).get(0));
        owner.rasterizer().request(ka);
        owner.rasterizer().request(kb);
        long deadline = System.nanoTime() + 10000000000L;
        while (owner.rasterizer().peek(ka) == null || owner.rasterizer().peek(kb) == null) {
            owner.pumpReady();
            if (System.nanoTime() > deadline) throw new AssertionError("紧预算CPU字形未就绪");
            Thread.sleep(1);
        }
        club.heiqi.uilib.ui.scene.text.font.nativefont.GlyphBitmap mono = owner.rasterizer().peek(ka),
                color = owner.rasterizer().peek(kb);
        if (mono.color || !color.color || mono.width + 2 > 256 || mono.height + 2 > 256
                || color.width + 2 > 256 || color.height + 2 > 256)
            throw new AssertionError("mono/color须已CPUready且均可独立装入单页");
        long rasterized = owner.rasterizer().stats().rasterized;
        long firstResource = -1, firstGeneration = -1, firstUploads = -1, firstBytes = -1;
        int[] firstPixels = null;
        boolean stable = true;
        boolean[] ready = new boolean[9];
        long[] uploads = new long[9], evictions = new long[9];
        for (int epoch = 0; epoch < 3; epoch++) {
            owner.beginPresentation(epoch);
            try {
                for (int draw = 0; draw < 3; draw++) {
                    // evict会保护任何纹理单元仍绑定的页，显式解绑才能命中真实预算压力。
                    unbindGlyphPages();
                    PaintPlan plan = draw == 1 ? pb : pa;
                    int index = epoch * 3 + draw;
                    try (GlPaintPlanRenderer.Prepared p = owner.prepare(draw == 1 ? b : a, plan, context)) {
                        GlGlyphAtlas.Resident r = p.resident(glyphs(plan).get(0));
                        ready[index] = r != null;
                        if (index == 0 && r != null) {
                            firstResource = r.resource;
                            firstGeneration = r.generation;
                            firstUploads = owner.atlas().stats().uploadCalls;
                            firstBytes = owner.atlas().stats().uploadedBytes;
                        }
                        if (draw != 1 && (r == null || r.resource != firstResource
                                || r.generation != firstGeneration)) stable = false;
                        if (p.demandCount() != 1) throw new AssertionError("短Frame重复准备累计需求");
                        glClearColor(0, 0, 0, 0);
                        glClear(GL_COLOR_BUFFER_BIT);
                        p.render(context);
                        boolean ink = false;
                        int[] image = pixels();
                        if (index == 0) firstPixels = image;
                        for (int i = 3; i < image.length; i += 4) if (image[i] != 0) ink = true;
                        if (ink != ready[index]) throw new AssertionError("紧预算ready与真实像素不符");
                    }
                    uploads[index] = owner.atlas().stats().uploadCalls;
                    evictions[index] = owner.atlas().stats().evictedGroups;
                    if (owner.presentationStats().activePrepared != 0)
                        throw new AssertionError("短Prepared未释放");
                }
            } finally {
                owner.endPresentation();
            }
        }
        System.out.println("tightVisibleGroups CPUready=mono+color maxGroups=1 ready="
                + Arrays.toString(ready) + " uploads=" + Arrays.toString(uploads)
                + " evictions=" + Arrays.toString(evictions));
        for (int i = 0; i < ready.length; i++)
            stable &= ready[i] == (i % 3 != 1) && uploads[i] == firstUploads && evictions[i] == 0;
        if (!stable || owner.atlas().stats().uploadedBytes != firstBytes
                || owner.atlas().stats().rejected == 0)
            throw new AssertionError("超预算可见组反复淘汰/上传，B应留白且firstA持续ready");
        owner.beginPresentation(3);
        try {
            unbindGlyphPages();
            try (GlPaintPlanRenderer.Prepared p = owner.prepare(b, pb, context)) {
                GlGlyphAtlas.Resident r = p.resident(glyphs(pb).get(0));
                if (r == null || !r.color || owner.atlas().stats().evictedGroups != 1
                        || owner.atlas().stats().uploadCalls <= firstUploads)
                    throw new AssertionError("下一epoch仅B需求未释放A并前进");
                p.render(context);
            }
        } finally {
            owner.endPresentation();
        }
        // 同一个memo曾保存A的UV；B已淘汰其组，再回A必须重建且保留原像素。
        owner.beginPresentation(4);
        try {
            unbindGlyphPages();
            try (GlPaintPlanRenderer.Prepared p = owner.prepare(a, pa, context)) {
                GlGlyphAtlas.Resident r = p.resident(glyphs(pa).get(0));
                if (r == null || r.resource == firstResource || owner.atlas().stats().evictedGroups != 2)
                    throw new AssertionError("缓存A没有随旧图集组淘汰重建");
                glClear(GL_COLOR_BUFFER_BIT);
                p.render(context);
                if (!Arrays.equals(firstPixels, pixels())) throw new AssertionError("缓存A复用了已淘汰的UV");
            }
        } finally { owner.endPresentation(); }
        if (owner.rasterizer().stats().rasterized != rasterized || owner.atlas().stats().groups != 1)
            throw new AssertionError("紧预算重复光栅或组数越界");
        long beforeStandalone = owner.atlas().stats().evictedGroups;
        PaintPlan[] standalone = {pb, pa};
        for (PaintPlan plan : standalone) {
            unbindGlyphPages();
            try (GlPaintPlanRenderer.Prepared p = a.prepare(plan, VIEW, owner.settings().upload)) {
                if (p.resident(glyphs(plan).get(0)) == null)
                    throw new AssertionError("standalone错误保留上个Frame的组保护");
            }
        }
        if (owner.atlas().stats().evictedGroups != beforeStandalone + standalone.length)
            throw new AssertionError("standalone未按当前需求释放旧组");
        System.out.println("tightVisibleGroups firstAStable=true budgetBBlank=true nextEpochBReady=true"
                + " releasedA=true standaloneReclaims=true nativeRasterized=" + rasterized);
    }

    /** CPU位图已逐出但GPU仍驻留时，延期登记不应再次排队；view变化与淘汰仍须重查。
     * 使用真实worker和GL图集，只选择view而不回放宿主shader pass。 */
    private void deferredResidentDemand() throws Exception {
        boolean[] straight = {false};
        GlProgramProvider views = new GlProgramProvider() {
            public boolean preview(FontRenderContext context) { return straight[0]; }
            public GlProgramScope open(FontRenderContext context) { return null; }
        };
        ClientFontServices owner = new ClientFontServices(
                PresentationEpochScenario.class.getClassLoader(), 1,
                new ClientFontServices.Settings(
                        new AsyncGlyphRasterizer.Budget(1, 1, 1, 1024 * 1024, 4, 96 * 1024 * 1024, 16, 96),
                        GlGlyphAtlas.Mode.TEXTURE_2D,
                        new GlGlyphAtlas.Budget(64, 1, 1, 8, 8, 64L * 64 * 4),
                        new GlGlyphAtlas.UploadBudget(65536, 1000000000, 16), 64, 8),
                IsolatedFontEngine.shared(), new Lwjgl3GlApi(), views);
        onClose(() -> close(owner));
        GlPaintPlanRenderer renderer = owner.createPlanRenderer();
        PaintPlan pa = text(owner, "A", 4), pb = text(owner, "B", 4), pc = text(owner, "C", 4);
        GlyphKey a = GlyphKey.of(glyphs(pa).get(0)), b = GlyphKey.of(glyphs(pb).get(0)),
                c = GlyphKey.of(glyphs(pc).get(0));
        if (a.equals(b) || a.equals(c) || b.equals(c)) throw new AssertionError("重试夹具未生成独立GlyphKey");
        float[] projection = FramebufferCoordinates.orthographic(W, H),
                identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        FontRenderContext context = new FontRenderContext(
                new FontProjection(projection, identity, new int[] {0, 0, W, H}),
                glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), H, false, GL_LEQUAL, false, false,
                new int[] {0, 0, W, H}, FontRenderContext.MatrixState.ui(projection, identity),
                owner.gl().capabilities().contextIdentity, 1, 0);
        GlGlyphAtlas atlas = owner.atlas();
        owner.beginPresentation(20000);
        try {
            owner.registerVisibleDemand(renderer, pa, context);
            awaitCpu(owner, a);
            try (GlPaintPlanRenderer.Prepared p = owner.prepare(renderer, pa, VIEW)) {
                if (p.readyCount() != 1) throw new AssertionError("A未真实上传");
            }
        } finally { owner.endPresentation(); }
        owner.beginPresentation(20001);
        try {
            owner.registerVisibleDemand(renderer, pb, context);
            awaitCpu(owner, b);
            if (owner.rasterizer().peek(a) != null
                    || !atlas.isResident(a, GlGlyphAtlas.SamplingView.PREMULT_RGBA)
                    || atlas.isResident(a, GlGlyphAtlas.SamplingView.STRAIGHT_RGBA))
                throw new AssertionError("CPU冷/GPU热或分view夹具未命中");
            long requests = owner.rasterizer().stats().requests,
                    rasterized = owner.rasterizer().stats().rasterized;
            for (int i = 0; i < 16; i++) owner.registerVisibleDemand(renderer, pa, context);
            if (owner.rasterizer().stats().requests != requests
                    || owner.rasterizer().stats().rasterized != rasterized
                    || owner.presentationStats().activePrepared != 0)
                throw new AssertionError("热延期登记重复request/光栅或建立Frame");
            straight[0] = true;
            owner.registerVisibleDemand(renderer, pa, context);
            if (owner.rasterizer().stats().requests != requests + 1)
                throw new AssertionError("错误view驻留使冷请求被跳过");
            awaitCpu(owner, a);
            // 登记只查询不能pin旧mono页；单组预算下straight必须能够淘汰它。
            unbindGlyphPages();
            try (GlPaintPlanRenderer.Prepared p = owner.prepare(renderer, pa, context)) {
                if (p.readyCount() != 1 || p.resident(glyphs(pa).get(0)).samplingView
                        != GlGlyphAtlas.SamplingView.STRAIGHT_RGBA)
                    throw new AssertionError("驻留查询提前pin或阻断view上传");
            }
            if (atlas.isResident(a, GlGlyphAtlas.SamplingView.PREMULT_RGBA)
                    || !atlas.isResident(a, GlGlyphAtlas.SamplingView.STRAIGHT_RGBA)
                    || atlas.stats().evictedGroups != 1)
                throw new AssertionError("旧view未被淘汰或查询未及时失效");
            requests = owner.rasterizer().stats().requests;
            owner.registerVisibleDemand(renderer, pa, context);
            if (owner.rasterizer().stats().requests != requests)
                throw new AssertionError("正确straight view仍重复请求");
            straight[0] = false;
            owner.registerVisibleDemand(renderer, pa, context);
            if (owner.rasterizer().stats().requests != requests + 1)
                throw new AssertionError("已淘汰view没有重新request");
        } finally { owner.endPresentation(); }
        owner.beginPresentation(20002);
        try {
            owner.registerVisibleDemand(renderer, pb, context);
            awaitCpu(owner, b); // ready=1的真实背压使下一项保留在pending。
            owner.registerVisibleDemand(renderer, pc, context);
            long rejected = owner.rasterizer().stats().rejected,
                    requests = owner.rasterizer().stats().requests;
            owner.registerVisibleDemand(renderer, pa, context);
            owner.registerVisibleDemand(renderer, pa, context);
            if (owner.rasterizer().stats().requests != requests + 2
                    || owner.rasterizer().stats().rejected != rejected + 2)
                throw new AssertionError("pending预算拒绝被错误记作已请求");
            awaitCpu(owner, c);
            rejected = owner.rasterizer().stats().rejected;
            owner.registerVisibleDemand(renderer, pa, context);
            awaitCpu(owner, a);
            if (owner.rasterizer().stats().rejected != rejected)
                throw new AssertionError("pending释放后同key仍未前进");
        } finally { owner.endPresentation(); }
        System.out.println("deferredResidentDemand CPUevictedGPUready=true hotRequests=0 viewMissRequests=true"
                + " queryDoesNotPin=true evictedViewRetries=true pendingRejectedRetries=true");
    }

    /** 等待真实worker的发布通知；推进ready背压，不以sleep猜测字形完成。 */
    private static void awaitCpu(ClientFontServices owner, GlyphKey key) throws InterruptedException {
        AsyncGlyphRasterizer worker = owner.rasterizer();
        long deadline = System.nanoTime() + 10000000000L;
        synchronized (worker) {
            while (worker.peek(key) == null) {
                owner.pumpReady();
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new AssertionError("CPU字形未就绪：" + key);
                worker.wait(Math.max(1, left / 1000000L));
            }
        }
    }

    private static void unbindGlyphPages() {
        int previous = glGetInteger(GL_ACTIVE_TEXTURE);
        int units = glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        try {
            for (int unit = 0; unit < units; unit++) {
                glActiveTexture(GL_TEXTURE0 + unit);
                glBindTexture(GL_TEXTURE_2D, 0);
                glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
            }
        } finally {
            glActiveTexture(previous);
        }
    }

    private static boolean unpack(int name) {
        return name == GL_UNPACK_ALIGNMENT || name == GL_UNPACK_ROW_LENGTH
                || name == GL_UNPACK_IMAGE_HEIGHT || name == GL_UNPACK_SKIP_PIXELS
                || name == GL_UNPACK_SKIP_ROWS || name == GL_UNPACK_SKIP_IMAGES
                || name == GL_UNPACK_SWAP_BYTES || name == GL_UNPACK_LSB_FIRST;
    }

    public static void main(String[] args) throws Exception { new PresentationEpochScenario().run(args); }

    @Override
    protected void runTests() throws Exception {
        String[] args = arguments();
        GlfwTestEnvironment environment = own(new GlfwTestEnvironment());
        own(environment.window(W, H, "PresentationEpochScenario", Profile.CORE, 2, false, true));
        System.out.println(
                "LWJGL="
                        + org.lwjgl.Version.getVersion()
                        + " Java="
                        + System.getProperty("java.version")
                        + " vendor="
                        + glGetString(GL_VENDOR)
                        + " renderer="
                        + glGetString(GL_RENDERER)
                        + " version="
                        + glGetString(GL_VERSION));
        int texture = glGenTextures(), fbo = glGenFramebuffers();
        onClose(
                () -> {
                    glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    glDeleteFramebuffers(fbo);
                    glDeleteTextures(texture);
                });
        {
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA8,
                    W,
                    H,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new AssertionError("FBO");
            glViewport(0, 0, W, H);
            glDisable(GL_DITHER);
            glDisable(GL_FRAMEBUFFER_SRGB);
            scenario("紧预算可见组", this::tightVisibleGroups);
            scenario("热字形多consumer", this::warmConsumers);
            scenario("延期驻留需求与重试", this::deferredResidentDemand);
            scenario("冷启动与取消", this::coldAndCancellation);
            scenario("多consumer共享预算", this::sharedBudget);
            scenario("需求并集与时间预算", this::unionAndTime);
            scenario("生产presentation边界", this::boundaryMode);
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("GLerror");
            System.out.println("PRESENTATION_EPOCH_SCENARIO_PASS");
        }
    }

}
