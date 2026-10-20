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
    }
    private static FontRenderContext context(float[] projection, int width, int height, int[] scissor, Object identity) {
        float[] modelView = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        return new FontRenderContext(new FontProjection(projection, modelView, new int[] {0, 0, width, height}),
                0, height, false, 0x0201, false, true, scissor,
                FontRenderContext.MatrixState.ui(projection, modelView), identity, 1, 0);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
