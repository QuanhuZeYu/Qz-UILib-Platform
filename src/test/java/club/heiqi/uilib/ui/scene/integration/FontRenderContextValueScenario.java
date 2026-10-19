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
    }

}
