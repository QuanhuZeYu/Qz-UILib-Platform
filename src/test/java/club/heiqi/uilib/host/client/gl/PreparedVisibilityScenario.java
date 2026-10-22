package club.heiqi.uilib.host.client.gl;

import club.heiqi.uilib.host.client.font.ClientFontServices;
import club.heiqi.uilib.host.client.font.FramebufferCoordinates;
import club.heiqi.uilib.host.client.font.context.FontProjection;
import club.heiqi.uilib.host.client.font.context.FontRenderContext;
import club.heiqi.uilib.ui.scene.geometry.SceneClipRegion;
import club.heiqi.uilib.ui.scene.geometry.SceneTransform;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.paint.PaintCommand;
import club.heiqi.uilib.ui.scene.paint.PaintPlan;
import club.heiqi.uilib.ui.scene.paint.PaintPlanBuilder;
import club.heiqi.uilib.ui.scene.text.TextLayout;
import club.heiqi.uilib.ui.scene.text.font.glyph.GlyphKey;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import club.heiqi.uilib.ui.scene.text.TextPolicy;
import club.heiqi.uilib.ui.scene.text.font.FontTextService;

/** 复用既有真实GL窗口、字体和atlas；工作量计数不代表实测帧时间。 */
public final class PreparedVisibilityScenario {
    public static void verify(ClientFontServices owner, GlPaintPlanRenderer renderer, TextLayout text, int width, int height) {
        LayoutBox viewport = new LayoutBox(0, 0, width, height);
        PaintPlan plan = new PaintPlanBuilder().add(PaintCommand.text(new LayoutBox(20, 50, width, height), viewport, text)).build()
                .transformed(SceneTransform.rotationDegrees(1), SceneClipRegion.rectangle(viewport));
        long glyphs = 0;
        for (TextLayout.Line line : text.lines()) glyphs += line.glyphs().size();
        require(glyphs > 0, "可见性探针未命中字形");
        long before = renderer.visibilityChecks;
        try (GlPaintPlanRenderer.Prepared prepared = owner.prepare(renderer, plan, viewport)) {
            prepared.render(FramebufferCoordinates.orthographic(width, height), height, false);
            prepared.render(FramebufferCoordinates.orthographic(width, height), height, false);
            require(renderer.visibilityChecks - before == glyphs, "需求、分层或重复回放重新判定了同一字形");
        }
        // 保持plan、宿主identity、revision相同；原生提交的scissor变化仍必须有新可见事实。
        float[] projection = FramebufferCoordinates.orthographic(width, height);
        Object identity = new Lwjgl3GlApi().capabilities().contextIdentity;
        FontRenderContext shown = context(projection, width, height, new int[] {0, 0, width, height}, identity);
        FontRenderContext hidden = context(projection, width, height, new int[] {width - 1, 0, 1, 1}, identity);
        Set<GlyphKey> demand = renderer.collectVisibleDemand(plan, shown);
        require(!demand.isEmpty(), "world探针不可见");
        before = renderer.visibilityChecks;
        try (GlPaintPlanRenderer.Prepared prepared = renderer.prepareDemandContext(plan, shown,
                new GlGlyphAtlas.UploadBudget(0, 0, 0), demand, null)) {
            prepared.render(shown);
            require(renderer.visibilityChecks == before, "world准备与回放没有复用需求事实");
            long clippedBefore = renderer.drawnGlyphs() + renderer.blankGlyphs();
            prepared.render(hidden);
            require(renderer.visibilityChecks - before == glyphs, "新world状态没有使可见事实失效");
            require(renderer.drawnGlyphs() + renderer.blankGlyphs() == clippedBefore, "world外部scissor仍回放隐藏字形");
        }
        require(renderer.collectVisibleDemand(plan, hidden).isEmpty(), "同plan新context需求沿用了旧可见性");
        require(renderer.collectVisibleDemand(plan, new LayoutBox(width + 20, height + 20, 5, 5)).isEmpty(), "新viewport未失效");
        System.out.println("preparedVisibility glyphs=" + glyphs + " collect/prepare/replay checks=" + glyphs
                + " repeatReplay=0 newWorldContext=" + glyphs + " hiddenDemand=0");
        verifyMemo(owner, text, width, height, identity);
    }
    private static FontRenderContext context(float[] projection, int width, int height, int[] scissor, Object identity) {
        float[] modelView = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        return new FontRenderContext(new FontProjection(projection, modelView, new int[] {0, 0, width, height}),
                0, height, false, 0x0201, false, true, scissor,
                FontRenderContext.MatrixState.ui(projection, modelView), identity, 1, 0);
    }
    private static void verifyMemo(ClientFontServices owner, TextLayout text, int width, int height, Object identity) {
        FontTextService fonts = owner.fonts();
        int listeners = listenerCount(fonts);
        GlPaintPlanRenderer renderer = owner.createPlanRenderer();
        require(listenerCount(fonts) == listeners + 1, "renderer没有登记唯一字体失效订阅");
        try {
            float[] projection = FramebufferCoordinates.orthographic(width, height);
            FontRenderContext shown = context(projection, width, height, new int[] {0, 0, width, height}, identity);
            int checks = distinctGlyphs(text);
            long before = renderer.visibilityChecks;
            List<GlyphKey> expected = null;
            for (int epoch = 0; epoch < 2; epoch++) {
                owner.beginPresentation(7100 + epoch);
                try {
                    for (int i = 0; i < 144; i++) {
                        Set<GlyphKey> demand = owner.registerVisibleDemand(renderer, plain(text, width, height),
                                context(projection, width, height, new int[] {0, 0, width, height}, identity));
                        if (expected == null) expected = new ArrayList<>(demand);
                        require(!demand.isEmpty() && expected.equals(new ArrayList<>(demand)), "memo改变需求或顺序");
                    }
                    for (int i = 0; i < 54; i++) {
                        try (GlPaintPlanRenderer.Prepared prepared = owner.prepare(renderer, plain(text, width, height),
                                context(projection, width, height, new int[] {0, 0, width, height}, identity))) {
                            require(prepared.demandCount() == expected.size(), "memo改变短Prepared的需求");
                            prepared.render(shown);
                        }
                    }
                } finally { owner.endPresentation(); }
            }
            require(renderer.visibilityChecks - before == checks,
                    "等值新context/new PaintCommand跨register、prepare、replay或epoch重复检查");
            verifySources(renderer, text, shown, width, height, identity);
            verifyReload(owner, renderer, text, shown, width, height);
        } finally { owner.releasePlanRenderer(renderer); }
        renderer.close();
        require(listenerCount(fonts) == listeners, "renderer.close没有退订或重复关闭重新访问字体");
        verifyBounds(owner, text, width, height, identity);
        require(listenerCount(fonts) == listeners, "预算/独立renderer场景泄漏字体订阅");
        System.out.println("preparedVisibility memo equalContexts/newCommands/epochs=true sourceChanges=true reload/oldPrepared=true"
                + " lruEntries=256 glyphFacts=8192 oversizedUncached=true closeUnsubscribed=true");
    }

