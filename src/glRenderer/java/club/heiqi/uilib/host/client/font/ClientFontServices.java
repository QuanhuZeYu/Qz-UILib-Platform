package club.heiqi.uilib.host.client.font;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Set;
import java.util.Collections;
import club.heiqi.uilib.host.client.font.context.FontRenderContext;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.paint.PaintPlan;
import club.heiqi.uilib.ui.scene.text.font.glyph.GlyphKey;
import club.heiqi.uilib.host.client.gl.GlApi;
import club.heiqi.uilib.host.client.gl.GlProgramProvider;
import club.heiqi.uilib.ui.scene.text.font.nativefont.FontEngine;
import club.heiqi.uilib.host.client.gl.GlGlyphAtlas;
import club.heiqi.uilib.host.client.gl.GlPaintPlanRenderer;
import club.heiqi.uilib.ui.scene.text.SceneTextMeasurer;
import club.heiqi.uilib.ui.scene.text.TextLayout;
import club.heiqi.uilib.ui.scene.text.TextPolicy;
import club.heiqi.uilib.ui.scene.text.TextStyle;
import club.heiqi.uilib.ui.scene.text.font.FontTextService;
import club.heiqi.uilib.ui.scene.text.font.FontResources;
import club.heiqi.uilib.ui.scene.text.font.glyph.AsyncGlyphRasterizer;

/** 客户端主线程持有共享CPU字体服务，GL资源按实际呈现需求创建。
 * scene 与原版字体适配共享字体/CPU/atlas，独立 consumer 可申请自己的 quad renderer。
 * atlas 的 active Frame 串行提交；即时 replay 与宿主延后提交分别持有所需资源引用，完成后释放。
 */
public final class ClientFontServices implements SceneTextMeasurer, AutoCloseable {
    public static final class Settings {
        public final AsyncGlyphRasterizer.Budget cpu;
        public final GlGlyphAtlas.Mode mode;
        public final GlGlyphAtlas.Budget atlas;
        public final GlGlyphAtlas.UploadBudget upload;
        public final int quadCapacity, maxDemand;

        public Settings(
                AsyncGlyphRasterizer.Budget cpu,
                GlGlyphAtlas.Mode mode,
                GlGlyphAtlas.Budget atlas,
                GlGlyphAtlas.UploadBudget upload,
                int quadCapacity,
                int maxDemand) {
            if (quadCapacity < 1 || maxDemand < 1) throw new IllegalArgumentException("无效客户端提交预算");
            this.cpu = Objects.requireNonNull(cpu, "cpu");
            this.mode = Objects.requireNonNull(mode, "mode");
            this.atlas = Objects.requireNonNull(atlas, "atlas");
            this.upload = Objects.requireNonNull(upload, "upload");
            this.quadCapacity = quadCapacity;
            this.maxDemand = maxDemand;
        }

        /** 默认采用有界CPU/GPU预算与2D纹理，调用方可按环境配置。 */
        public static Settings defaults() {
            return new Settings(
                    new AsyncGlyphRasterizer.Budget(
                            512, 2048, 256, 64L * 1024 * 1024, 16, 256L * 1024 * 1024, 64, 256),
                    GlGlyphAtlas.Mode.TEXTURE_2D,
                    new GlGlyphAtlas.Budget(512, 2, 16, 8192, 2048, 32L * 1024 * 1024),
                    new GlGlyphAtlas.UploadBudget(256 * 1024, 2000000, 16),
                    2048,
                    2048);
        }
    }

    private final Thread thread = Thread.currentThread();
    private final Settings settings;
    private final GlApi gl;
    private final GlProgramProvider programs;
    private final FontTextService fonts;
    private final AsyncGlyphRasterizer rasterizer;
    private final List<GlPaintPlanRenderer> renderers = new ArrayList<GlPaintPlanRenderer>();
    private GlGlyphAtlas atlas;
    private GlPaintPlanRenderer sharedRenderer;
    private boolean closed;
    private long readyVersion;
    private boolean presenting;
    private final boolean presentationBoundaryRequired;
    private long epochId,
            autoEpoch,
            remainingBytes,
            remainingNanos,
            usedBytes,
            usedNanos,
            rejectedDemand;
    private int remainingCalls, usedCalls, preparedCount;
    private boolean allowBatchReordering;
    private final LinkedHashSet<GlyphKey> presentationDemand = new LinkedHashSet<GlyphKey>();

