package club.heiqi.uilib.host.client.font.context;

import java.util.Objects;
import club.heiqi.uilib.ui.scene.text.RasterSampling;

/** 一次caller提交的不可变值上下文，回放限于同一context/pass，GL对象由所属owner管理。
 * revision标识字体/采样策略/target代际；camera变化由投影快照表达。
 * image/icon通过各自key维护资源代际与失效。 */
public final class FontRenderContext {
    public final FontProjection projection;
    public final int drawFramebuffer, sourceFramebufferHeight, depthFunction, passKey;
    public final boolean depthTest, depthWrite, scissorEnabled;
    public final long revision;
    public final Object contextIdentity;
    public final MatrixState state;

    /** capture提供typed光栅状态；旧即时UI值构造以null表达沿用回放现场状态。 */
    public final RasterState rasterState;

    private final int[] scissor;

    public FontRenderContext(
            FontProjection projection,
            int framebuffer,
            int height,
            boolean depthTest,
            int depthFunction,
            boolean depthWrite,
            boolean scissorEnabled,
            int[] scissor,
            MatrixState state,
            Object identity,
            long revision,
            int passKey) {
        this(
                projection,
                framebuffer,
                height,
                depthTest,
                depthFunction,
                depthWrite,
                scissorEnabled,
                scissor,
                state,
                identity,
                revision,
                passKey,
                null);
    }

    public FontRenderContext(
            FontProjection projection,
            int framebuffer,
            int height,
            boolean depthTest,
            int depthFunction,
            boolean depthWrite,
            boolean scissorEnabled,
            int[] scissor,
            MatrixState state,
            Object identity,
            long revision,
            int passKey,
            RasterState rasterState) {
        this.rasterState = rasterState;
        this.projection = Objects.requireNonNull(projection, "projection");
        if (height <= 0
                || scissor == null
                || scissor.length != 4
                || scissor[2] < 0
                || scissor[3] < 0) throw new IllegalArgumentException("无效target/scissor");
        drawFramebuffer = framebuffer;
        sourceFramebufferHeight = height;
        this.depthTest = depthTest;
        this.depthFunction = depthFunction;
        this.depthWrite = depthWrite;
        this.scissorEnabled = scissorEnabled;
        this.scissor = scissor.clone();
        this.state = Objects.requireNonNull(state, "state");
        contextIdentity = Objects.requireNonNull(identity, "contextIdentity");
        this.revision = revision;
        this.passKey = passKey;
    }

    /** 同包派生只复用已冻结的私有载荷；公开数组入口与 getter 仍防御复制。 */
    FontRenderContext(FontRenderContext source, MatrixState state, RasterState rasterState) {
        projection = source.projection;
        drawFramebuffer = source.drawFramebuffer;
        sourceFramebufferHeight = source.sourceFramebufferHeight;
        depthTest = source.depthTest;
        depthFunction = source.depthFunction;
        depthWrite = source.depthWrite;
        scissorEnabled = source.scissorEnabled;
        scissor = source.scissor;
        this.state = Objects.requireNonNull(state, "state");
        contextIdentity = source.contextIdentity;
        revision = source.revision;
        passKey = source.passKey;
        this.rasterState = rasterState;
    }

    /** 延期提交的光栅状态值；构造消费显式输入，数组通过防御拷贝读取。
     * FBO/viewport/depth/scissor仍由外层context保存。延期consumer应用本载荷后须恢复回放入口状态。
     */
    public static final class RasterState {
        public final boolean blendEnabled, stencilEnabled, cullEnabled, polygonOffsetFill;
        public final int blendSrcRgb,
                blendDstRgb,
                blendSrcAlpha,
                blendDstAlpha,
                blendEquationRgb,
                blendEquationAlpha,
                cullFace,
                frontFace;
        public final float polygonOffsetFactor, polygonOffsetUnits;
        public final StencilFace frontStencil, backStencil;
        private final float[] blendColor, depthRange;
        private final boolean[] colorMask;

        public RasterState(
                boolean blendEnabled,
                int srcRgb,
                int dstRgb,
                int srcAlpha,
                int dstAlpha,
                int equationRgb,
                int equationAlpha,
                float[] blendColor,
                boolean stencilEnabled,
                StencilFace frontStencil,
                StencilFace backStencil,
                boolean[] colorMask,
                float[] depthRange,
                boolean cullEnabled,
                int cullFace,
                int frontFace,
                boolean polygonOffsetFill,
                float factor,
                float units) {
            this.blendEnabled = blendEnabled;
            blendSrcRgb = srcRgb;
            blendDstRgb = dstRgb;
            blendSrcAlpha = srcAlpha;
            blendDstAlpha = dstAlpha;
            blendEquationRgb = equationRgb;
            blendEquationAlpha = equationAlpha;
            this.blendColor = vector(blendColor, 4);
            this.depthRange = vector(depthRange, 2);
            if (colorMask == null || colorMask.length != 4)
                throw new IllegalArgumentException("无效colorMask");
            this.colorMask = colorMask.clone();
            this.stencilEnabled = stencilEnabled;
            this.frontStencil = Objects.requireNonNull(frontStencil, "frontStencil");
            this.backStencil = Objects.requireNonNull(backStencil, "backStencil");
            this.cullEnabled = cullEnabled;
            this.cullFace = cullFace;
            this.frontFace = frontFace;
            if (!Float.isFinite(factor) || !Float.isFinite(units))
                throw new IllegalArgumentException("无效polygon offset");
            this.polygonOffsetFill = polygonOffsetFill;
            polygonOffsetFactor = factor;
            polygonOffsetUnits = units;
        }

