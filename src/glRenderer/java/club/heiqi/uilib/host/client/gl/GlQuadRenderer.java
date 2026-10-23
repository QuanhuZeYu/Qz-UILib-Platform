package club.heiqi.uilib.host.client.gl;

import static club.heiqi.uilib.host.client.gl.GlTokens.*;

import club.heiqi.uilib.host.client.font.context.FontRenderContext;
import club.heiqi.uilib.ui.render.batch.OrderedQuadBatch;
import club.heiqi.uilib.ui.render.batch.OrderedQuadBatch.Draw;
import club.heiqi.uilib.ui.render.batch.OrderedQuadBatch.Quad;
import club.heiqi.uilib.ui.render.batch.OrderedQuadBatch.State;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * GL3.2/GLSL150 的正式有序载荷消费者；所有操作及 close 必须在所属上下文线程执行。 宿主在变换、深度、pass 或外部委托屏障处结束 Scope；不捕获 GL 矩阵，不管理
 * scene。 资源提供者只解析已准备并存活的 resource/generation，不得在消费中修改 GL 状态。
 */
public final class GlQuadRenderer implements AutoCloseable {
    /** ARGB 顶点颜色为直通颜色；输出为 premult RGBA。 */
    public static final int SOLID = 0;

    /** 按资源声明的通道采样 coverage，RGB 使用顶点着色。 */
    public static final int MONOCHROME_COVERAGE = 1;

    /** 采样已经 premult 的彩色 RGBA，保留其 RGB，只乘顶点整体 alpha。 */
    public static final int COLOR_PREMULT = 2;

    public interface ResourceProvider {
        Texture resolve(long resource, long generation);
    }

    public enum CoverageChannel {
        ALPHA,
        RED
    }

    /** 纹理由调用者拥有；backend 不上传、换代或删除提供者的资源。 */
    public static final class Texture {
        public final int target, id;
        public final CoverageChannel coverageChannel;

        /** 既有外部 RGBA 资源默认从 alpha 采样，不能因 mono 材质改读 red。 */
        public Texture(int target, int id) {
            this(target, id, CoverageChannel.ALPHA);
        }

        public Texture(int target, int id, CoverageChannel coverageChannel) {
            if ((target != GL_TEXTURE_2D && target != GL_TEXTURE_2D_ARRAY) || id <= 0)
                throw new IllegalArgumentException("无效纹理绑定");
            this.target = target;
            this.id = id;
            this.coverageChannel = Objects.requireNonNull(coverageChannel, "coverageChannel");
        }
    }

    public static final class Counters {
        public final long draws, vertexUploads, vertexBytes, textureBindings, indexUploads;

        private Counters(GlQuadRenderer r) {
            draws = r.draws;
            vertexUploads = r.uploads;
            vertexBytes = r.bytes;
            textureBindings = r.bindings;
            indexUploads = r.indexUploads;
        }
    }

    private final GlApi gl;
    private final GlProgramProvider programProvider;
    private static final int STRIDE = 36;
    private final int capacity;
    private final ResourceProvider resources;
    private ByteBuffer staging;
    private int vao, vbo, ebo;
    private static final int DUAL_2D = 2;
    private final int[] programs = new int[3];
    private final ProgramUniforms[] uniforms = new ProgramUniforms[3];

    /** 位置只属于本 renderer 创建且不再重链的 program；动态宿主值仍在每个 Scope 应用。 */
    private static final class ProgramUniforms {
        final int mvp, material, image, image2, coverageRed;
        final int hostLighting, hostAlpha, outputStraight, alphaEnabled, alphaFunction, alphaReference;
        final int lightmapEnabled, lightmapImage, lightmapUv;

        ProgramUniforms(GlApi gl, int program) {
            mvp = gl.glGetUniformLocation(program, "mvp");
            material = gl.glGetUniformLocation(program, "material");
            image = gl.glGetUniformLocation(program, "image");
            image2 = gl.glGetUniformLocation(program, "image2");
            coverageRed = gl.glGetUniformLocation(program, "coverageRed");
            hostLighting = gl.glGetUniformLocation(program, "hostLighting");
            hostAlpha = gl.glGetUniformLocation(program, "hostAlpha");
            outputStraight = gl.glGetUniformLocation(program, "outputStraight");
            alphaEnabled = gl.glGetUniformLocation(program, "alphaEnabled");
            alphaFunction = gl.glGetUniformLocation(program, "alphaFunction");
            alphaReference = gl.glGetUniformLocation(program, "alphaReference");
            lightmapEnabled = gl.glGetUniformLocation(program, "lightmapEnabled");
            lightmapImage = gl.glGetUniformLocation(program, "lightmapImage");
            lightmapUv = gl.glGetUniformLocation(program, "lightmapUv");
        }