    public static final class PresentationStats {
        public final long epochId,
                uploadedBytes,
                prepareNanos,
                rejectedDemand,
                remainingBytes,
                remainingNanos;
        public final int uploadCalls, demand, remainingCalls, activePrepared;
        public final boolean active;

        private PresentationStats(ClientFontServices owner) {
            epochId = owner.epochId;
            uploadedBytes = owner.usedBytes;
            prepareNanos = owner.usedNanos;
            rejectedDemand = owner.rejectedDemand;
            remainingBytes = owner.remainingBytes;
            remainingNanos = owner.remainingNanos;
            uploadCalls = owner.usedCalls;
            demand = owner.presentationDemand.size();
            remainingCalls = owner.remainingCalls;
            activePrepared = owner.preparedCount;
            active = owner.presenting;
        }
    }

    public PresentationStats presentationStats() {
        check();
        return new PresentationStats(this);
    }

    /** main线程呈现范围保存需求key；每次begin须配对finally endPresentation。
     * 活动范围内重入会报告异常，原预算保持至范围结束。
     */
    public void beginPresentation(long epochId) {
        check();
        if (presenting) throw new IllegalStateException("上一个presentation尚未结束");
        this.epochId = epochId;
        presentationDemand.clear();
        presenting = true;
        remainingBytes = settings.upload.bytes;
        remainingCalls = settings.upload.calls;
        remainingNanos = settings.upload.nanos;
        usedBytes = 0;
        usedCalls = 0;
        usedNanos = 0;
        rejectedDemand = 0;
        // 完整需求在本帧逐步收集，end统一清理过期queued请求。
        pumpReady();
    }