        /** 标准字体仅覆盖混合规则，其余 caller 光栅载荷保持不可变共享。 */
        RasterState(
                RasterState source,
                int srcRgb,
                int dstRgb,
                int srcAlpha,
                int dstAlpha,
                int equationRgb,
                int equationAlpha) {
            blendEnabled = true;
            blendSrcRgb = srcRgb;
            blendDstRgb = dstRgb;
            blendSrcAlpha = srcAlpha;
            blendDstAlpha = dstAlpha;
            blendEquationRgb = equationRgb;
            blendEquationAlpha = equationAlpha;
            blendColor = source.blendColor;
            depthRange = source.depthRange;
            colorMask = source.colorMask;
            stencilEnabled = source.stencilEnabled;
            frontStencil = source.frontStencil;
            backStencil = source.backStencil;
            cullEnabled = source.cullEnabled;
            cullFace = source.cullFace;
            frontFace = source.frontFace;
            polygonOffsetFill = source.polygonOffsetFill;
            polygonOffsetFactor = source.polygonOffsetFactor;
            polygonOffsetUnits = source.polygonOffsetUnits;
        }

        private static float[] vector(float[] value, int size) {
            if (value == null || value.length != size) throw new IllegalArgumentException("无效光栅向量");
            for (float f : value)
                if (!Float.isFinite(f)) throw new IllegalArgumentException("非有限光栅状态");
            return value.clone();
        }

        private boolean sameState(RasterState other) {
            return this == other || other != null
                    && blendEnabled == other.blendEnabled
                    && blendSrcRgb == other.blendSrcRgb && blendDstRgb == other.blendDstRgb
                    && blendSrcAlpha == other.blendSrcAlpha && blendDstAlpha == other.blendDstAlpha
                    && blendEquationRgb == other.blendEquationRgb && blendEquationAlpha == other.blendEquationAlpha
                    && stencilEnabled == other.stencilEnabled
                    && cullEnabled == other.cullEnabled && cullFace == other.cullFace && frontFace == other.frontFace
                    && polygonOffsetFill == other.polygonOffsetFill
                    && sameFloat(polygonOffsetFactor, other.polygonOffsetFactor)
                    && sameFloat(polygonOffsetUnits, other.polygonOffsetUnits)
                    && frontStencil.sameState(other.frontStencil) && backStencil.sameState(other.backStencil)
                    && sameFloats(blendColor, other.blendColor) && sameFloats(depthRange, other.depthRange)
                    && java.util.Arrays.equals(colorMask, other.colorMask);
        }

        public float[] blendColor() {
            return blendColor.clone();
        }

        public float[] depthRange() {
            return depthRange.clone();
        }

        public boolean[] colorMask() {
            return colorMask.clone();
        }
    }

    public static final class StencilFace {
        public final int function, reference, valueMask, writeMask, fail, depthFail, depthPass;

        public StencilFace(
                int function,
                int reference,
                int valueMask,
                int writeMask,
                int fail,
                int depthFail,
                int depthPass) {
            this.function = function;
            this.reference = reference;
            this.valueMask = valueMask;
            this.writeMask = writeMask;
            this.fail = fail;
            this.depthFail = depthFail;
            this.depthPass = depthPass;
        }

        private boolean sameState(StencilFace other) {
            return this == other || other != null
                    && function == other.function && reference == other.reference
                    && valueMask == other.valueMask && writeMask == other.writeMask
                    && fail == other.fail && depthFail == other.depthFail && depthPass == other.depthPass;
        }
    }

    /** 同一绘制来源的完整值比较；直接读取不可变快照，保留 P/MV 及浮点原始位身份。 */
    public boolean sameDrawState(FontRenderContext other) {
        return this == other || other != null
                && contextIdentity == other.contextIdentity && revision == other.revision
                && passKey == other.passKey && drawFramebuffer == other.drawFramebuffer
                && sourceFramebufferHeight == other.sourceFramebufferHeight
                && depthTest == other.depthTest && depthFunction == other.depthFunction
                && depthWrite == other.depthWrite && scissorEnabled == other.scissorEnabled
                && java.util.Arrays.equals(scissor, other.scissor)
                && projection.sameState(other.projection) && state.sameState(other.state)
                && (rasterState == other.rasterState
                        || rasterState != null && rasterState.sameState(other.rasterState));
    }