        void world(GlApi gl, FontRenderContext context) {
            gl.glUniform3fv(hostLighting, context.state.lighting());
            // resolved 文字 RGB 已由 adapter 固化；彩色字形同样只受整体 alpha 与 lighting 影响。
            gl.glUniform1f(hostAlpha, context.state.color()[3]);
            gl.glUniform1i(outputStraight, context.rasterState != null ? 1 : 0);
            gl.glUniform1i(alphaEnabled, context.state.alphaTest ? 1 : 0);
            gl.glUniform1i(alphaFunction, context.state.alphaFunction);
            gl.glUniform1f(alphaReference, context.state.alphaReference);
            gl.glUniform1i(lightmapEnabled, context.state.lightmapActive ? 1 : 0);
            gl.glUniform1i(lightmapImage, 1);
            gl.glUniform2f(lightmapUv, context.state.lightmapU, context.state.lightmapV);
        }

        void ui(GlApi gl) {
            gl.glUniform3f(hostLighting, 1, 1, 1);
            gl.glUniform1f(hostAlpha, 1);
            gl.glUniform1i(outputStraight, 0);
            gl.glUniform1i(alphaEnabled, 0);
            gl.glUniform1i(lightmapEnabled, 0);
            gl.glUniform1i(lightmapImage, 1);
        }
    }

    private Scope active;
    private boolean closed;
    private long draws, uploads, bytes, bindings, indexUploads;
    private static final String VERTEX =
            "#version 150\n"
                    + "in vec2 position; in vec2 uv; in float layer; in vec4 color; uniform mat4 mvp;"
                    + " out vec2 texUv; flat out float texLayer; out vec4 tint; void"
                    + " main(){gl_Position=mvp*vec4(position,0,1);texUv=uv;texLayer=layer;tint=color;}";

    private static String fragment(int variant) {
        boolean array = variant == 1, dual = variant == DUAL_2D;
        return "#version 150\n"
                + "uniform "
                + (array ? "sampler2DArray" : "sampler2D")
                + " image; "
                + (dual ? "uniform sampler2D image2; " : "uniform int material,coverageRed; ")
                + "uniform vec3 hostLighting; uniform float"
                + " hostAlpha; uniform int"
                + " alphaEnabled,alphaFunction,lightmapEnabled,outputStraight; uniform float"
                + " alphaReference; uniform sampler2D lightmapImage; uniform vec2 lightmapUv; in"
                + " vec2 texUv; flat in float texLayer; in vec4 tint; out vec4 result; bool"
                + " alphaPass(float a){if(alphaFunction==512)return"
                + " false;if(alphaFunction==513)return"
                + " a<alphaReference;if(alphaFunction==514)return"
                + " a==alphaReference;if(alphaFunction==515)return"
                + " a<=alphaReference;if(alphaFunction==516)return"
                + " a>alphaReference;if(alphaFunction==517)return"
                + " a!=alphaReference;if(alphaFunction==518)return a>=alphaReference;return true;}"
                + " void main(){"
                + (dual ? "int selector=int(texLayer);int material=1+(selector/2)%2;int coverageRed=selector%2;" : "")
                + "if(material==0){result=vec4(tint.rgb*tint.a,tint.a);}else{vec4 s="
                + (dual ? "(selector<4?texture(image,texUv):texture(image2,texUv))"
                        : "texture(image," + (array ? "vec3(texUv,texLayer)" : "texUv") + ")")
                + ";if(material==1){float"
                + " a=(coverageRed!=0?s.r:s.a)*tint.a;result=vec4(tint.rgb*a,a);}else{result=s*tint.a;}}"
                + "result*=hostAlpha;result.rgb*=hostLighting;if(lightmapEnabled!=0)result.rgb*=texture(lightmapImage,lightmapUv).rgb;if(alphaEnabled!=0&&!alphaPass(result.a))discard;if(outputStraight!=0){if(material==0||material==1){result.rgb=tint.rgb*hostLighting;if(lightmapEnabled!=0)result.rgb*=texture(lightmapImage,lightmapUv).rgb;}else"
                + " result.rgb=result.a>0.0?result.rgb/result.a:vec3(0.0);}}";
    }

