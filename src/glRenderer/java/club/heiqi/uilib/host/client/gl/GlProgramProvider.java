package club.heiqi.uilib.host.client.gl;

import club.heiqi.uilib.host.client.font.context.FontRenderContext;

/** preview只读查询适用性；open匹配捕获的program/pipeline代际并打开pass。 */
public interface GlProgramProvider {
    boolean preview(FontRenderContext context);

    GlProgramScope open(FontRenderContext context);

    /**
     * 首次有效 pass 打开后，由 backend 在所属 GL 上下文内调用。每次返回独立 producer；
     * backend跨短scope缓存并负责关闭。有效pass须提供与其顶点ABI匹配的producer；
     * 默认实现报告能力缺失，普通路径由preview/open选择。
     */
    default GlVertexProducer createVertexProducer(GlApi gl, int capacity) {
        throw new UnsupportedOperationException("有效宿主 pass 未提供 vertex producer");
    }

    GlProgramProvider NONE =
            new GlProgramProvider() {
                public boolean preview(FontRenderContext context) {
                    return false;
                }

                public GlProgramScope open(FontRenderContext context) {
                    return null;
                }
            };
}