    public void endPresentation() {
        check();
        if (!presenting) throw new IllegalStateException("没有活动presentation");
        if (preparedCount != 0) throw new IllegalStateException("先关闭所有Prepared再结束presentation");
        try {
            Throwable failure = null;
            try {
                rasterizer.retainPending(presentationDemand);
            } catch (RuntimeException | Error error) {
                failure = error;
            }
            // CPU清理失败也须归还GPU组保护；保留原始主错与后续清理错误。
            try {
                if (atlas != null) atlas.retainUploadDemand(presentationDemand);
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
            if (failure instanceof Error) throw (Error) failure;
            if (failure != null) throw (RuntimeException) failure;
            pumpReady();
        } finally {
            presenting = false;
        }
    }

    /** 延期consumer注册投影可见key并启动有界worker，GPU资源在回放时准备。
     * 与scene/immediate consumer共用完整呈现需求；真正回放时才取得短Prepared。 */
    public Set<GlyphKey> registerVisibleDemand(
            GlPaintPlanRenderer renderer, PaintPlan plan, FontRenderContext context) {
        checkRenderer(renderer);
        if (!presentationBoundaryRequired && !presenting)
            throw new IllegalStateException("延期consumer需要显式presentation或真实present边界");
        if (!presenting) beginPresentation(--autoEpoch);
        Set<GlyphKey> admitted = admitDemand(renderer.collectVisibleDemand(plan, context));
        if (admitted.isEmpty()) return admitted;
        GlGlyphAtlas.SamplingView view = atlas == null ? null : renderer.resolveSamplingView(context);
        // 已驻留字形可沿用GPU资源；其余key继续申请CPU光栅，回放时取得短Frame。
        // 驻留查询按当前view逐次确认，覆盖淘汰与预算变化。
        for (GlyphKey key : admitted)
            if (atlas == null || !atlas.isResident(key, view)) rasterizer.request(key);
        return admitted;
    }

    /** 仅在调用方flush并退出quad范围之后借用；保持同一presentation预算和父Frame冻结资源。 */
    public void withDelegatedConsumer(Runnable delegated) {
        check();
        if (preparedCount != 1) throw new IllegalStateException("字体委托需要唯一父Prepared");
        preparedCount--;
        try {
            atlas().borrowFrozenFrame(delegated);
        } finally {
            preparedCount++;
        }
    }

    private void checkRenderer(GlPaintPlanRenderer renderer) {
        check();
        if (!renderers.contains(renderer)) throw new IllegalArgumentException("renderer不属于该owner");
    }

    private Set<GlyphKey> admitDemand(Collection<GlyphKey> incoming) {
        // 调用方传入collectVisibleDemand的独立只读有序Set，容量足够时直接复用该快照。
        boolean reuseIncoming = incoming instanceof Set
                && incoming.size() <= settings.maxDemand - presentationDemand.size();
        Set<GlyphKey> admitted = reuseIncoming ? (Set<GlyphKey>) incoming : new LinkedHashSet<GlyphKey>();
        for (GlyphKey key : incoming) {
            if (presentationDemand.size() < settings.maxDemand
                    || presentationDemand.contains(key)) {
                presentationDemand.add(key);
                if (!reuseIncoming) admitted.add(key);
            } else {
                rejectedDemand++;
            }
        }
        return reuseIncoming ? admitted : Collections.unmodifiableSet(admitted);
    }

    /** consumer 共享呈现预算；自动范围在 Prepared 关闭时结束，显式范围由宿主结束。
     * 上传限时覆盖atlas prepare，在驱动调用边界检查。 */
    public GlPaintPlanRenderer.Prepared prepare(
            GlPaintPlanRenderer renderer, PaintPlan plan, LayoutBox viewport) {
        return prepare(renderer, plan, viewport, null);
    }

    public GlPaintPlanRenderer.Prepared prepare(
            GlPaintPlanRenderer renderer, PaintPlan plan, FontRenderContext context) {
        Objects.requireNonNull(context, "context");
        return prepare(renderer, plan, null, context);
    }

    private GlPaintPlanRenderer.Prepared prepare(
            GlPaintPlanRenderer renderer,
            PaintPlan plan,
            LayoutBox viewport,
            FontRenderContext context) {
        checkRenderer(renderer);
        if (preparedCount != 0) throw new IllegalStateException("共享atlas只允许即时短Prepared");
        boolean automatic = !presenting && !presentationBoundaryRequired;
        if (!presenting) beginPresentation(--autoEpoch);
        try {
            // owner保留整帧并集裁决预算与取消，短Prepared只扫描当前consumer需求。
            Set<GlyphKey> admitted = admitDemand(
                    context == null
                            ? renderer.collectVisibleDemand(plan, viewport)
                            : renderer.collectVisibleDemand(plan, context));
            GlGlyphAtlas sharedAtlas = atlas();
            GlGlyphAtlas.Stats before = sharedAtlas.stats();
            // 上传限时从需求收集后开始，保证大计划仍有字形上传额度。
            long start = System.nanoTime();
            long availableNanos = remainingNanos;
            try {
                GlGlyphAtlas.UploadBudget budget =
                        new GlGlyphAtlas.UploadBudget(
                                remainingBytes, availableNanos, remainingCalls);
                Runnable onClose =
                        () -> {
                            preparedCount--;
                            if (automatic) endPresentation();
                        };
                renderer.allowBatchReordering(allowBatchReordering);
                GlPaintPlanRenderer.Prepared prepared =
                        context == null
                                ? renderer.prepareDemand(
                                        plan, viewport, budget, admitted, false, onClose)
                                : renderer.prepareDemandContext(
                                        plan, context, budget, admitted, onClose);
                preparedCount++;
                return prepared;
            } finally {
                GlGlyphAtlas.Stats after = sharedAtlas.stats();
                long bytes = after.uploadedBytes - before.uploadedBytes;
                int calls = Math.toIntExact(after.uploadCalls - before.uploadCalls);
                long elapsed = System.nanoTime() - start;
                usedBytes += bytes;
                usedCalls += calls;
                usedNanos += elapsed;
                remainingBytes = Math.max(0, remainingBytes - bytes);
                remainingCalls = Math.max(0, remainingCalls - calls);
                remainingNanos = Math.max(0, remainingNanos - elapsed);
            }
        } catch (RuntimeException | Error failure) {
            if (automatic && preparedCount == 0) endPresentation();
            throw failure;
        }
    }

    /** 显式同步批处理入口；交互宿主通过FontResources构造接收worker准备结果。 */
    public ClientFontServices(
            ClassLoader sources,
            long revision,
            Settings settings,
            FontEngine engine,
            GlApi gl,
            GlProgramProvider programs)
            throws IOException {
        this(sources, revision, settings, false, engine, gl, programs);
    }

    /** 同步准备批处理资源；交互宿主通过FontResources构造接收worker结果。
     * 真实present宿主可保留整个呈现范围，首次消费懒启动；逐次关闭Prepared后，在交换缓冲前调用endPresentation。
     * 独立consumer默认使用自动单次范围。 */
    public ClientFontServices(
            ClassLoader sources,
            long revision,
            Settings settings,
            boolean presentationBoundaryRequired,
            FontEngine engine,
            GlApi gl,
            GlProgramProvider programs)
            throws IOException {
        this(FontResources.prepare(sources, revision, FontTextService.defaultSources(sources, revision)),
                settings, presentationBoundaryRequired, engine, gl, programs);
    }

    /** UI 宿主必须传入 worker 完成的整代资源；native 测量会话仍在本创建线程。 */
    public ClientFontServices(FontResources resources, Settings settings,
            boolean presentationBoundaryRequired, FontEngine engine, GlApi gl, GlProgramProvider programs) {
        this.presentationBoundaryRequired = presentationBoundaryRequired;
        this.gl = Objects.requireNonNull(gl, "gl");
        this.programs = Objects.requireNonNull(programs, "programs");
        this.settings = Objects.requireNonNull(settings, "settings");
        fonts =
                new FontTextService(
                        resources,
                        Objects.requireNonNull(engine, "engine"),
                        32,
                        256,
                        8192);
        rasterizer = new AsyncGlyphRasterizer(settings.cpu, engine);
    }

    private void check() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("客户端字体owner仅限创建线程");
        if (closed) throw new IllegalStateException("客户端字体owner已关闭");
    }

