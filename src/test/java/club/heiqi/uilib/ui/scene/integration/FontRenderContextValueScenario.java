package club.heiqi.uilib.ui.scene.integration;

import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.host.client.font.context.FontRenderContext;
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
