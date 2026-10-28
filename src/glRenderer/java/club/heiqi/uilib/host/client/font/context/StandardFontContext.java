package club.heiqi.uilib.host.client.font.context;

import static club.heiqi.uilib.host.client.gl.GlTokens.*;

/** 由已捕获快照派生标准字体方法的颜色与混合语义。 */
public final class StandardFontContext {
    private StandardFontContext() {}

    public static FontRenderContext from(FontRenderContext context) {
        // 显式文字颜色替换current color，格式颜色已由布局保存；其余已冻结载荷同包共享。
        FontRenderContext.MatrixState fontState = new FontRenderContext.MatrixState(context.state);
        FontRenderContext.RasterState raster = context.rasterState;
        // 单色atlas存coverage，标准文字显式启用其覆盖率混合。
        // typed replay输出直色，因此RGB乘一次源alpha，目标alpha采用覆盖率合成。其余历史限制仍来自caller。
        // 这里只创建值快照；backend恢复actual入口，Iris Pass.use仍拥有其blend override。
        FontRenderContext.RasterState fontRaster =
                raster == null
                        ? null
                        : new FontRenderContext.RasterState(
                                raster,
                                GL_SRC_ALPHA,
                                GL_ONE_MINUS_SRC_ALPHA,
                                GL_ONE,
                                GL_ONE_MINUS_SRC_ALPHA,
                                GL_FUNC_ADD,
                                GL_FUNC_ADD);
        return new FontRenderContext(context, fontState, fontRaster);
    }
}