    private static void verifySources(GlPaintPlanRenderer renderer, TextLayout text, FontRenderContext shown,
            int width, int height, Object identity) {
        PaintPlan base = plain(text, width, height);
        int checks = distinctGlyphs(text);
        float[] projection = shown.projection.projection();
        float[] moved = projection.clone(); moved[12] += 0.01f;
        expectChecks(renderer, base, context(moved, width, height, shown.scissor(), identity), checks, "MVP变化未miss");
        int[] viewport = shown.projection.viewport(), changedViewport = viewport.clone(); changedViewport[2]++;
        expectChecks(renderer, base, context(projection, identityMatrix(), changedViewport, true, shown.scissor(), height, identity),
                checks, "projection viewport变化未miss");
        expectChecks(renderer, base, context(projection, identityMatrix(), viewport, false, shown.scissor(), height, identity),
                checks, "scissorEnabled未miss");
        expectChecks(renderer, base, context(projection, identityMatrix(), viewport, true, shown.scissor(), height + 1, identity),
                checks, "sourceFramebufferHeight未miss");
        expectChecks(renderer, plan(text, new LayoutBox(21, 50, width, height), new LayoutBox(0, 0, width, height)),
                shown, checks, "anchor变化未miss");
        expectChecks(renderer, plan(text, new LayoutBox(20, 50, width, height), new LayoutBox(1, 0, width, height)),
                shown, checks, "clip变化未miss");
        expectChecks(renderer, plain(new TextLayout(text.width, text.height, text.style, text.lines()), width, height),
                shown, checks, "新layout身份沿用了旧facts");
        FontRenderContext hidden = context(projection, width, height, new int[] {0, 0, 0, 0}, identity);
        long clipped = renderer.clippedGlyphs();
        require(expectChecks(renderer, base, hidden, checks, "隐藏scissor未miss").isEmpty(), "隐藏scissor仍申请字形");
        long delta = renderer.clippedGlyphs() - clipped;
        clipped = renderer.clippedGlyphs();
        require(expectChecks(renderer, plain(text, width, height), hidden, 0, "隐藏facts未命中").isEmpty()
                && renderer.clippedGlyphs() - clipped == delta, "命中改变clippedGlyphs计数");
        // 经真实FontProjection乘法得到负零；输入符号不能在key中被数值相等抹平。
        float[] zero = projection.clone(), negativeZero = projection.clone(), mv = identityMatrix();
        mv[10] = 0.25f; negativeZero[8] = -Float.MIN_VALUE;
        FontRenderContext plus = context(zero, mv, viewport, true, shown.scissor(), height, identity);
        FontRenderContext minus = context(negativeZero, mv, viewport, true, shown.scissor(), height, identity);
        require(Float.floatToRawIntBits(plus.projection.mvp()[8]) == 0
                && Float.floatToRawIntBits(minus.projection.mvp()[8]) == Integer.MIN_VALUE, "signed zero探针无效");
        // plus与普通base的MVP不同（Z缩放），两个来源都必须先miss，再独立命中。
        expectChecks(renderer, base, plus, checks, "正零来源未miss");
        expectChecks(renderer, base, minus, checks, "负零rawbits被归并");
        expectChecks(renderer, plain(text, width, height), minus, 0, "负零相同rawbits未命中");
    }