    public GlQuadRenderer(
            GlApi gl, int capacity, ResourceProvider resources, GlProgramProvider provider) {
        this.gl = Objects.requireNonNull(gl, "gl");
        this.programProvider = Objects.requireNonNull(provider, "programs");
        if (!gl.capabilities().openGL32)
            throw new IllegalStateException("renderer要求实际OpenGL3.2/GLSL150");
        if (capacity <= 0 || capacity > Integer.MAX_VALUE / (4 * STRIDE))
            throw new IllegalArgumentException("无效 staging 容量");
        this.capacity = capacity;
        this.resources = Objects.requireNonNull(resources, "resources");
        int previousVao = gl.glGetInteger(GL_VERTEX_ARRAY_BINDING),
                previousVbo = gl.glGetInteger(GL_ARRAY_BUFFER_BINDING);
        try {
            staging =
                    ByteBuffer.allocateDirect(capacity * 4 * STRIDE).order(ByteOrder.nativeOrder());
            for (int i = 0; i < programs.length; i++) {
                programs[i] = program(fragment(i));
                uniforms[i] = new ProgramUniforms(gl, programs[i]);
            }
            vao = gl.glGenVertexArrays();
            vbo = gl.glGenBuffers();
            ebo = gl.glGenBuffers();
            gl.glBindVertexArray(vao);
            gl.glBindBuffer(GL_ARRAY_BUFFER, vbo);
            gl.glBufferData(GL_ARRAY_BUFFER, (long) capacity * 4 * STRIDE, GL_STREAM_DRAW);
            gl.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            gl.glBufferData(
                    GL_ELEMENT_ARRAY_BUFFER,
                    OrderedQuadBatch.quadIndices(capacity),
                    GL_STATIC_DRAW);
            indexUploads++;
            int[] sizes = {2, 2, 1, 4};
            int[] offsets = {0, 8, 16, 20};
            for (int i = 0; i < 4; i++) {
                gl.glEnableVertexAttribArray(i);
                gl.glVertexAttribPointer(i, sizes[i], GL_FLOAT, false, STRIDE, offsets[i]);
            }
        } catch (RuntimeException | Error failure) {
            release();
            closed = true;
            throw failure;
        } finally {
            gl.glBindVertexArray(previousVao);
            gl.glBindBuffer(GL_ARRAY_BUFFER, previousVbo);
        }
    }

    private int shader(int type, String source) {
        int id = gl.glCreateShader(type);
        try {
            gl.glShaderSource(id, source);
            gl.glCompileShader(id);
            if (gl.glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE)
                throw new IllegalStateException(gl.glGetShaderInfoLog(id));
            return id;
        } catch (RuntimeException | Error e) {
            gl.glDeleteShader(id);
            throw e;
        }
    }