    public FontTextService fonts() {
        check();
        return fonts;
    }

    /** 共同文字与公式使用同一字体代际解析出的字号、基线和样式身份。 */
    @Override
    public TextStyle resolveStyle(TextStyle style) {
        check();
        return fonts.resolveStyle(style);
    }

    @Override
    public club.heiqi.uilib.ui.scene.text.math.MathFont mathFont(TextStyle style) {
        check();
        return fonts.mathFont(style);
    }

    @Override
    public TextLayout layout(String text, TextStyle style, int availableWidth) {
        check();
        return fonts.layout(text, style, availableWidth);
    }

    @Override
    public TextLayout layout(
            club.heiqi.uilib.ui.scene.text.AttributedText text,
            float availableWidth,
            TextPolicy policy) {
        check();
        return fonts.layout(text, availableWidth, policy);
    }

    @Override
    public TextLayout layout(String text, TextStyle style, int availableWidth, TextPolicy policy) {
        check();
        return fonts.layout(text, style, availableWidth, policy);
    }

    public AsyncGlyphRasterizer rasterizer() {
        check();
        return rasterizer;
    }

    /** 宿主配置在owner线程发布，所有共享consumer的下一次Prepared使用同一策略。 */
    public void allowBatchReordering(boolean enabled) {
        check();
        allowBatchReordering = enabled;
    }

    public Settings settings() {
        return settings;
    }

    public GlApi gl() {
        check();
        return gl;
    }

    public boolean hasGlResources() {
        check();
        return atlas != null;
    }

    public long readyVersion() {
        check();
        return readyVersion;
    }

    /** 宿主在每帧准备阶段接收字形就绪结果，仅更新资源可画状态。 */
    public int pumpReady() {
        check();
        int count = rasterizer.drainReady(settings.cpu.ready).size();
        readyVersion = rasterizer.stats().version;
        return count;
    }