    private static void verifyReload(ClientFontServices owner, GlPaintPlanRenderer renderer, TextLayout text,
            FontRenderContext shown, int width, int height) {
        PaintPlan plan = plain(text, width, height);
        renderer.collectVisibleDemand(plan, shown);
        try (GlPaintPlanRenderer.Prepared old = owner.prepare(renderer, plan, shown)) {
            int ready = old.readyCount(), demand = old.demandCount();
            long before = renderer.visibilityChecks, emitted = renderer.drawnGlyphs() + renderer.blankGlyphs();
            old.render(shown);
            long drawn = renderer.drawnGlyphs() + renderer.blankGlyphs() - emitted;
            long generation = owner.fonts().generation();
            owner.reload(owner.fonts().defaultCandidates());
            require(owner.fonts().generation() != generation, "真实字体reload没有换代");
            old.render(shown);
            require(renderer.visibilityChecks == before && old.readyCount() == ready && old.demandCount() == demand
                    && renderer.drawnGlyphs() + renderer.blankGlyphs() - emitted == drawn * 2,
                    "reload清理破坏旧Prepared的CPU事实或冻结Frame");
            expectChecks(renderer, plan, shown, distinctGlyphs(text), "字体reload没有清理memo");
            TextLayout current = owner.layout("memo新代", text.style, width, TextPolicy.SINGLE_LINE);
            require(current.generation != text.generation, "新layout仍属旧字体代际");
            require(!renderer.collectVisibleDemand(plain(current, width, height), shown).isEmpty(), "新字体代际不可见");
        }
        expectChecks(renderer, plain(text, width, height), shown, 0, "旧代plan不允许重新缓存重放");
        try (GlPaintPlanRenderer.Prepared next = owner.prepare(renderer, plain(text, width, height), shown)) { next.render(shown); }
    }

