package club.heiqi.uilib.host.client.gl;

import static club.heiqi.uilib.host.client.gl.GlTokens.*;

import java.util.Arrays;
import club.heiqi.uilib.host.client.font.context.FontRenderContext.RasterState;
import club.heiqi.uilib.host.client.font.context.FontRenderContext.StencilFace;

/** world历史光栅载荷只短时应用；退出恢复actual进入态，不写宿主有效缓存。 */
final class GlFontRasterState {
    private GlFontRasterState() {}

    static RasterState capture(GlApi gl) {
        float[] color = new float[4], range = new float[2];
        gl.glGetFloatv(GL_BLEND_COLOR, color);
        gl.glGetFloatv(GL_DEPTH_RANGE, range);
        boolean[] mask = new boolean[4];
        for (int i = 0; i < 4; i++) mask[i] = false;
        int[] maskInts = new int[4];
        gl.glGetIntegerv(GL_COLOR_WRITEMASK, maskInts);
        for (int i = 0; i < 4; i++) mask[i] = maskInts[i] != 0;
        return new RasterState(
                gl.glIsEnabled(GL_BLEND),
                gl.glGetInteger(GL_BLEND_SRC_RGB),
                gl.glGetInteger(GL_BLEND_DST_RGB),
                gl.glGetInteger(GL_BLEND_SRC_ALPHA),
                gl.glGetInteger(GL_BLEND_DST_ALPHA),
                gl.glGetInteger(GL_BLEND_EQUATION_RGB),
                gl.glGetInteger(GL_BLEND_EQUATION_ALPHA),
                color,
                gl.glIsEnabled(GL_STENCIL_TEST),
                stencil(gl, false),
                stencil(gl, true),
                mask,
                range,
                gl.glIsEnabled(GL_CULL_FACE),
                gl.glGetInteger(GL_CULL_FACE_MODE),
                gl.glGetInteger(GL_FRONT_FACE),
                gl.glIsEnabled(GL_POLYGON_OFFSET_FILL),
                gl.glGetFloat(GL_POLYGON_OFFSET_FACTOR),
                gl.glGetFloat(GL_POLYGON_OFFSET_UNITS));
    }

    private static StencilFace stencil(GlApi gl, boolean back) {
        return new StencilFace(
                gl.glGetInteger(back ? GL_STENCIL_BACK_FUNC : GL_STENCIL_FUNC),
                gl.glGetInteger(back ? GL_STENCIL_BACK_REF : GL_STENCIL_REF),
                gl.glGetInteger(back ? GL_STENCIL_BACK_VALUE_MASK : GL_STENCIL_VALUE_MASK),
                gl.glGetInteger(back ? GL_STENCIL_BACK_WRITEMASK : GL_STENCIL_WRITEMASK),
                gl.glGetInteger(back ? GL_STENCIL_BACK_FAIL : GL_STENCIL_FAIL),
                gl.glGetInteger(
                        back ? GL_STENCIL_BACK_PASS_DEPTH_FAIL : GL_STENCIL_PASS_DEPTH_FAIL),
                gl.glGetInteger(
                        back ? GL_STENCIL_BACK_PASS_DEPTH_PASS : GL_STENCIL_PASS_DEPTH_PASS));
    }

    private static final int BLEND = 1, BLEND_FUNC = 1 << 1, BLEND_EQUATION = 1 << 2,
            BLEND_COLOR = 1 << 3, STENCIL = 1 << 4, FRONT_FUNC = 1 << 5,
            FRONT_MASK = 1 << 6, FRONT_OP = 1 << 7, BACK_FUNC = 1 << 8,
            BACK_MASK = 1 << 9, BACK_OP = 1 << 10, COLOR_MASK = 1 << 11,
            DEPTH_RANGE = 1 << 12, CULL = 1 << 13, CULL_FACE = 1 << 14,
            FRONT_FACE = 1 << 15, POLYGON_FILL = 1 << 16, POLYGON_OFFSET = 1 << 17;