    private int program(String fragment) {
        int vertex = 0, pixel = 0, id = 0;
        try {
            vertex = shader(GL_VERTEX_SHADER, VERTEX);
            pixel = shader(GL_FRAGMENT_SHADER, fragment);
            id = gl.glCreateProgram();
            gl.glAttachShader(id, vertex);
            gl.glAttachShader(id, pixel);
            String[] names = {"position", "uv", "layer", "color"};
            for (int i = 0; i < names.length; i++) gl.glBindAttribLocation(id, i, names[i]);
            gl.glBindFragDataLocation(id, 0, "result");
            gl.glLinkProgram(id);
            if (gl.glGetProgrami(id, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(gl.glGetProgramInfoLog(id));
            return id;
        } catch (RuntimeException | Error e) {
            if (id != 0) gl.glDeleteProgram(id);
            throw e;
        } finally {
            if (vertex != 0) gl.glDeleteShader(vertex);
            if (pixel != 0) gl.glDeleteShader(pixel);
        }
    }

    public Counters counters() {
        return new Counters(this);
    }

    /**
     * clip 是宿主转换完成的 framebuffer 像素，左上原点；MVP 为列主序快照。 framebuffer、viewport、depth function/write mask
     * 与 pass 由宿主固定，本范围仅指定 depth enable。
     */
    public Scope begin(float[] mvp, int framebufferHeight, boolean depthEnabled) {
        return begin(mvp, framebufferHeight, depthEnabled, null);
    }

    private Scope begin(float[] mvp, int framebufferHeight, boolean depthEnabled,
            GlFontWorldState worldEntry) {
        if (closed || active != null) throw new IllegalStateException("backend 已关闭或范围重入");
        if (mvp == null || mvp.length != 16 || framebufferHeight < 0)
            throw new IllegalArgumentException("无效宿主范围");
        for (float f : mvp) if (!Float.isFinite(f)) throw new IllegalArgumentException("无效 MVP");
        Scope scope = new Scope(mvp.clone(), framebufferHeight, worldEntry);
        active = scope;
        try {
            scope.selectTextureUnit();
            gl.glBindVertexArray(vao);
            gl.glBindBuffer(GL_ARRAY_BUFFER, vbo);
            gl.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            gl.glEnable(GL_BLEND);
            gl.glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
            gl.glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            enable(GL_CULL_FACE, false);
            enable(GL_DEPTH_TEST, depthEnabled);
            if (scope.compatibility) enable(GL_ALPHA_TEST, false);
            return scope;
        } catch (RuntimeException | Error failure) {
            scope.close();
            throw failure;
        }
    }

    /** 由宿主装配，跨短 pass 复用；只在 backend 所属有效 GL 上下文内创建和释放。 */
    private GlVertexProducer programProducer;

    private int fontEbo;

    // 原版字体strip TL/BL/TR/BR在caller XY是负绕序；UI通用quad保持其既有正绕序。
    static int[] fontIndices(int capacity) {
        int[] indices = OrderedQuadBatch.quadIndices(capacity);
        for (int i = 0; i < indices.length; i += 6) {
            int swap = indices[i + 1];
            indices[i + 1] = indices[i + 2];
            indices[i + 2] = swap;
            swap = indices[i + 4];
            indices[i + 4] = indices[i + 5];
            indices[i + 5] = swap;
        }
        return indices;
    }

    private void bindFontIndices() {
        if (fontEbo == 0) {
            fontEbo = gl.glGenBuffers();
            gl.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, fontEbo);
            gl.glBufferData(GL_ELEMENT_ARRAY_BUFFER, fontIndices(capacity), GL_STATIC_DRAW);
            indexUploads++;
        } else gl.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, fontEbo);
    }