    private static void verifyBounds(ClientFontServices owner, TextLayout text, int width, int height, Object identity) {
        FontTextService fonts = owner.fonts();
        TextLayout one = owner.layout("M", text.style, width, TextPolicy.SINGLE_LINE);
        require(distinctGlyphs(one) == 1, "LRU探针不是单glyph");
        FontRenderContext shown = context(FramebufferCoordinates.orthographic(width, height), width, height,
                new int[] {0, 0, width, height}, identity);
        GlPaintPlanRenderer renderer = owner.createPlanRenderer();
        try {
            for (int i = 0; i < 256; i++) expectChecks(renderer, anchored(one, i), shown, 1, "256项内提前丢失");
            expectChecks(renderer, anchored(one, 0), shown, 0, "256项边界提前逐出");
            expectChecks(renderer, anchored(one, 256), shown, 1, "新anchor误命中");
            expectChecks(renderer, anchored(one, 0), shown, 0, "LRU丢弃最近使用项");
            expectChecks(renderer, anchored(one, 1), shown, 1, "超过256项没有逐出最旧项");
        } finally { owner.releasePlanRenderer(renderer); }
        renderer = owner.createPlanRenderer();
        try {
            // 真实glyph重复出现也占visibleKeys空间；总量按出现次数而非identity表大小控制。
            TextLayout repeated = repeated(one, 33);
            int retained = 8192 / 33;
            require(retained < 256, "总glyph预算未先于项数生效");
            for (int i = 0; i < retained; i++) expectChecks(renderer, anchored(repeated, i), shown, 1, "glyph预算内提前丢失");
            TextLayout remainder = repeated(one, 8192 - retained * 33);
            expectChecks(renderer, anchored(remainder, 0), shown, 1, "8192内剩余额度未计算");
            expectChecks(renderer, anchored(repeated, 0), shown, 0, "8192边界提前逐出");
            expectChecks(renderer, anchored(one, retained), shown, 1, "超过总glyph预算的新事实未计算");
            expectChecks(renderer, anchored(repeated, 0), shown, 0, "glyph预算逐出最近使用项");
            expectChecks(renderer, anchored(repeated, 1), shown, 1, "总glyph事实超过8192未逐出");
            TextLayout oversized = repeated(one, 8193);
            expectChecks(renderer, anchored(oversized, 0), shown, 1, "超大布局没有计算");
            expectChecks(renderer, anchored(oversized, 0), shown, 1, "超大单项被缓存");
            expectChecks(renderer, anchored(repeated, 0), shown, 0, "超大单项污染已有memo");
        } finally { owner.releasePlanRenderer(renderer); }
        // 小需求预算仍逐次重放原顺序/overflow，不从memo直接返回旧demand集合。
        try (GlPaintPlanRenderer limited = new GlPaintPlanRenderer(owner.gl(), owner.rasterizer(), owner.atlas(), 32,
                1, GlProgramProvider.NONE, null, null, fonts)) {
            TextLayout pair = owner.layout("MW", one.style, width, TextPolicy.SINGLE_LINE);
            Set<GlyphKey> first = limited.collectVisibleDemand(plain(pair, width, height), shown);
            long overflow = limited.demandOverflow(), before = limited.visibilityChecks;
            Set<GlyphKey> second = limited.collectVisibleDemand(plain(pair, width, height), shown);
            require(first.size() == 1 && new ArrayList<>(first).equals(new ArrayList<>(second)) && overflow > 0
                    && limited.demandOverflow() == overflow * 2 && limited.visibilityChecks == before,
                    "memo改变需求预算、顺序或demandOverflow计数");
        }
        try (GlPaintPlanRenderer standalone = new GlPaintPlanRenderer(owner.gl(), owner.rasterizer(), owner.atlas(), 32, 32, GlProgramProvider.NONE)) {
            expectChecks(standalone, plain(one, width, height), shown, 1, "旧构造不可用");
            expectChecks(standalone, plain(one, width, height), shown, 1, "无字体通知的旧构造启用了跨帧memo");
        }
    }

    private static TextLayout repeated(TextLayout one, int occurrences) {
        TextLayout.Glyph glyph = one.lines().get(0).glyphs().get(0);
        TextLayout.Line line = new TextLayout.Line("M", 0, 0, 0, 1, 0, 1, one.advanceWidth, one.advanceHeight,
                one.advanceHeight, 0, one.advanceHeight, Collections.nCopies(occurrences, glyph));
        return new TextLayout(one.width, one.height, one.style, Collections.singletonList(line));
    }
    private static int distinctGlyphs(TextLayout text) {
        java.util.Map<TextLayout.Glyph, Boolean> glyphs = new java.util.IdentityHashMap<>();
        for (TextLayout.Line line : text.lines()) for (TextLayout.Glyph glyph : line.glyphs()) glyphs.put(glyph, true);
        return glyphs.size();
    }
    private static PaintPlan plain(TextLayout text, int width, int height) {
        return plan(text, new LayoutBox(20, 50, width, height), new LayoutBox(0, 0, width, height));
    }
    private static PaintPlan anchored(TextLayout text, int anchor) {
        return plan(text, new LayoutBox(20 + anchor, 50, text.width, text.height), null);
    }
    private static PaintPlan plan(TextLayout text, LayoutBox box, LayoutBox clip) {
        return new PaintPlanBuilder().add(PaintCommand.text(box, clip, text)).build();
    }
    private static Set<GlyphKey> expectChecks(GlPaintPlanRenderer renderer, PaintPlan plan, FontRenderContext context,
            long expected, String message) {
        long before = renderer.visibilityChecks;
        Set<GlyphKey> demand = renderer.collectVisibleDemand(plan, context);
        require(renderer.visibilityChecks - before == expected, message);
        return demand;
    }
    private static int listenerCount(FontTextService fonts) {
        // 观察真实订阅的占用/归还，不以类字段数量或源码形状代替生命周期验收。
        try {
            java.lang.reflect.Field field = FontTextService.class.getDeclaredField("invalidation");
            field.setAccessible(true);
            return ((java.util.Collection<?>) field.get(fonts)).size();
        } catch (ReflectiveOperationException failure) { throw new AssertionError("无法观察字体订阅生命周期", failure); }
    }
    private static float[] identityMatrix() { return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}; }
    private static FontRenderContext context(float[] projection, float[] modelView, int[] viewport,
            boolean scissorEnabled, int[] scissor, int height, Object identity) {
        return new FontRenderContext(new FontProjection(projection, modelView, viewport), 0, height, false,
                0x0201, false, scissorEnabled, scissor, FontRenderContext.MatrixState.ui(projection, modelView), identity, 1, 0);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