    /** 同一封闭 typed scope 的进入态与目标态决定同一组 apply/restore；不记录完成进度。 */
    static int changes(RasterState entry, RasterState target) {
        int changes = 0;
        if (entry.blendEnabled != target.blendEnabled) changes |= BLEND;
        if (entry.blendSrcRgb != target.blendSrcRgb || entry.blendDstRgb != target.blendDstRgb
                || entry.blendSrcAlpha != target.blendSrcAlpha || entry.blendDstAlpha != target.blendDstAlpha)
            changes |= BLEND_FUNC;
        if (entry.blendEquationRgb != target.blendEquationRgb
                || entry.blendEquationAlpha != target.blendEquationAlpha) changes |= BLEND_EQUATION;
        if (!Arrays.equals(entry.blendColor(), target.blendColor())) changes |= BLEND_COLOR;
        if (entry.stencilEnabled != target.stencilEnabled) changes |= STENCIL;
        changes |= stencilChanges(entry.frontStencil, target.frontStencil, FRONT_FUNC, FRONT_MASK, FRONT_OP);
        changes |= stencilChanges(entry.backStencil, target.backStencil, BACK_FUNC, BACK_MASK, BACK_OP);
        if (!Arrays.equals(entry.colorMask(), target.colorMask())) changes |= COLOR_MASK;
        if (!Arrays.equals(entry.depthRange(), target.depthRange())) changes |= DEPTH_RANGE;
        if (entry.cullEnabled != target.cullEnabled) changes |= CULL;
        if (entry.cullFace != target.cullFace) changes |= CULL_FACE;
        if (entry.frontFace != target.frontFace) changes |= FRONT_FACE;
        if (entry.polygonOffsetFill != target.polygonOffsetFill) changes |= POLYGON_FILL;
        if (Float.floatToRawIntBits(entry.polygonOffsetFactor) != Float.floatToRawIntBits(target.polygonOffsetFactor)
                || Float.floatToRawIntBits(entry.polygonOffsetUnits) != Float.floatToRawIntBits(target.polygonOffsetUnits))
            changes |= POLYGON_OFFSET;
        return changes;
    }

    private static int stencilChanges(StencilFace entry, StencilFace target, int function, int mask, int op) {
        int changes = 0;
        if (entry.function != target.function || entry.reference != target.reference || entry.valueMask != target.valueMask)
            changes |= function;
        if (entry.writeMask != target.writeMask) changes |= mask;
        if (entry.fail != target.fail || entry.depthFail != target.depthFail || entry.depthPass != target.depthPass)
            changes |= op;
        return changes;
    }

    static void apply(GlApi gl, RasterState s) {
        apply(gl, s, -1);
    }

    static void apply(GlApi gl, RasterState s, int changes) {
        if ((changes & BLEND) != 0) enable(gl, GL_BLEND, s.blendEnabled);
        if ((changes & BLEND_FUNC) != 0)
            gl.glBlendFuncSeparate(s.blendSrcRgb, s.blendDstRgb, s.blendSrcAlpha, s.blendDstAlpha);
        if ((changes & BLEND_EQUATION) != 0)
            gl.glBlendEquationSeparate(s.blendEquationRgb, s.blendEquationAlpha);
        if ((changes & BLEND_COLOR) != 0) {
            float[] c = s.blendColor();
            gl.glBlendColor(c[0], c[1], c[2], c[3]);
        }
        applyNonBlend(gl, s, changes);
    }

    static void applyNonBlend(GlApi gl, RasterState s) {
        applyNonBlend(gl, s, -1);
    }

    private static void applyNonBlend(GlApi gl, RasterState s, int changes) {
        if ((changes & STENCIL) != 0) enable(gl, GL_STENCIL_TEST, s.stencilEnabled);
        stencil(gl, GL_FRONT, s.frontStencil, changes, FRONT_FUNC, FRONT_MASK, FRONT_OP);
        stencil(gl, GL_BACK, s.backStencil, changes, BACK_FUNC, BACK_MASK, BACK_OP);
        if ((changes & COLOR_MASK) != 0) {
            boolean[] m = s.colorMask();
            gl.glColorMask(m[0], m[1], m[2], m[3]);
        }
        if ((changes & DEPTH_RANGE) != 0) {
            float[] range = s.depthRange();
            gl.glDepthRange(range[0], range[1]);
        }
        if ((changes & CULL) != 0) enable(gl, GL_CULL_FACE, s.cullEnabled);
        if ((changes & CULL_FACE) != 0) gl.glCullFace(s.cullFace);
        if ((changes & FRONT_FACE) != 0) gl.glFrontFace(s.frontFace);
        if ((changes & POLYGON_FILL) != 0) enable(gl, GL_POLYGON_OFFSET_FILL, s.polygonOffsetFill);
        if ((changes & POLYGON_OFFSET) != 0) gl.glPolygonOffset(s.polygonOffsetFactor, s.polygonOffsetUnits);
    }

    private static void stencil(GlApi gl, int face, StencilFace s, int changes, int function, int mask, int op) {
        if ((changes & function) != 0) gl.glStencilFuncSeparate(face, s.function, s.reference, s.valueMask);
        if ((changes & mask) != 0) gl.glStencilMaskSeparate(face, s.writeMask);
        if ((changes & op) != 0) gl.glStencilOpSeparate(face, s.fail, s.depthFail, s.depthPass);
    }

    private static void enable(GlApi gl, int capability, boolean enabled) {
        if (enabled) gl.glEnable(capability);
        else gl.glDisable(capability);
    }
}
