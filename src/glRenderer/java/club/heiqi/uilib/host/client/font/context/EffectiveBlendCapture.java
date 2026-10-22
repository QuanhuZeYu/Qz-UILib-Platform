package club.heiqi.uilib.host.client.font.context;

import java.util.Objects;
import club.heiqi.uilib.host.client.gl.GlApi;
import static club.heiqi.uilib.host.client.gl.GlTokens.*;

/** 本次宿主effective blend覆盖；其余状态仍由统一光栅捕获从同一queries读取。
 * 不跨调用保存状态，不重复查询将被覆盖的blend，也不产生随后丢弃的RasterState。 */
public final class EffectiveBlendCapture implements LwjglFontContextCapture.RasterQueries {
    private final LwjglFontContextCapture.RasterQueries queries;
    private final boolean blendEnabled;
    private final int srcRgb, dstRgb, srcAlpha, dstAlpha, equationRgb, equationAlpha;
    private final float[] color;

    private EffectiveBlendCapture(LwjglFontContextCapture.RasterQueries queries, boolean blendEnabled,
            int srcRgb, int dstRgb, int srcAlpha, int dstAlpha, int equationRgb, int equationAlpha,
            float[] color) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.blendEnabled = blendEnabled;
        this.srcRgb = srcRgb; this.dstRgb = dstRgb;
        this.srcAlpha = srcAlpha; this.dstAlpha = dstAlpha;
        this.equationRgb = equationRgb; this.equationAlpha = equationAlpha;
        this.color = color;
    }

    /** 同步捕获结束前输入只作临时借用，返回值由RasterState统一校验并防御复制。 */
    public static FontRenderContext.RasterState capture(LwjglFontContextCapture.RasterQueries queries,
            boolean blendEnabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha,
            int equationRgb, int equationAlpha, float[] color) {
        return LwjglFontContextCapture.captureRaster(new EffectiveBlendCapture(queries, blendEnabled,
                srcRgb, dstRgb, srcAlpha, dstAlpha, equationRgb, equationAlpha, color));
    }

    @Override public GlApi gl() { return queries.gl(); }
    @Override public boolean enabled(int name) {
        return name == GL_BLEND ? blendEnabled : queries.enabled(name);
    }
    @Override public int integer(int name) {
        switch (name) {
            case GL_BLEND_SRC_RGB: return srcRgb;
            case GL_BLEND_DST_RGB: return dstRgb;
            case GL_BLEND_SRC_ALPHA: return srcAlpha;
            case GL_BLEND_DST_ALPHA: return dstAlpha;
            case GL_BLEND_EQUATION_RGB: return equationRgb;
            case GL_BLEND_EQUATION_ALPHA: return equationAlpha;
            default: return queries.integer(name);
        }
    }
    @Override public int[] integers(int name, int count) { return queries.integers(name, count); }
    @Override public boolean[] booleans(int name, int count) { return queries.booleans(name, count); }
    @Override public float[] floats(int name, int count) {
        return name == GL_BLEND_COLOR ? color : queries.floats(name, count);
    }
}