    public void reload(ClassLoader loader, long revision) throws IOException {
        check();
        List<club.heiqi.uilib.ui.scene.text.font.FontSource> defaults = FontTextService.defaultSources(loader, revision);
        reload(FontResources.prepare(loader, revision, defaults, defaults));
    }

    /** 显式同步批处理换代；交互宿主只发布 worker 已准备的 FontResources。 */
    public void reload(List<club.heiqi.uilib.ui.scene.text.font.FontSource> sources) {
        check();
        fonts.reload(FontResources.prepare(ClientFontServices.class.getClassLoader(), fonts.generation() + 1, sources));
    }

    /** 在宿主prepare/apply边界原子发布已准备的完整资源代。 */
    public void reload(FontResources resources) {
        check();
        fonts.reload(resources);
    }

    public GlGlyphAtlas atlas() {
        check();
        if (atlas == null) {
            if (!gl.capabilities().openGL32)
                throw new IllegalStateException("共享字体backend要求实际GL3.2+能力，不能以bindings版本推断");
            atlas = new GlGlyphAtlas(gl, settings.mode, settings.atlas);
        }
        return atlas;
    }

    /** 为screen/consumer创建所属renderer；release释放其quad，共享缓存归本owner。 */
    public GlPaintPlanRenderer createPlanRenderer() {
        return createPlanRenderer(null);
    }

    /** 借用同级owner提供的图像资源，组合有序GPU消费者。 */
    public GlPaintPlanRenderer createPlanRenderer(
            club.heiqi.uilib.host.client.gl.GlImageResources images) {
        return createPlanRenderer(images, null);
    }

    /** 借用同级视频owner供GPU消费者回放，媒体时钟与解码归媒体会话。 */
    public GlPaintPlanRenderer createPlanRenderer(club.heiqi.uilib.host.client.gl.GlImageResources images,
            club.heiqi.uilib.host.client.gl.GlVideoResources videos) {
        check();
        GlPaintPlanRenderer renderer =
                new GlPaintPlanRenderer(
                        gl,
                        rasterizer,
                        atlas(),
                        settings.quadCapacity,
                        settings.maxDemand,
                        programs,
                        images, videos, fonts);
        renderers.add(renderer);
        return renderer;
    }

    public void releasePlanRenderer(GlPaintPlanRenderer renderer) {
        if (Thread.currentThread() != thread)
            throw new IllegalStateException("renderer必须在owner线程释放");
        if (closed) return; // owner退出时已关闭全部renderer，screen后续释放沿用该结果。
        if (renderer == sharedRenderer) throw new IllegalArgumentException("共享即时renderer由owner关闭");
        if (!renderers.contains(renderer)) throw new IllegalArgumentException("renderer不属于该owner");
        renderer.close();
        renderers.remove(renderer);
    }

    /** vanilla即时adapter复用所属共享quad backend。 */
    public GlPaintPlanRenderer sharedPlanRenderer() {
        check();
        if (sharedRenderer == null) sharedRenderer = createPlanRenderer();
        return sharedRenderer;
    }

    /** 在客户端主线程、有效GLcontext内关闭GPU资源。
     * CPU worker收到异步关闭信号，native face/service在worker线程自行销毁。
     */
    @Override
    public void close() {
        if (closed) return;
        check();
        if (preparedCount != 0) throw new IllegalStateException("退出前先关闭活动Prepared");
        presenting = false;
        presentationDemand.clear();
        // 字体规格 5.2：单项关闭失败仍清理其余所属资源，首错保留，后续错误附入 suppressed。
        closed = true;
        Throwable failure = null;
        for (GlPaintPlanRenderer renderer : new ArrayList<GlPaintPlanRenderer>(renderers)) {
            try {
                renderer.close();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        renderers.clear();
        try {
            if (atlas != null) atlas.close();
        } catch (RuntimeException | Error error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
        try {
            fonts.close();
        } catch (RuntimeException | Error error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
        try {
            rasterizer.close();
        } catch (RuntimeException | Error error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw (RuntimeException) failure;
    }

    /** 供退出清理验收等待CPU光栅worker结束。 */
    public boolean awaitRasterizerClose(long timeoutMillis) throws InterruptedException {
        return rasterizer.awaitClose(timeoutMillis);
    }
}
