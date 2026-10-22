package club.heiqi.uilib.host.client.gl;

import static club.heiqi.uilib.host.client.gl.GlTokens.*;

import club.heiqi.uilib.host.client.font.context.FontRenderContext;

/** 短world范围的actual GL target/depth/lightmap保护；不改变GLSM有效缓存或固定矩阵。
 * typed RasterState恢复捕获的历史状态；null旧value合同保留回放现场实际stencil/color mask。
 * lightmap UV已由有效provider乘unit1 texture matrix，不能再乘一次。 */
final class GlFontWorldState implements AutoCloseable {
    private final GlApi gl;
    final FontRenderContext context;
    private final FontRenderContext.RasterState entryRaster;
    private final int fbo, depthFunction, unit;
    private final boolean depthWrite;
    private final int[] viewport = new int[4];
    private final int lightmapTexture, sampler;
    private final boolean samplers;

    GlFontWorldState(GlApi gl, FontRenderContext context) {
        this.gl = gl;
        entryRaster = GlFontRasterState.capture(gl);
        fbo = gl.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        depthFunction = gl.glGetInteger(GL_DEPTH_FUNC);
        unit = gl.glGetInteger(GL_ACTIVE_TEXTURE);
        depthWrite = gl.glGetBoolean(GL_DEPTH_WRITEMASK);
        samplers = gl.capabilities().samplerObjects;
        if (context.contextIdentity != gl.capabilities().contextIdentity)
            throw new IllegalStateException("字体context身份失配，禁止跨context回放");
        this.context = context;
        gl.glGetIntegerv(GL_VIEWPORT, viewport);
        gl.glActiveTexture(GL_TEXTURE1);
        lightmapTexture = gl.glGetInteger(GL_TEXTURE_BINDING_2D);
        sampler = samplers ? gl.glGetInteger(GL_SAMPLER_BINDING) : 0;
        gl.glActiveTexture(unit);
    }

    void applyIris() {
        // 外部 Pass.use 按当前 program/阶段拥有目标与 blend overrides；历史 caller FBO
        // 可属于另一 pass，不能用句柄相等约束或重新绑定它。viewport/depth/scissor 仍取提交快照。
        if (context.rasterState != null) GlFontRasterState.applyNonBlend(gl, context.rasterState);
        int[] v = context.projection.viewport();
        gl.glViewport(v[0], v[1], v[2], v[3]);
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
        gl.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, context.drawFramebuffer);
        int[] v = context.projection.viewport();
        gl.glViewport(v[0], v[1], v[2], v[3]);
        gl.glDepthFunc(context.depthFunction);
        gl.glDepthMask(context.depthWrite);
        if (context.rasterState != null) GlFontRasterState.apply(gl, context.rasterState);
        if (context.state.lightmapActive) {
            gl.glActiveTexture(GL_TEXTURE1);
            gl.glBindTexture(GL_TEXTURE_2D, context.state.lightmapTexture);
            if (samplers) gl.glBindSampler(1, 0);
            gl.glActiveTexture(GL_TEXTURE0);
        }
    }

    @Override
    public void close() {
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
