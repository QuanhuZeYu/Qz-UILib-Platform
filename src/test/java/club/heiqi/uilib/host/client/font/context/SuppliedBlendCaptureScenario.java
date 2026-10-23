package club.heiqi.uilib.host.client.font.context;

import java.util.Arrays;
import club.heiqi.uilib.host.client.gl.GlApi;
import static club.heiqi.uilib.host.client.gl.GlTokens.*;

/** 在真实GL的A状态下供给不同的effective blend，非blend仍必须取A且不得二次读blend。 */
public final class SuppliedBlendCaptureScenario {
    private static final int GL_ZERO = 0, GL_FUNC_SUBTRACT = 0x800A;
    private SuppliedBlendCaptureScenario() {}
    public static void verify(GlApi gl, FontRenderContext.RasterState original) {
        LwjglFontContextCapture.RasterQueries queries = new LwjglFontContextCapture.RasterQueries() {
            public GlApi gl() { return gl; }
            public boolean enabled(int name) {
                if (name == GL_BLEND) throw new AssertionError("已供给effective blend仍查询普通enable");
                return LwjglFontContextCapture.RasterQueries.super.enabled(name);
            }
            public int integer(int name) {
                if (name == GL_BLEND_SRC_RGB || name == GL_BLEND_DST_RGB
                        || name == GL_BLEND_SRC_ALPHA || name == GL_BLEND_DST_ALPHA
                        || name == GL_BLEND_EQUATION_RGB || name == GL_BLEND_EQUATION_ALPHA)
                    throw new AssertionError("已供给effective blend仍查询普通factor/equation");
                return LwjglFontContextCapture.RasterQueries.super.integer(name);
            }
            public float[] floats(int name, int count) {
                if (name == GL_BLEND_COLOR) throw new AssertionError("已供给effective blend仍查询普通color");
                return LwjglFontContextCapture.RasterQueries.super.floats(name, count);
            }
        };
        float[] color = {.75f, .25f, .5f, 1};
        FontRenderContext.RasterState supplied = EffectiveBlendCapture.capture(queries,
                false, GL_ONE, GL_ZERO, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA,
                GL_FUNC_ADD, GL_FUNC_SUBTRACT, color);
        require(!supplied.blendEnabled && supplied.blendSrcRgb == GL_ONE
                && supplied.blendDstRgb == GL_ZERO && supplied.blendSrcAlpha == GL_SRC_ALPHA
                && supplied.blendDstAlpha == GL_ONE_MINUS_SRC_ALPHA
                && supplied.blendEquationRgb == GL_FUNC_ADD && supplied.blendEquationAlpha == GL_FUNC_SUBTRACT
                && Arrays.equals(supplied.blendColor(), color), "effective blend未优先于driver快照");
        color[0] = 99;
        require(supplied.blendColor()[0] == .75f, "供给数组未冻结");
        sameNonBlend(original, supplied);
        FontRenderContext.RasterState current = LwjglFontContextCapture.driverCapture(gl);
        sameNonBlend(original, current);
        require(current.blendEnabled == original.blendEnabled
                && current.blendSrcRgb == original.blendSrcRgb
                && current.blendDstRgb == original.blendDstRgb
                && current.blendSrcAlpha == original.blendSrcAlpha
                && current.blendDstAlpha == original.blendDstAlpha
                && current.blendEquationRgb == original.blendEquationRgb
                && current.blendEquationAlpha == original.blendEquationAlpha
                && Arrays.equals(current.blendColor(), original.blendColor()), "捕获修改了driver blend");
        System.out.println("SUPPLIED_EFFECTIVE_BLEND_PASS genericBlendQueries=0 nonBlendNative=true immutable=true driverUnchanged=true");
    }
    private static void sameNonBlend(FontRenderContext.RasterState a, FontRenderContext.RasterState b) {
        require(a.stencilEnabled == b.stencilEnabled
                && Arrays.equals(face(a.frontStencil), face(b.frontStencil))
                && Arrays.equals(face(a.backStencil), face(b.backStencil))
                && Arrays.equals(a.colorMask(), b.colorMask()) && Arrays.equals(a.depthRange(), b.depthRange())
                && a.cullEnabled == b.cullEnabled && a.cullFace == b.cullFace && a.frontFace == b.frontFace
                && a.polygonOffsetFill == b.polygonOffsetFill
                && a.polygonOffsetFactor == b.polygonOffsetFactor && a.polygonOffsetUnits == b.polygonOffsetUnits,
                "effective blend供给改变了非blend捕获");
    }
    private static int[] face(FontRenderContext.StencilFace f) {
        return new int[] {f.function, f.reference, f.valueMask, f.writeMask, f.fail, f.depthFail, f.depthPass};
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