    static boolean sameFloat(float a, float b) {
        return Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b);
    }

    static boolean sameFloats(float[] a, float[] b) {
        if (a == b) return true;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (!sameFloat(a[i], b[i])) return false;
        return true;
    }

    public int[] scissor() {
        return scissor.clone();
    }

    public RasterSampling samplingAt(double x, double y, double logicalEm, RasterPolicy policy) {
        return projection.samplingAt(x, y, logicalEm, policy, revision);
    }

    /** effective固定管线与可选宿主状态；公开构造持有向量和矩阵的独立副本。 */
    public static final class MatrixState {
        private static final float[] WHITE = {1, 1, 1, 1};
        private final float[] p, mv, color, normal, lighting;
        public final boolean alphaTest, lightmapActive;
        public final int alphaFunction, lightmapTexture, packedLight, blockEntityId;
        public final float alphaReference, lightmapU, lightmapV;

        public MatrixState(
                float[] p,
                float[] mv,
                float[] color,
                boolean alphaTest,
                int alphaFunction,
                float alphaReference,
                boolean lightmapActive,
                int lightmapTexture,
                float lightmapU,
                float lightmapV,
                int packedLight,
                float[] normal,
                float[] lighting,
                int blockEntityId) {
            this.p = copy(p, 16);
            this.mv = copy(mv, 16);
            this.color = copy(color, 4);
            this.normal = copy(normal, 3);
            this.lighting = copy(lighting, 3);
            if (!Float.isFinite(alphaReference)
                    || !Float.isFinite(lightmapU)
                    || !Float.isFinite(lightmapV)
                    || lightmapTexture < 0) throw new IllegalArgumentException("无效alpha/lightmap");
            this.alphaTest = alphaTest;
            this.alphaFunction = alphaFunction;
            this.alphaReference = alphaReference;
            this.lightmapActive = lightmapActive;
            this.lightmapTexture = lightmapTexture;
            this.lightmapU = lightmapU;
            this.lightmapV = lightmapV;
            this.packedLight = packedLight;
            this.blockEntityId = blockEntityId;
        }

        /** 布局已保存显式字体颜色；currentColor归白，其余冻结矩阵与光照共享。 */
        MatrixState(MatrixState source) {
            p = source.p;
            mv = source.mv;
            color = WHITE;
            normal = source.normal;
            lighting = source.lighting;
            alphaTest = source.alphaTest;
            alphaFunction = source.alphaFunction;
            alphaReference = source.alphaReference;
            lightmapActive = source.lightmapActive;
            lightmapTexture = source.lightmapTexture;
            lightmapU = source.lightmapU;
            lightmapV = source.lightmapV;
            packedLight = source.packedLight;
            blockEntityId = source.blockEntityId;
        }

        private static float[] copy(float[] v, int size) {
            if (v == null || v.length != size) throw new IllegalArgumentException("无效向量长度");
            float[] c = v.clone();
            for (float x : c) if (!Float.isFinite(x)) throw new IllegalArgumentException("非有限状态");
            return c;
        }

        private boolean sameState(MatrixState other) {
            return this == other || other != null
                    && alphaTest == other.alphaTest && alphaFunction == other.alphaFunction
                    && sameFloat(alphaReference, other.alphaReference)
                    && lightmapActive == other.lightmapActive && lightmapTexture == other.lightmapTexture
                    && sameFloat(lightmapU, other.lightmapU) && sameFloat(lightmapV, other.lightmapV)
                    && packedLight == other.packedLight && blockEntityId == other.blockEntityId
                    && sameFloats(p, other.p) && sameFloats(mv, other.mv) && sameFloats(color, other.color)
                    && sameFloats(normal, other.normal) && sameFloats(lighting, other.lighting);
        }

        public float[] projection() {
            return p.clone();
        }

        public float[] modelView() {
            return mv.clone();
        }

        public float[] color() {
            return color.clone();
        }

        public float[] normal() {
            return normal.clone();
        }

        public float[] lighting() {
            return lighting.clone();
        }

        /** 以own scene/core显式P/MV构造默认UI状态，lightmap与alpha test保持关闭。 */
        public static MatrixState ui(float[] p, float[] mv) {
            return new MatrixState(
                    p,
                    mv,
                    new float[] {1, 1, 1, 1},
                    false,
                    0x0204,
                    0,
                    false,
                    0,
                    0,
                    0,
                    0,
                    new float[] {0, 0, 1},
                    new float[] {1, 1, 1},
                    -1);
        }
    }
}
