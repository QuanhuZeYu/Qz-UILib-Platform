package club.heiqi.uilib.host.client.gl;

import static club.heiqi.uilib.host.client.gl.GlTokens.*;

import club.heiqi.uilib.host.client.font.context.FontRenderContext;
import java.util.Arrays;

/** 短world范围的actual GL target/depth/lightmap保护；不改变GLSM有效缓存或固定矩阵。
 * typed RasterState恢复捕获的历史状态；null旧value合同保留回放现场实际stencil/color mask。
 * lightmap UV已由有效provider乘unit1 texture matrix，不能再乘一次。 */
final class GlFontWorldState implements AutoCloseable {
    private final GlApi gl;
    final FontRenderContext context;
    // 与同一短范围的 Scope 共用 actual 进入态；重叠的 blend/cull 只在此处恢复。
    final FontRenderContext.RasterState entryRaster;
    final int unit;
    final boolean depth, scissor;
    final int[] box = new int[4];
    private final int fbo, depthFunction;
    private final boolean depthWrite;
    private final int[] viewport = new int[4], targetViewport;
    private final int lightmapTexture, sampler;
    private final boolean samplers;
    private int rasterChanges;
    private boolean differential;

    GlFontWorldState(GlApi gl, FontRenderContext context) {
        this.gl = gl;
        entryRaster = GlFontRasterState.capture(gl);
        fbo = gl.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        depthFunction = gl.glGetInteger(GL_DEPTH_FUNC);
        unit = gl.glGetInteger(GL_ACTIVE_TEXTURE);
        depthWrite = gl.glGetBoolean(GL_DEPTH_WRITEMASK);
        depth = gl.glIsEnabled(GL_DEPTH_TEST);
        scissor = gl.glIsEnabled(GL_SCISSOR_TEST);
        gl.glGetIntegerv(GL_SCISSOR_BOX, box);
        samplers = gl.capabilities().samplerObjects;
        if (context.contextIdentity != gl.capabilities().contextIdentity)
            throw new IllegalStateException("字体context身份失配，禁止跨context回放");
        this.context = context;
        targetViewport = context.projection.viewport();
        gl.glGetIntegerv(GL_VIEWPORT, viewport);
        try {
            if (unit != GL_TEXTURE1) gl.glActiveTexture(GL_TEXTURE1);
            lightmapTexture = gl.glGetInteger(GL_TEXTURE_BINDING_2D);
            sampler = samplers ? gl.glGetInteger(GL_SAMPLER_BINDING) : 0;
        } finally {
            if (unit != GL_TEXTURE1) gl.glActiveTexture(unit);
        }
    }

    void applyIris() {
        // 外部 Pass.use 按当前 program/阶段拥有目标与 blend overrides；历史 caller FBO
        // 可属于另一 pass，不能用句柄相等约束或重新绑定它。viewport/depth/scissor 仍取提交快照。
        if (context.rasterState != null) GlFontRasterState.applyNonBlend(gl, context.rasterState);
        gl.glViewport(targetViewport[0], targetViewport[1], targetViewport[2], targetViewport[3]);
        gl.glDepthFunc(context.depthFunction);
        gl.glDepthMask(context.depthWrite);
        if (context.depthTest) gl.glEnable(GL_DEPTH_TEST);
        else gl.glDisable(GL_DEPTH_TEST);
        int[] s = context.scissor();
        gl.glScissor(s[0], s[1], s[2], s[3]);
        if (context.scissorEnabled) gl.glEnable(GL_SCISSOR_TEST);
        else gl.glDisable(GL_SCISSOR_TEST);
    }

    void apply() {
        if (context.rasterState != null) {
            // preview 为只读，typed 普通范围内也无外部 GL 写入。先选择配对差异恢复，
            // 再做任何 setter；中途失败仍恢复整组原定差异，不能只恢复已完成的步骤。
            rasterChanges = GlFontRasterState.changes(entryRaster, context.rasterState);
            differential = true;
            applyTyped(false);
            return;
        }
        gl.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, context.drawFramebuffer);
        gl.glViewport(targetViewport[0], targetViewport[1], targetViewport[2], targetViewport[3]);
        gl.glDepthFunc(context.depthFunction);
        gl.glDepthMask(context.depthWrite);
        if (context.state.lightmapActive) {
            gl.glActiveTexture(GL_TEXTURE1);
            gl.glBindTexture(GL_TEXTURE_2D, context.state.lightmapTexture);
            if (samplers) gl.glBindSampler(1, 0);
            gl.glActiveTexture(GL_TEXTURE0);
        }
    }

    boolean differential() {
        return differential;
    }

    private void applyTyped(boolean restore) {
        if (restore) GlFontRasterState.apply(gl, entryRaster, rasterChanges);
        if (fbo != context.drawFramebuffer)
            gl.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, restore ? fbo : context.drawFramebuffer);
        if (!Arrays.equals(viewport, targetViewport)) {
            int[] v = restore ? viewport : targetViewport;
            gl.glViewport(v[0], v[1], v[2], v[3]);
        }
        if (depthFunction != context.depthFunction)
            gl.glDepthFunc(restore ? depthFunction : context.depthFunction);
        if (depthWrite != context.depthWrite) gl.glDepthMask(restore ? depthWrite : context.depthWrite);
        if (!restore) GlFontRasterState.apply(gl, context.rasterState, rasterChanges);
        boolean textureChanged = context.state.lightmapActive && lightmapTexture != context.state.lightmapTexture;
        boolean samplerChanged = context.state.lightmapActive && samplers && sampler != 0;
        if (textureChanged) {
            gl.glActiveTexture(GL_TEXTURE1);
            gl.glBindTexture(GL_TEXTURE_2D, restore ? lightmapTexture : context.state.lightmapTexture);
        }
        if (samplerChanged) gl.glBindSampler(1, restore ? sampler : 0);
        // close 也覆盖 Scope 自身清理中途失败，不能依赖它已恢复活动单元。
        if (textureChanged || restore) gl.glActiveTexture(restore ? unit : GL_TEXTURE0);
    }

    @Override
    public void close() {
        if (differential) {
            applyTyped(true);
            return;
        }
        GlFontRasterState.apply(gl, entryRaster);
        gl.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        gl.glDepthFunc(depthFunction);
        gl.glDepthMask(depthWrite);
        gl.glActiveTexture(GL_TEXTURE1);
        gl.glBindTexture(GL_TEXTURE_2D, lightmapTexture);
        if (samplers) gl.glBindSampler(1, sampler);
        gl.glActiveTexture(unit);
    }
}
