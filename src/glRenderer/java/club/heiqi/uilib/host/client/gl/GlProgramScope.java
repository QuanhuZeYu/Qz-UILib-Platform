package club.heiqi.uilib.host.client.gl;

import java.util.List;

/** 可选宿主 program pass；宿主选择 program 与输出目标（可不同于历史 caller FBO），
 * 并在短 scope 退出时恢复有效矩阵、实体、纹理缓存及原 pass。 */
public interface GlProgramScope extends AutoCloseable {
    int programId();

    List<GlProgramAttribute> attributes();

    float[] normal();

    int packedLight();

    float[] extended(String name, int type);

    void configure(
            int vao, int vbo, int ebo, boolean uv, boolean color, boolean lightmap, boolean normal);

    void pointer(int location, int size, int type, boolean normalized, int stride, long offset);

    void disable(int location);

    void bindTexture2D(int texture);

    void beforeDraw();

    void close();
}