    public Scope begin(FontRenderContext context) {
        Objects.requireNonNull(context, "context");

        if (closed || active != null) throw new IllegalStateException("backend已关闭或范围重入");
        GlFontWorldState world = new GlFontWorldState(gl, context);
        Scope scope = null;
        try {
            if (programProvider.preview(context)) {
                scope = new Scope(context.projection.mvp(), context.sourceFramebufferHeight, world);
                active = scope;
                scope.world = world;
                scope.iris = programProvider.open(context);
                if (scope.iris == null)
                    throw new IllegalStateException("Iris prepare/render activepass不一致");
                scope.selectTextureUnit();
                if (programProducer == null)
                    programProducer =
                            Objects.requireNonNull(
                                    programProvider.createVertexProducer(gl, capacity),
                                    "vertex producer");
                programProducer.configure(scope.iris);
                world.applyIris();
                // Pass.use拥有FBO/blend/alpha overrides；不能再用普通world.apply盖掉pack定义。
                return scope;
            }
            if (context.rasterState == null) {
                scope =
                        begin(
                                context.projection.mvp(),
                                context.sourceFramebufferHeight,
                                context.depthTest,
                                world);
                scope.world = world;
            } else {
                // typed world 直接应用捕获状态，避免先建立 UI blend/cull 与通用 EBO 后再覆盖。
                scope = new Scope(context.projection.mvp(), context.sourceFramebufferHeight, world);
                active = scope;
                scope.world = world;
                scope.selectTextureUnit();
                gl.glBindVertexArray(vao);
                gl.glBindBuffer(GL_ARRAY_BUFFER, vbo);
                if (scope.depth != context.depthTest) enable(GL_DEPTH_TEST, context.depthTest);
                if (scope.compatibility) enable(GL_ALPHA_TEST, false);
            }
            world.apply();
            bindFontIndices();
            return scope;
        } catch (RuntimeException | Error failure) {
            try {
                if (scope != null) scope.close();
                else world.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public final class Scope implements AutoCloseable {
        private final float[] mvp;
        private GlFontWorldState world;
        private GlProgramScope iris;
        private final int height,
                program = gl.glGetInteger(GL_CURRENT_PROGRAM),
                oldVao = gl.glGetInteger(GL_VERTEX_ARRAY_BINDING),
                oldVbo = gl.glGetInteger(GL_ARRAY_BUFFER_BINDING);
        private final int unit;
        private final int eqRgb, eqAlpha, srcRgb, dstRgb, srcAlpha, dstAlpha;
        private final boolean blend, cull, restoreBlendCull;
        private final boolean scissor, depth;
        private final int[] box;
        private final int texture2d, textureArray, sampler;
        private final boolean compatibility =
                (gl.glGetInteger(GL_CONTEXT_PROFILE_MASK) & GL_CONTEXT_COMPATIBILITY_PROFILE_BIT)
                        != 0;
        private final boolean alphaTest = compatibility && gl.glIsEnabled(GL_ALPHA_TEST);
        private final boolean samplerObjects = gl.capabilities().samplerObjects;
        private boolean done;
        private int currentProgram = -1, bound2d, boundArray;
        private boolean secondCaptured;
        private int secondTexture, secondSampler, boundSecond;

        private Scope(float[] mvp, int height, GlFontWorldState worldEntry) {
            this.mvp = mvp;
            this.height = height;
            // preview 只读；与 world 共用同一 actual 进入态，不能用历史 context 代替。
            unit = worldEntry == null ? gl.glGetInteger(GL_ACTIVE_TEXTURE) : worldEntry.unit;
            scissor = worldEntry == null ? gl.glIsEnabled(GL_SCISSOR_TEST) : worldEntry.scissor;
            depth = worldEntry == null ? gl.glIsEnabled(GL_DEPTH_TEST) : worldEntry.depth;
            box = worldEntry == null ? new int[4] : worldEntry.box;
            FontRenderContext.RasterState worldEntryRaster = worldEntry == null ? null : worldEntry.entryRaster;
            restoreBlendCull = worldEntry == null;
            eqRgb = restoreBlendCull ? gl.glGetInteger(GL_BLEND_EQUATION_RGB) : worldEntryRaster.blendEquationRgb;
            eqAlpha = restoreBlendCull ? gl.glGetInteger(GL_BLEND_EQUATION_ALPHA) : worldEntryRaster.blendEquationAlpha;
            srcRgb = restoreBlendCull ? gl.glGetInteger(GL_BLEND_SRC_RGB) : worldEntryRaster.blendSrcRgb;
            dstRgb = restoreBlendCull ? gl.glGetInteger(GL_BLEND_DST_RGB) : worldEntryRaster.blendDstRgb;
            srcAlpha = restoreBlendCull ? gl.glGetInteger(GL_BLEND_SRC_ALPHA) : worldEntryRaster.blendSrcAlpha;
            dstAlpha = restoreBlendCull ? gl.glGetInteger(GL_BLEND_DST_ALPHA) : worldEntryRaster.blendDstAlpha;
            blend = restoreBlendCull ? gl.glIsEnabled(GL_BLEND) : worldEntryRaster.blendEnabled;
            cull = restoreBlendCull ? gl.glIsEnabled(GL_CULL_FACE) : worldEntryRaster.cullEnabled;
            if (worldEntry == null) gl.glGetIntegerv(GL_SCISSOR_BOX, box);
            try {
                if (unit != GL_TEXTURE0) gl.glActiveTexture(GL_TEXTURE0);
                texture2d = gl.glGetInteger(GL_TEXTURE_BINDING_2D);
                textureArray = gl.glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
                bound2d = texture2d;
                boundArray = textureArray;
                // 外部sampler会覆盖图集过滤，范围内使用资源纹理参数，并还原unit0的sampler。
                sampler = samplerObjects ? gl.glGetInteger(GL_SAMPLER_BINDING) : 0;
            } finally {
                if (unit != GL_TEXTURE0) gl.glActiveTexture(unit);
            }
        }

        private void selectTextureUnit() {
            gl.glActiveTexture(GL_TEXTURE0);
            if (samplerObjects) gl.glBindSampler(0, 0);
        }

        /** 一段恰好一次顶点上传；双2D只按原索引连续提交，其余逐 Draw 消费，不排序。 */
        public void consumeBatch(OrderedQuadBatch batch) {
            if (done || active != this) throw new IllegalStateException("范围已结束");
            Objects.requireNonNull(batch, "batch");
            if (batch.quads().size() > capacity)
                throw new IllegalArgumentException("提交段超过 backend 容量");
            if (batch.quads().isEmpty()) return;
            if (iris != null) {
                bytes += programProducer.consume(batch, iris, world.context, resources);
                uploads++;
                draws += batch.draws().size();
                return;
            }
            if (consumeDual2d(batch)) return;
            staging.clear();
            for (Quad q : batch.quads()) quad(q, q.layer);
            staging.flip();
            gl.glBufferSubData(GL_ARRAY_BUFFER, 0, staging);
            uploads++;
            bytes += staging.remaining();
            for (Draw draw : batch.draws()) {
                int material = draw.state.material;
                if (material < SOLID || material > COLOR_PREMULT)
                    throw new IllegalArgumentException("未知材质: " + material);
                Texture t =
                        material == SOLID
                                ? null
                                : Objects.requireNonNull(
                                        resources.resolve(
                                                draw.state.resource, draw.state.generation),
                                        "texture");
                int variant = t != null && t.target == GL_TEXTURE_2D_ARRAY ? 1 : 0;
                ProgramUniforms binding = uniforms[variant];
                if (currentProgram != variant) {
                    gl.glUseProgram(programs[variant]);
                    gl.glUniformMatrix4fv(binding.mvp, false, mvp);
                    gl.glUniform1i(binding.image, 0);
                    if (world == null) binding.ui(gl);
                    else binding.world(gl, world.context);
                    currentProgram = variant;
                }
                gl.glUniform1i(binding.material, material);
                gl.glUniform1i(binding.coverageRed,
                        t != null && t.coverageChannel == CoverageChannel.RED ? 1 : 0);
                if (t != null && (variant == 0 ? bound2d : boundArray) != t.id) {
                    gl.glBindTexture(t.target, t.id);
                    if (variant == 0) bound2d = t.id;
                    else boundArray = t.id;
                    bindings++;
                }
                applyClip(draw.state.clip);
                gl.glDrawElements(
                        GL_TRIANGLES,
                        draw.quadCount * 6,
                        GL_UNSIGNED_INT,
                        (long) draw.firstQuad * 6 * 4);
                draws++;
            }
        }

        /** 只消费同一封闭 typed world 批次；按原 Draw/quad 顺序打包，不排序或延长资源引用。 */
        private boolean consumeDual2d(OrderedQuadBatch batch) {
            if (world == null || world.context.rasterState == null || batch.draws().size() < 2) return false;
            State first = batch.draws().get(0).state;
            long resource0 = first.resource, generation0 = first.generation, resource1 = 0, generation1 = 0;
            int count = 0;
            for (Draw draw : batch.draws()) {
                State s = draw.state;
                if ((s.material != MONOCHROME_COVERAGE && s.material != COLOR_PREMULT) || s.resource <= 0
                        || !sameGeometry(first, s) || draw.firstQuad != count || draw.quadCount <= 0
                        || draw.quadCount > batch.quads().size() - count) return false;
                count += draw.quadCount;
                if (s.resource == resource0 && s.generation == generation0) continue;
                if (resource1 == 0) { resource1 = s.resource; generation1 = s.generation; }
                else if (s.resource != resource1 || s.generation != generation1) return false;
            }
            if (resource1 == 0 || count != batch.quads().size()) return false;
            // provider 只解析本 Frame 已 pin 的资源；解析失败原样上抛，不吞错重试或跨 Frame 缓存。
            Texture firstTexture = Objects.requireNonNull(resources.resolve(resource0, generation0), "texture");
            Texture second = Objects.requireNonNull(resources.resolve(resource1, generation1), "texture");
            if (firstTexture.target != GL_TEXTURE_2D || second.target != GL_TEXTURE_2D || firstTexture.id == second.id)
                return false;
            staging.clear();
            for (Draw draw : batch.draws()) {
                boolean firstSlot = draw.state.resource == resource0 && draw.state.generation == generation0;
                Texture texture = firstSlot ? firstTexture : second;
                // 2D 不读数组层；复用原 layer 槽的三个局部位：texture slot/material/coverage channel。
                int selector = (firstSlot ? 0 : 4) + (draw.state.material - 1) * 2
                        + (texture.coverageChannel == CoverageChannel.RED ? 1 : 0);
                for (int i = draw.firstQuad; i < draw.firstQuad + draw.quadCount; i++)
                    quad(batch.quads().get(i), selector);
            }
            staging.flip();
            gl.glBufferSubData(GL_ARRAY_BUFFER, 0, staging);
            uploads++;
            bytes += staging.remaining();
            bindSecondTexture(second.id);
            ProgramUniforms binding = uniforms[DUAL_2D];
            if (currentProgram != DUAL_2D) {
                gl.glUseProgram(programs[DUAL_2D]);
                gl.glUniformMatrix4fv(binding.mvp, false, mvp);
                gl.glUniform1i(binding.image, 0);
                gl.glUniform1i(binding.image2, 2);
                binding.world(gl, world.context);
                currentProgram = DUAL_2D;
            }
            if (bound2d != firstTexture.id) {
                bound2d = -1;
                gl.glBindTexture(GL_TEXTURE_2D, firstTexture.id);
                bound2d = firstTexture.id;
                bindings++;
            }
            applyClip(first.clip);
            gl.glDrawElements(GL_TRIANGLES, count * 6, GL_UNSIGNED_INT, 0);
            draws++;
            return true;
        }

        private boolean sameGeometry(State a, State b) {
            LayoutBox x = a.clip, y = b.clip;
            return a.transform.equals(b.transform) && a.shapeClip.equals(b.shapeClip)
                    && (x == y || x != null && y != null && x.x == y.x && x.y == y.y
                            && x.width == y.width && x.height == y.height);
        }

        private void bindSecondTexture(int texture) {
            try {
                gl.glActiveTexture(GL_TEXTURE0 + 2);
                if (!secondCaptured) {
                    int binding = gl.glGetInteger(GL_TEXTURE_BINDING_2D);
                    int samplerBinding = samplerObjects ? gl.glGetInteger(GL_SAMPLER_BINDING) : 0;
                    secondTexture = boundSecond = binding;
                    secondSampler = samplerBinding;
                    secondCaptured = true;
                }
                if (samplerObjects) gl.glBindSampler(2, 0);
                if (boundSecond != texture) {
                    boundSecond = -1;
                    gl.glBindTexture(GL_TEXTURE_2D, texture);
                    boundSecond = texture;
                    bindings++;
                }
            } finally {
                gl.glActiveTexture(GL_TEXTURE0);
            }
        }

        private void restoreSecondTexture() {
            Throwable failure = null;
            try {
                gl.glActiveTexture(GL_TEXTURE0 + 2);
                gl.glBindTexture(GL_TEXTURE_2D, secondTexture);
            } catch (RuntimeException | Error error) { failure = error; }
            try {
                if (samplerObjects) gl.glBindSampler(2, secondSampler);
            } catch (RuntimeException | Error error) { failure = cleanupFailure(failure, error); }
            try {
                gl.glActiveTexture(unit);
            } catch (RuntimeException | Error error) { failure = cleanupFailure(failure, error); }
            if (failure instanceof Error) throw (Error) failure;
            if (failure != null) throw (RuntimeException) failure;
        }

        private void applyClip(LayoutBox clip) {
            if (world != null) clip = GlFontVisibility.viewport(world.context);
            if (world != null && world.context.scissorEnabled) {
                int[] s = world.context.scissor();
                LayoutBox hostClip =
                        new LayoutBox(
                                s[0], Math.toIntExact((long) height - s[1] - s[3]), s[2], s[3]);
                if (clip == null) clip = hostClip;
                else {
                    long l = Math.max((long) clip.x, hostClip.x),
                            top = Math.max((long) clip.y, hostClip.y);
                    long
                            r =
                                    Math.min(
                                            (long) clip.x + clip.width,
                                            (long) hostClip.x + hostClip.width),
                            bottom =
                                    Math.min(
                                            (long) clip.y + clip.height,
                                            (long) hostClip.y + hostClip.height);
                    clip =
                            new LayoutBox(
                                    Math.toIntExact(l),
                                    Math.toIntExact(top),
                                    Math.toIntExact(Math.max(0, r - l)),
                                    Math.toIntExact(Math.max(0, bottom - top)));
                }
            }

            enable(GL_SCISSOR_TEST, clip != null);
            if (clip != null)
                gl.glScissor(
                        clip.x,
                        Math.toIntExact((long) height - clip.y - clip.height),
                        clip.width,
                        clip.height);
        }

        @Override
        public void close() {
            if (done) return;
            done = true;
            Throwable failure = null;
            try {
                gl.glUseProgram(program);
                gl.glBindVertexArray(oldVao);
                gl.glBindBuffer(GL_ARRAY_BUFFER, oldVbo);
                gl.glActiveTexture(GL_TEXTURE0);
                gl.glBindTexture(GL_TEXTURE_2D, texture2d);
                gl.glBindTexture(GL_TEXTURE_2D_ARRAY, textureArray);
                gl.glActiveTexture(unit);
                if (restoreBlendCull) {
                    gl.glBlendEquationSeparate(eqRgb, eqAlpha);
                    gl.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
                    enable(GL_BLEND, blend);
                    enable(GL_CULL_FACE, cull);
                }
                gl.glScissor(box[0], box[1], box[2], box[3]);
                enable(GL_SCISSOR_TEST, scissor);
                if (world == null || !world.differential() || depth != world.context.depthTest)
                    enable(GL_DEPTH_TEST, depth);
                if (compatibility) enable(GL_ALPHA_TEST, alphaTest);
                if (samplerObjects) gl.glBindSampler(0, sampler);
            } catch (RuntimeException | Error error) {
                failure = error;
            }
            try {
                if (secondCaptured) restoreSecondTexture();
            } catch (RuntimeException | Error error) {
                failure = cleanupFailure(failure, error);
            }
            try {
                if (world != null) world.close();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            // Irisclose最后重建effective缓存与旧pass；两个cleanup均执行并保留先前错误。
            try {
                if (iris != null) iris.close();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            } finally {
                active = null;
            }
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
        }
    }

    private void quad(Quad q, float layer) {
        vertex(q, q.x0, q.y0, q.s0, q.t0, layer);
        vertex(q, q.x1, q.y1, q.s1, q.t1, layer);
        vertex(q, q.x2, q.y2, q.s2, q.t2, layer);
        vertex(q, q.x3, q.y3, q.s3, q.t3, layer);
    }

    private void vertex(Quad q, float x, float y, float u, float v, float layer) {
        staging.putFloat(x).putFloat(y).putFloat(u).putFloat(v).putFloat(layer);
        staging.putFloat((q.color >>> 16 & 255) / 255f)
                .putFloat((q.color >>> 8 & 255) / 255f)
                .putFloat((q.color & 255) / 255f)
                .putFloat((q.color >>> 24) / 255f);
    }

    private void enable(int capability, boolean value) {
        if (value) gl.glEnable(capability);
        else gl.glDisable(capability);
    }

    private void release() {
        Throwable failure = null;
        if (programProducer != null) {
            try { programProducer.close(); }
            catch (RuntimeException | Error error) { failure = cleanupFailure(failure, error); }
            finally { programProducer = null; }
        }
        for (int buffer : new int[] {fontEbo, ebo, vbo}) if (buffer != 0) {
            try { gl.glDeleteBuffers(buffer); }
            catch (RuntimeException | Error error) { failure = cleanupFailure(failure, error); }
        }
        fontEbo = ebo = vbo = 0;
        if (vao != 0) {
            try { gl.glDeleteVertexArrays(vao); }
            catch (RuntimeException | Error error) { failure = cleanupFailure(failure, error); }
            finally { vao = 0; }
        }
        for (int i = 0; i < programs.length; i++) if (programs[i] != 0) {
            try { gl.glDeleteProgram(programs[i]); }
            catch (RuntimeException | Error error) { failure = cleanupFailure(failure, error); }
            finally {
                programs[i] = 0;
                uniforms[i] = null;
            }
        }
        staging = null;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw (RuntimeException) failure;
    }

    private static Throwable cleanupFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    @Override
    public void close() {
        if (closed) return;
        if (active != null) throw new IllegalStateException("先结束提交范围");
        closed = true;
        release();
    }
}
