package club.heiqi.uilib.ui.scene.integration;

import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.host.client.font.context.FontRenderContext;
import club.heiqi.uilib.host.client.font.context.FontProjection;
import club.heiqi.uilib.host.client.font.context.StandardFontContext;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/** 字体历史状态的纯值合同；GL 常量在编译期内联，不读取驱动或创建窗口。 */
public final class FontRenderContextValueScenario extends TestApplication {
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    @Override protected void runTests() throws Exception {
        scenario("值对象复制与兼容构造", FontRenderContextValueScenario::verifyValue);
    }

    static void verifyValue() {
        float[] blend = {0.25f, 0.5f, 0.75f, 1}, range = {0.125f, 0.875f};
        boolean[] mask = {true, false, true, false};
        FontRenderContext.StencilFace s =
                new FontRenderContext.StencilFace(
                        GL11.GL_ALWAYS, 0, -1, -1, GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        FontRenderContext.RasterState r =
                new FontRenderContext.RasterState(
                        true,
                        1,
                        0,
                        1,
                        0,
                        GL14.GL_FUNC_ADD,
                        GL14.GL_FUNC_ADD,
                        blend,
                        false,
                        s,
                        s,
                        mask,
                        range,
                        false,
                        GL11.GL_BACK,
                        GL11.GL_CCW,
                        false,
                        0,
                        0);
        blend[0] = 99;
        range[0] = 99;
        mask[0] = false;
        require(
                r.blendColor()[0] == 0.25f && r.depthRange()[0] == 0.125f && r.colorMask()[0],
                "constructor borrowed mutable array");
        FontRenderContext old =
                new FontRenderContext(
                        new club.heiqi.uilib.host.client.font.context.FontProjection(
                                IDENTITY, IDENTITY, new int[] {0, 0, 64, 64}),
                        0,
                        64,
                        false,
                        GL11.GL_LESS,
                        true,
                        false,
                        new int[] {0, 0, 64, 64},
                        FontRenderContext.MatrixState.ui(IDENTITY, IDENTITY),
                        new Object(),
                        0,
                        0);
        require(
                old.rasterState == null,
                "legacy constructor synthesized driver snapshot without context");
        Object identity = new Object();
        FontRenderContext a = context(identity, IDENTITY, IDENTITY, new float[]{1, 1, 1, 1}, r);
        FontRenderContext equal = context(identity, IDENTITY.clone(), IDENTITY.clone(), new float[]{1, 1, 1, 1}, r);
        require(a.sameDrawState(equal) && equal.sameDrawState(a), "等值新快照不兼容");
        require(!a.sameDrawState(context(new Object(), IDENTITY, IDENTITY, new float[]{1, 1, 1, 1}, r)),
                "不同 GL context 被合并");
        float[] negativeZero = IDENTITY.clone();
        negativeZero[12] = -0.0f;
        require(!a.sameDrawState(context(identity, negativeZero, IDENTITY, new float[]{1, 1, 1, 1}, r)),
                "原始浮点位身份被数值判等抹平");
        float[] scaledProjection = IDENTITY.clone(), inverseModel = IDENTITY.clone();
        scaledProjection[0] = 2; inverseModel[0] = 0.5f;
        require(!a.sameDrawState(context(identity, scaledProjection, inverseModel, new float[]{1, 1, 1, 1}, r)),
                "相同 MVP 不能替代各自的 P/MV shader 语义");
        require(!a.sameDrawState(context(identity, IDENTITY, IDENTITY, new float[]{1, 1, 1, 0.5f}, r)),
                "动态颜色被忽略");
        float[] borrowed = equal.state.color(); borrowed[3] = 0;
        require(a.sameDrawState(equal) && !a.sameDrawState(null), "值比较破坏防御复制或 null 边界");
        FontRenderContext font = StandardFontContext.from(a);
        require(!font.sameDrawState(StandardFontContext.from(
                        context(identity, negativeZero, IDENTITY, new float[]{1, 1, 1, 1}, r))),
                "标准字体派生抹平浮点原始位");
        require(!font.sameDrawState(StandardFontContext.from(
                        context(identity, scaledProjection, inverseModel, new float[]{1, 1, 1, 1}, r))),
                "标准字体派生将不同 P/MV 压为相同 MVP");
        verifyStandardValue();
    }

    private static void verifyStandardValue() {
        Object identity = new Object();
        float[] p = IDENTITY.clone(), mv = IDENTITY.clone();
        p[12] = -0.0f; mv[13] = -0.0f;
        float[] color = {0.25f, 0.5f, 0.75f, 0.5f};
        float[] normal = {-0.0f, 0.5f, 1}, lighting = {0.125f, 0.25f, 0.5f};
        float[] blend = {-0.0f, 0.25f, 0.5f, 1}, range = {-0.0f, 0.875f};
        boolean[] mask = {false, true, false, true};
        int[] viewport = {7, 11, 80, 96}, scissor = {3, 5, 37, 41};
        FontRenderContext.StencilFace front = new FontRenderContext.StencilFace(
                GL11.GL_EQUAL, 3, 0x0f, 0xf0, GL11.GL_REPLACE, GL11.GL_INCR, GL11.GL_DECR);
        FontRenderContext.StencilFace back = new FontRenderContext.StencilFace(
                GL11.GL_NOTEQUAL, 5, 0xf0, 0x0f, GL11.GL_ZERO, GL11.GL_DECR, GL11.GL_INCR);
        FontRenderContext.MatrixState state = new FontRenderContext.MatrixState(p, mv, color,
                true, GL11.GL_GEQUAL, -0.0f, true, 17, -0.0f, 0.75f, 0x12345678,
                normal, lighting, 29);
        FontRenderContext.RasterState raster = new FontRenderContext.RasterState(
                false, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ZERO, GL11.GL_ONE,
                GL14.GL_FUNC_SUBTRACT, GL14.GL_FUNC_REVERSE_SUBTRACT, blend,
                true, front, back, mask, range, true, GL11.GL_FRONT, GL11.GL_CW, true, -0.0f, 2.5f);
        FontRenderContext source = new FontRenderContext(new FontProjection(p, mv, viewport),
                31, 128, true, GL11.GL_GEQUAL, false, true, scissor, state, identity, 37L, 41, raster);

        // 独立公开构造表达标准字体期望值：只改 white currentColor 与 coverage 混合。
        FontRenderContext.MatrixState expectedState = new FontRenderContext.MatrixState(p, mv,
                new float[]{1, 1, 1, 1}, true, GL11.GL_GEQUAL, -0.0f, true, 17, -0.0f, 0.75f,
                0x12345678, normal, lighting, 29);
        FontRenderContext.RasterState expectedRaster = new FontRenderContext.RasterState(
                true, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE,
                GL11.GL_ONE_MINUS_SRC_ALPHA, GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD, blend,
                true, front, back, mask, range, true, GL11.GL_FRONT, GL11.GL_CW, true, -0.0f, 2.5f);
        FontRenderContext expected = new FontRenderContext(new FontProjection(p, mv, viewport),
                31, 128, true, GL11.GL_GEQUAL, false, true, scissor, expectedState, identity, 37L, 41, expectedRaster);
        FontRenderContext font = StandardFontContext.from(source);
        require(font.sameDrawState(expected) && expected.sameDrawState(font), "标准字体派生遗漏历史状态");
        require(font.projection == source.projection && font.rasterState.frontStencil == front
                        && font.rasterState.backStencil == back, "不可变投影或 stencil 身份丢失");
        require(source.state.color()[0] == 0.25f && !source.rasterState.blendEnabled, "派生改写 caller");

        // 共享只在冻结对象之间成立；构造输入与任一公开 getter 都不能泄漏共享载荷。
        p[0] = mv[0] = color[0] = normal[0] = lighting[0] = blend[0] = range[0] = 99;
        viewport[2] = scissor[2] = 1; mask[0] = true;
        for (FontRenderContext value : new FontRenderContext[]{source, font}) {
            value.scissor()[0] = 99;
            value.projection.projection()[0] = 99; value.projection.modelView()[0] = 99;
            value.projection.mvp()[0] = 99; value.projection.viewport()[0] = 99;
            value.state.projection()[0] = 99; value.state.modelView()[0] = 99;
            value.state.color()[0] = 99; value.state.normal()[0] = 99; value.state.lighting()[0] = 99;
            value.rasterState.blendColor()[0] = 99; value.rasterState.depthRange()[0] = 99;
            value.rasterState.colorMask()[0] = true;
        }
        require(font.sameDrawState(expected) && StandardFontContext.from(source).sameDrawState(expected)
                        && StandardFontContext.from(font).sameDrawState(expected)
                        && java.util.Arrays.equals(font.projection.mvp(), expected.projection.mvp()),
                "防御复制失效污染来源、派生或共享 white");
        FontRenderContext legacy = new FontRenderContext(source.projection,
                31, 128, true, GL11.GL_GEQUAL, false, true, source.scissor(), state, identity, 37L, 41);
        FontRenderContext expectedLegacy = new FontRenderContext(expected.projection,
                31, 128, true, GL11.GL_GEQUAL, false, true, expected.scissor(), expectedState, identity, 37L, 41);
        FontRenderContext legacyFont = StandardFontContext.from(legacy);
        require(legacyFont.rasterState == null && legacyFont.sameDrawState(expectedLegacy),
                "legacy 标准字体派生改变历史语义或合成光栅快照");
    }

    private static FontRenderContext context(Object identity, float[] projection, float[] model,
            float[] color, FontRenderContext.RasterState raster) {
        FontRenderContext.MatrixState matrix = new FontRenderContext.MatrixState(projection, model, color,
                false, GL11.GL_GREATER, 0, false, 0, 0, 0, 0,
                new float[]{0, 0, 1}, new float[]{1, 1, 1}, -1);
        return new FontRenderContext(new club.heiqi.uilib.host.client.font.context.FontProjection(
                projection, model, new int[]{0, 0, 64, 64}), 0, 64, false, GL11.GL_LESS, true,
                false, new int[]{0, 0, 64, 64}, matrix, identity, 0, 0, raster);
    }

}
