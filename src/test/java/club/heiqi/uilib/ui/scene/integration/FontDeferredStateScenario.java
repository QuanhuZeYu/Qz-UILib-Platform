package club.heiqi.uilib.ui.scene.integration;

import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.testing.GlfwTestEnvironment;
import club.heiqi.uilib.testing.GlfwTestEnvironment.Profile;
import club.heiqi.uilib.host.client.gl.GlApi;
import club.heiqi.uilib.host.client.gl.Lwjgl3GlApi;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Paths;
import java.io.File;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.Collections;
import org.lwjgl.BufferUtils;
import club.heiqi.uilib.host.font.IsolatedFontEngine;
import club.heiqi.uilib.host.client.font.ClientFontServices;
import club.heiqi.uilib.host.client.font.context.StandardFontContext;
import club.heiqi.uilib.host.client.gl.GlPaintPlanRenderer;
import club.heiqi.uilib.host.client.gl.GlProgramProvider;
import club.heiqi.uilib.host.client.gl.GlProgramScope;
import club.heiqi.uilib.host.client.gl.GlQuadRenderer;
import club.heiqi.uilib.host.client.gl.GlGlyphAtlas;
import club.heiqi.uilib.ui.render.batch.OrderedQuadBatch.Quad;
import club.heiqi.uilib.ui.render.batch.OrderedQuadBatch.State;
import club.heiqi.uilib.ui.render.batch.OrderedQuadCollector;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.paint.PaintCommand;
import club.heiqi.uilib.ui.scene.paint.PaintPlan;
import club.heiqi.uilib.ui.scene.paint.PaintPlanBuilder;
import club.heiqi.uilib.ui.scene.text.TextStyle;
import club.heiqi.uilib.ui.scene.text.TextLayout;
import club.heiqi.uilib.ui.scene.text.font.FontSource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import club.heiqi.uilib.host.client.font.context.FontRenderContext;
import club.heiqi.uilib.host.client.font.context.LwjglFontContextCapture;

/** 核验延期producer状态捕获，以及标准字体经正式native/atlas/renderer得到的GPU coverage。 */
public final class FontDeferredStateScenario extends TestApplication {
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    private static void stateA(int framebuffer) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL11.glViewport(3, 5, 41, 43);
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendFuncSeparate(
                GL14.GL_CONSTANT_COLOR,
                GL14.GL_ONE_MINUS_CONSTANT_COLOR,
                GL11.GL_SRC_ALPHA,
                GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_SUBTRACT, GL14.GL_FUNC_REVERSE_SUBTRACT);
        GL14.glBlendColor(0.25f, 0.5f, 0.75f, 1);
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL20.glStencilFuncSeparate(GL11.GL_FRONT, GL11.GL_EQUAL, 3, 0x35);
        GL20.glStencilMaskSeparate(GL11.GL_FRONT, 0x53);
        GL20.glStencilOpSeparate(GL11.GL_FRONT, GL11.GL_REPLACE, GL11.GL_INCR, GL11.GL_DECR);
        GL20.glStencilFuncSeparate(GL11.GL_BACK, GL11.GL_NOTEQUAL, 7, 0x27);
        GL20.glStencilMaskSeparate(GL11.GL_BACK, 0x72);
        GL20.glStencilOpSeparate(GL11.GL_BACK, GL11.GL_ZERO, GL11.GL_INVERT, GL11.GL_KEEP);
        GL11.glColorMask(true, false, true, false);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_GREATER);
        GL11.glDepthMask(false);
        GL11.glDepthRange(0.125, 0.875);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(7, 9, 11, 13);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_FRONT);
        GL11.glFrontFace(GL11.GL_CW);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(1.25f, -2.5f);
    }

    private static void stateB(int framebuffer) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL11.glViewport(0, 0, 64, 64);
        GL11.glDisable(GL11.GL_BLEND);
        GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        GL14.glBlendColor(0, 0, 0, 0);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL20.glStencilFuncSeparate(GL11.GL_FRONT_AND_BACK, GL11.GL_ALWAYS, 0, -1);
        GL20.glStencilMaskSeparate(GL11.GL_FRONT_AND_BACK, -1);
        GL20.glStencilOpSeparate(GL11.GL_FRONT_AND_BACK, GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glColorMask(false, true, false, true);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glDepthMask(true);
        GL11.glDepthRange(0, 1);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(0, 0, 64, 64);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glFrontFace(GL11.GL_CCW);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(0, 0);
    }

    private static void verifyA(FontRenderContext a, int framebuffer) {
        FontRenderContext.RasterState r = a.rasterState;
        require(
                a.drawFramebuffer == framebuffer
                        && Arrays.equals(a.projection.viewport(), new int[] {3, 5, 41, 43}),
                "historical target/viewport");
        require(
                a.depthTest
                        && !a.depthWrite
                        && a.depthFunction == GL11.GL_GREATER
                        && a.scissorEnabled
                        && Arrays.equals(a.scissor(), new int[] {7, 9, 11, 13}),
                "historical depth/scissor");
        require(
                r.blendEnabled
                        && r.blendSrcRgb == GL14.GL_CONSTANT_COLOR
                        && r.blendDstRgb == GL14.GL_ONE_MINUS_CONSTANT_COLOR
                        && r.blendSrcAlpha == GL11.GL_SRC_ALPHA
                        && r.blendDstAlpha == GL11.GL_ONE_MINUS_SRC_ALPHA
                        && r.blendEquationRgb == GL14.GL_FUNC_SUBTRACT
                        && r.blendEquationAlpha == GL14.GL_FUNC_REVERSE_SUBTRACT,
                "historical blend");
        require(
                Arrays.equals(r.blendColor(), new float[] {0.25f, 0.5f, 0.75f, 1})
                        && Arrays.equals(r.depthRange(), new float[] {0.125f, 0.875f})
                        && Arrays.equals(r.colorMask(), new boolean[] {true, false, true, false}),
                "historical vectors");
        require(
                r.stencilEnabled
                        && r.frontStencil.function == GL11.GL_EQUAL
                        && r.frontStencil.reference == 3
                        && r.frontStencil.valueMask == 0x35
                        && r.frontStencil.writeMask == 0x53
                        && r.frontStencil.fail == GL11.GL_REPLACE
                        && r.frontStencil.depthFail == GL11.GL_INCR
                        && r.frontStencil.depthPass == GL11.GL_DECR,
                "front stencil");
        require(
                r.backStencil.function == GL11.GL_NOTEQUAL
                        && r.backStencil.reference == 7
                        && r.backStencil.valueMask == 0x27
                        && r.backStencil.writeMask == 0x72
                        && r.backStencil.fail == GL11.GL_ZERO
                        && r.backStencil.depthFail == GL11.GL_INVERT
                        && r.backStencil.depthPass == GL11.GL_KEEP,
                "back stencil");
        require(
                r.cullEnabled
                        && r.cullFace == GL11.GL_FRONT
                        && r.frontFace == GL11.GL_CW
                        && r.polygonOffsetFill
                        && r.polygonOffsetFactor == 1.25f
                        && r.polygonOffsetUnits == -2.5f,
                "historical cull/polygon");
    }

    private static void effectiveProvider() {
        LwjglFontContextCapture.MatrixStateProvider effective =
                new LwjglFontContextCapture.MatrixStateProvider() {
                    public GlApi gl() {
                        return new Lwjgl3GlApi();
                    }

                    public FontRenderContext.MatrixState capture() {
                        return FontRenderContext.MatrixState.ui(IDENTITY, IDENTITY);
                    }

                    public int[] integers(int name, int count) {
                        if (name == GL11.GL_VIEWPORT) return new int[] {1, 2, 30, 40};
                        if (name == GL11.GL_SCISSOR_BOX) return new int[] {4, 6, 8, 10};
                        return LwjglFontContextCapture.MatrixStateProvider.super.integers(
                                name, count);
                    }

                    public int integer(int name) {
                        if (name == GL11.GL_DEPTH_FUNC) return GL11.GL_GEQUAL;
                        return LwjglFontContextCapture.MatrixStateProvider.super.integer(name);
                    }

                    public boolean enabled(int name) {
                        if (name == GL11.GL_DEPTH_TEST || name == GL11.GL_SCISSOR_TEST) return true;
                        return LwjglFontContextCapture.MatrixStateProvider.super.enabled(name);
                    }

                    public boolean[] booleans(int name, int count) {
                        if (name == GL11.GL_DEPTH_WRITEMASK) return new boolean[] {false};
                        return LwjglFontContextCapture.MatrixStateProvider.super.booleans(
                                name, count);
                    }
                };
        FontRenderContext a =
                new LwjglFontContextCapture(new Lwjgl3GlApi(), effective).capture(64, 7, 11);
        require(
                a.depthTest
                        && !a.depthWrite
                        && a.depthFunction == GL11.GL_GEQUAL
                        && a.scissorEnabled
                        && Arrays.equals(a.scissor(), new int[] {4, 6, 8, 10})
                        && Arrays.equals(a.projection.viewport(), new int[] {1, 2, 30, 40}),
                "effective provider ignored for historical depth/viewport/scissor");
        require(
                !GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
                        && GL11.glGetInteger(GL11.GL_DEPTH_FUNC) == GL11.GL_LESS,
                "provider wrote driver");
    }

    private void capture(GlfwTestEnvironment environment, boolean compatibility) {
        own(
                environment.window(
                        64,
                        64,
                        "font-deferred",
                        compatibility ? Profile.COMPATIBILITY : Profile.CORE,
                        compatibility ? 3 : 2));
        int fboA = GL30.glGenFramebuffers(), fboB = GL30.glGenFramebuffers();
        onClose(
                () -> {
                    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
                    GL30.glDeleteFramebuffers(fboA);
                    GL30.glDeleteFramebuffers(fboB);
                });
        {
            LwjglFontContextCapture capture =
                    new LwjglFontContextCapture(
                            new Lwjgl3GlApi(),
                            compatibility
                                    ? LwjglFontContextCapture.compatibility(new Lwjgl3GlApi())
                                    : LwjglFontContextCapture.explicit(
                                            new Lwjgl3GlApi(),
                                            FontRenderContext.MatrixState.ui(IDENTITY, IDENTITY)));
            if (compatibility) {
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glLoadIdentity();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glLoadIdentity();
                GL11.glDisable(GL11.GL_LIGHTING);
            }
            stateA(fboA);
            FontRenderContext a = capture.capture(64, 5, 9);
            verifyA(a, fboA);
            club.heiqi.uilib.host.client.font.context.SuppliedBlendCaptureScenario.verify(
                    new Lwjgl3GlApi(), a.rasterState);
            stateB(fboB);
            FontRenderContext b = capture.capture(64, 6, 10);
            FontRenderContext font = StandardFontContext.from(a);
            FontRenderContext.RasterState fr = font.rasterState, ar = a.rasterState;
            require(
                    font.drawFramebuffer == a.drawFramebuffer
                            && font.depthTest == a.depthTest
                            && font.depthWrite == a.depthWrite
                            && font.depthFunction == a.depthFunction
                            && font.scissorEnabled == a.scissorEnabled
                            && Arrays.equals(font.scissor(), a.scissor())
                            && font.projection == a.projection
                            && font.contextIdentity == a.contextIdentity
                            && font.revision == a.revision
                            && font.passKey == a.passKey,
                    "标准字体丢失target/depth/scissor身份");
            require(
                    fr.stencilEnabled == ar.stencilEnabled
                            && fr.frontStencil == ar.frontStencil
                            && fr.backStencil == ar.backStencil
                            && Arrays.equals(fr.colorMask(), ar.colorMask())
                            && Arrays.equals(fr.depthRange(), ar.depthRange())
                            && Arrays.equals(fr.blendColor(), ar.blendColor())
                            && fr.cullEnabled == ar.cullEnabled
                            && fr.cullFace == ar.cullFace
                            && fr.frontFace == ar.frontFace
                            && fr.polygonOffsetFill == ar.polygonOffsetFill
                            && fr.polygonOffsetFactor == ar.polygonOffsetFactor
                            && fr.polygonOffsetUnits == ar.polygonOffsetUnits,
                    "标准字体丢失nonblend历史限制");
            require(
                    !b.rasterState.blendEnabled
                            && !b.depthTest
                            && b.depthWrite
                            && b.drawFramebuffer == fboB,
                    "stateB captured");
            verifyA(a, fboA);
            a.rasterState.blendColor()[0] = 99;
            a.rasterState.depthRange()[0] = 99;
            a.rasterState.colorMask()[0] = false;
            a.scissor()[0] = 99;
            a.projection.viewport()[0] = 99;
            verifyA(a, fboA);
            require(
                    GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == fboB
                            && !GL11.glIsEnabled(GL11.GL_BLEND),
                    "capture changed driver B");
            effectiveProvider();
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "capture invalid legacy enum");
            System.out.println(
                    (compatibility ? "compat" : "core")
                            + " capturedAImmutable=true driverBUnchanged=true stencilBoth=true blendColorMaskDepthRange=true GL="
                            + GL11.glGetString(GL11.GL_VERSION));
        }
    }

    /** 计数并转发真实GL，真实调用完成后单次注入异常。 */
    private static final class GlCalls implements InvocationHandler {
        private static final int[] QUERIES = {GL14.GL_BLEND_SRC_RGB, GL14.GL_BLEND_DST_RGB,
                GL14.GL_BLEND_SRC_ALPHA, GL14.GL_BLEND_DST_ALPHA, GL20.GL_BLEND_EQUATION_RGB,
                GL20.GL_BLEND_EQUATION_ALPHA, GL11.GL_BLEND, GL11.GL_CULL_FACE,
                GL13.GL_ACTIVE_TEXTURE, GL11.GL_DEPTH_TEST, GL11.GL_SCISSOR_TEST, GL11.GL_SCISSOR_BOX};
        private final GlApi actual = new Lwjgl3GlApi();
        final GlApi gl = (GlApi) Proxy.newProxyInstance(GlApi.class.getClassLoader(),
                new Class<?>[] {GlApi.class}, this);
        final int[] queries = new int[QUERIES.length], writes = new int[4];
        final Map<String, Integer> stateWrites = new LinkedHashMap<>();
        int activeTexture, uniforms, failUnit = -1, programLinks, programDeletes;
        boolean failThirdProgram;
        String failMethod;
        int failName;

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("glGetUniformLocation")) uniforms++;
            if (name.equals("glDeleteProgram")) programDeletes++;
            if (name.equals("glGetInteger") || name.equals("glIsEnabled") || name.equals("glGetIntegerv"))
                for (int i = 0; i < QUERIES.length; i++)
                    if ((Integer) args[0] == QUERIES[i]) queries[i]++;
            if (name.equals("glBlendEquationSeparate")) writes[0]++;
            if (name.equals("glBlendFuncSeparate")) writes[1]++;
            if (name.equals("glEnable") || name.equals("glDisable")) {
                if ((Integer) args[0] == GL11.GL_BLEND) writes[2]++;
                if ((Integer) args[0] == GL11.GL_CULL_FACE) writes[3]++;
            }
            if (name.equals("glActiveTexture")) activeTexture = (Integer) args[0];
            String stateWrite = null;
            if (name.startsWith("glBlend") || name.startsWith("glStencil") || name.equals("glColorMask")
                    || name.equals("glDepthRange") || name.equals("glCullFace") || name.equals("glFrontFace")
                    || name.equals("glPolygonOffset") || name.equals("glBindFramebuffer") || name.equals("glViewport")
                    || name.equals("glDepthFunc") || name.equals("glDepthMask")) stateWrite = name;
            if (name.equals("glEnable") || name.equals("glDisable")) {
                int capability = (Integer) args[0];
                if (capability == GL11.GL_BLEND || capability == GL11.GL_STENCIL_TEST
                        || capability == GL11.GL_CULL_FACE || capability == GL11.GL_POLYGON_OFFSET_FILL
                        || capability == GL11.GL_DEPTH_TEST) stateWrite = "enable:" + capability;
            }
            if (name.equals("glBindTexture") && activeTexture == GL13.GL_TEXTURE1) stateWrite = "lightmapTexture";
            if (name.equals("glBindSampler") && (Integer) args[0] == 1) stateWrite = "lightmapSampler";
            if (stateWrite != null) stateWrites.merge(stateWrite, 1, Integer::sum);
            Object result;
            try { result = method.invoke(actual, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
            if (name.equals("glLinkProgram") && ++programLinks == 3 && failThirdProgram)
                throw new IllegalStateException("真实GL异常探针:glLinkProgram");
            if (name.equals(failMethod) && (failName == 0 || (Integer) args[0] == failName)
                    && (failUnit < 0 || activeTexture == failUnit)) {
                failMethod = null;
                throw new IllegalStateException("真实GL异常探针:" + name);
            }
            return result;
        }

        void reset() {
            Arrays.fill(queries, 0);
            Arrays.fill(writes, 0);
            stateWrites.clear();
            activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        }

        void captured(boolean ui) {
            for (int count : queries) require(count == 1,
                    (ui ? "UI" : "world") + " 重复或遗漏 actual 进入态捕获: " + Arrays.toString(queries));
        }

        void verified(boolean ui) {
            captured(ui);
            // typed world仅设置差异项，以像素、完整进入态和调用次数共同核对恢复。
            for (int count : writes) require(count == 2 || !ui && count == 0,
                    "blend/cull 恢复 owner 重复或遗漏: " + Arrays.toString(writes));
        }
    }

    private static final int WIDTH = 480, HEIGHT = 96;

    private static int[] pixels(int fbo) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        ByteBuffer data = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        int[] result = new int[WIDTH * HEIGHT * 4];
        for (int i = 0; i < result.length; i++) result[i] = data.get(i) & 255;
        return result;
    }

    private static int grayPixels(int[] data) {
        int count = 0;
        for (int i = 0; i < data.length; i += 4) if (data[i] > 0 && data[i] < 255) count++;
        return count;
    }

    private static void image(int[] data, String name) throws Exception {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++)
            for (int x = 0; x < WIDTH; x++) {
                int i = (y * WIDTH + x) * 4;
                image.setRGB(x, HEIGHT - 1 - y, data[i] << 16 | data[i + 1] << 8 | data[i + 2]);
            }
        File directory = new File("build/font-coverage-review");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("输出目录创建失败");
        ImageIO.write(image, "png", new File(directory, name + ".png"));
    }

    private static int[] render(
            ClientFontServices owner,
            GlPaintPlanRenderer renderer,
            PaintPlan plan,
            FontRenderContext context)
            throws Exception {
        return render(owner, renderer, plan, context, false);
    }

    private static int[] render(
            ClientFontServices owner,
            GlPaintPlanRenderer renderer,
            PaintPlan plan,
            FontRenderContext context,
            boolean opaque)
            throws Exception {
        return render(owner, renderer, plan, context, opaque, false);
    }

    private static int[] render(
            ClientFontServices owner, GlPaintPlanRenderer renderer, PaintPlan plan,
            FontRenderContext context, boolean opaque, boolean ui) throws Exception {
        return render(owner, renderer, plan, context, opaque, ui, null);
    }

    private static int[] render(ClientFontServices owner, GlPaintPlanRenderer renderer, PaintPlan plan,
            FontRenderContext context, boolean opaque, boolean ui, GlCalls calls) throws Exception {
        long end = System.nanoTime() + 20000000000L;
        int entryFramebuffer = ui ? context.drawFramebuffer : 0;
        int[] entryViewport = ui ? new int[] {0, 0, WIDTH, HEIGHT} : new int[] {2, 4, 30, 40};
        for (int frame = 0; ; frame++) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, context.drawFramebuffer);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glClearColor(0, 0, 0, opaque ? 1 : 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            // 回放入口故意与捕获目标不同；scope必须恢复实际入口blend/viewport/FBO。
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, entryFramebuffer);
            GL11.glViewport(entryViewport[0], entryViewport[1], entryViewport[2], entryViewport[3]);
            GL11.glDisable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_SUBTRACT, GL14.GL_FUNC_REVERSE_SUBTRACT);
            owner.beginPresentation(frame);
            boolean ready;
            try (GlPaintPlanRenderer.Prepared prepared = ui
                    ? owner.prepare(renderer, plan, new LayoutBox(0, 0, WIDTH, HEIGHT))
                    : owner.prepare(renderer, plan, context)) {
                ready = prepared.readyCount() == prepared.demandCount();
                if (calls != null) calls.reset();
                if (ui) prepared.render(context.projection.mvp(), HEIGHT, false);
                else prepared.render(context);
                if (calls != null) calls.verified(ui);
            } finally {
                owner.endPresentation();
            }
            require(
                    !GL11.glIsEnabled(GL11.GL_BLEND)
                            && GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB) == GL11.GL_ONE
                            && GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB)
                                    == GL14.GL_FUNC_SUBTRACT,
                    "文字回放污染入口blend");
            int[] viewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            require(
                    GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == entryFramebuffer
                            && Arrays.equals(viewport, entryViewport),
                    "文字回放未恢复目标/viewport");
            if (ready) return pixels(context.drawFramebuffer);
            if (System.nanoTime() > end) throw new AssertionError("真实字形未就绪");
            Thread.sleep(2);
        }
    }

    private static FontRenderContext uniformContext(FontRenderContext base, float alpha, float[] lighting,
            int lightmap, float u, boolean alphaTest, int function, float reference, boolean typed) {
        FontRenderContext.MatrixState old = base.state;
        FontRenderContext.MatrixState state = new FontRenderContext.MatrixState(
                old.projection(), old.modelView(), new float[] {1, 1, 1, alpha}, alphaTest, function, reference,
                true, lightmap, u, .5f, old.packedLight, old.normal(), lighting, old.blockEntityId);
        return new FontRenderContext(base.projection, base.drawFramebuffer, HEIGHT, false, GL11.GL_LESS,
                false, false, base.scissor(), state, base.contextIdentity, base.revision, base.passKey,
                typed ? base.rasterState : null);
    }

    private static void uniformPixel(int[] pixels, int offset, double[] expected, double coverage) {
        for (int c = 0; c < 4; c++)
            require(Math.abs(pixels[offset + c] - expected[c] * coverage) <= 2,
                    "动态 uniform 像素未更新 channel=" + c + " actual=" + pixels[offset + c]);
    }

    /** 交替回放world/UI，核验位置复用时灯光、alpha、lightmap与进入态各自更新和恢复。 */
    private static void uniformScopes(ClientFontServices owner, GlPaintPlanRenderer renderer,
            FontRenderContext base, PaintPlan text, int[] textPixels, boolean compatibility, GlCalls calls) throws Exception {
        int sample = 3;
        for (int i = 7; i < textPixels.length; i += 4)
            if (textPixels[i] > textPixels[sample]) sample = i;
        double coverage = textPixels[sample] / 128.0;
        PaintPlanBuilder builder = new PaintPlanBuilder()
                .add(PaintCommand.rect(new LayoutBox(0, 0, 6, 6), null, 0x80ffffff));
        for (PaintCommand command : text.commands()) builder.add(command);
        PaintPlan plan = builder.build();
        int lightmap = GL11.glGenTextures(), entryLightmap = GL11.glGenTextures();
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lightmap);
            ByteBuffer texels = BufferUtils.createByteBuffer(8);
            texels.put(new byte[] {(byte) 128, (byte) 255, 64, (byte) 255,
                    (byte) 255, 64, (byte) 128, (byte) 255}).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2, 1, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texels);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, entryLightmap);
            GL13.glActiveTexture(GL13.GL_TEXTURE3);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_GEQUAL);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_FRONT);
            GL11.glFrontFace(GL11.GL_CW);
            if (compatibility) {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_NEVER, 0);
            }
            FontRenderContext first = uniformContext(base, .5f, new float[] {.25f, 1, .5f},
                    lightmap, .25f, false, GL11.GL_NEVER, 0, true);
            FontRenderContext second = uniformContext(base, 1, new float[] {.5f, .25f, 1},
                    lightmap, .75f, true, GL11.GL_GREATER, .25f, true);
            FontRenderContext[] contexts = {first, second,
                    uniformContext(base, 1, new float[] {.5f, .25f, 1}, lightmap, .75f,
                            true, GL11.GL_GREATER, .75f, true),
                    uniformContext(base, 1, new float[] {.5f, .25f, 1}, lightmap, .75f,
                            true, GL11.GL_LESS, .75f, true),
                    uniformContext(base, .5f, new float[] {.25f, 1, .5f}, lightmap, .25f,
                            false, GL11.GL_NEVER, 0, false)};
            double[] firstColor = {128 * .5 * .25 * 128 / 255, 64, 128 * .5 * .5 * 64 / 255, 64};
            double[] secondColor = {64, 128 * .25 * 64 / 255, 128.0 * 128 / 255, 128};
            double[][] expected = {firstColor, secondColor, new double[4], secondColor, firstColor};
            int marker = ((HEIGHT - 1 - 2) * WIDTH + 2) * 4;
            for (int i = 0; i < contexts.length; i++) {
                int[] world = render(owner, renderer, plan, contexts[i], false, false, calls);
                uniformPixel(world, marker, expected[i], 1);
                uniformPixel(world, sample - 3, expected[i], coverage);
                int[] ui = render(owner, renderer, plan, base, false, true, calls);
                uniformPixel(ui, marker, new double[] {128, 128, 128, 128}, 1);
                uniformPixel(ui, sample - 3, new double[] {128, 128, 128, 128}, coverage);
                require(GL11.glIsEnabled(GL11.GL_DEPTH_TEST) && !GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
                                && GL11.glGetInteger(GL11.GL_DEPTH_FUNC) == GL11.GL_GEQUAL
                                && GL11.glIsEnabled(GL11.GL_CULL_FACE)
                                && GL11.glGetInteger(GL11.GL_CULL_FACE_MODE) == GL11.GL_FRONT
                                && GL11.glGetInteger(GL11.GL_FRONT_FACE) == GL11.GL_CW
                                && GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE3,
                        "world/UI scope 未恢复进入态 depth/cull/texture unit");
                if (compatibility)
                    require(GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
                                    && GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC) == GL11.GL_NEVER,
                            "compat alpha 未禁用或恢复");
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == entryLightmap, "lightmap 绑定未恢复");
                GL13.glActiveTexture(GL13.GL_TEXTURE3);
            }
        } finally {
            GL11.glDeleteTextures(lightmap);
            GL11.glDeleteTextures(entryLightmap);
        }
        System.out.println("UNIFORM_SCOPES_REAL_GL dynamicLightingAlphaLightmap=true worldUiIsolation=true atlas="
                + owner.settings().mode);
    }

    /** 同一真实 prepared 在构造、应用与上传失败后仍恢复任意 actual 进入态，并可再次回放。 */
    private static void exceptionScopes(ClientFontServices owner, GlPaintPlanRenderer renderer,
            FontRenderContext base, PaintPlan plan, int[] expected, GlCalls calls) throws Exception {
        GlApi actual = new Lwjgl3GlApi();
        LwjglFontContextCapture capture = new LwjglFontContextCapture(actual,
                LwjglFontContextCapture.explicit(actual, base.state));
        FontRenderContext legacy = new FontRenderContext(base.projection, base.drawFramebuffer,
                base.sourceFramebufferHeight, base.depthTest, base.depthFunction, base.depthWrite,
                base.scissorEnabled, base.scissor(), base.state, base.contextIdentity, base.revision, base.passKey);
        for (FontRenderContext context : new FontRenderContext[] {base, legacy}) {
            owner.beginPresentation(0);
            try (GlPaintPlanRenderer.Prepared prepared = owner.prepare(renderer, plan, context)) {
                require(prepared.readyCount() == prepared.demandCount(), "异常反例字形尚未热");
                for (String method : new String[] {"glGetInteger", "glDepthMask", "glBufferSubData"}) {
                    stateA(0);
                    GL13.glActiveTexture(GL13.GL_TEXTURE3);
                    calls.reset();
                    calls.failMethod = method;
                    calls.failName = method.equals("glGetInteger") ? GL30.GL_TEXTURE_BINDING_2D_ARRAY : 0;
                    try {
                        prepared.render(context);
                        throw new AssertionError("异常探针未触发:" + method);
                    } catch (IllegalStateException failure) {
                        require(failure.getMessage().equals("真实GL异常探针:" + method), "丢失原始GL异常");
                    } finally { calls.failMethod = null; }
                    calls.captured(false);
                    verifyA(capture.capture(HEIGHT, 1, 0), 0);
                    require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE3,
                            "world 未挂 Scope 或回放异常时泄漏纹理单元");
                }
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, base.drawFramebuffer);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glColorMask(true, true, true, true);
                GL11.glClearColor(0, 0, 0, 0);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                stateA(0);
                prepared.render(context);
                verifyA(capture.capture(HEIGHT, 1, 0), 0);
                if (context.rasterState != null)
                    require(Arrays.equals(expected, pixels(base.drawFramebuffer)), "异常后 typed world 像素未恢复");
                calls.reset();
                calls.failMethod = "glBufferSubData";
                calls.failName = 0;
                try {
                    prepared.render(base.projection.mvp(), HEIGHT, false);
                    throw new AssertionError("UI异常探针未触发");
                } catch (IllegalStateException failure) {
                    require(failure.getMessage().equals("真实GL异常探针:glBufferSubData"), "丢失UI原始异常");
                } finally { calls.failMethod = null; }
                calls.verified(true);
                verifyA(capture.capture(HEIGHT, 1, 0), 0);
            } finally { owner.endPresentation(); }
        }
        System.out.println("SCOPE_RASTER_REAL_GL queriesPerScope=" + Arrays.toString(calls.queries)
                + " singleRestoreOwner=true constructorApplyUploadFailure=true actualEntryRestored=true");
    }

    /** 真实 driver 上覆盖无变化、单组变化、任意 apply 前缀失败，以及 preview/open 的外部写入。 */
    private static void differenceScopes(FontRenderContext base) {
        GlCalls calls = new GlCalls();
        GlApi actual = new Lwjgl3GlApi();
        int entryTexture = GL11.glGenTextures(), targetTexture = GL11.glGenTextures();
        boolean samplers = actual.capabilities().samplerObjects;
        int entrySampler = samplers ? GL33.glGenSamplers() : 0;
        LwjglFontContextCapture entryCapture = new LwjglFontContextCapture(actual,
                LwjglFontContextCapture.explicit(actual,
                        uniformContext(base, 1, new float[] {1, 1, 1}, entryTexture, .5f,
                                false, GL11.GL_ALWAYS, 0, true).state));
        LwjglFontContextCapture targetCapture = new LwjglFontContextCapture(actual,
                LwjglFontContextCapture.explicit(actual,
                        uniformContext(base, 1, new float[] {1, 1, 1}, targetTexture, .5f,
                                false, GL11.GL_ALWAYS, 0, true).state));
        try {
            stateA(0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, entryTexture);
            if (samplers) GL33.glBindSampler(1, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE3);
            FontRenderContext entry = entryCapture.capture(HEIGHT, 1, 0);
            try (GlQuadRenderer quads = new GlQuadRenderer(calls.gl, 4, (resource, generation) -> null,
                    GlProgramProvider.NONE)) {
                calls.reset();
                try (GlQuadRenderer.Scope scope = quads.begin(entry)) {
                    verifyA(entryCapture.capture(HEIGHT, 1, 0), 0);
                }
                calls.captured(false);
                require(calls.stateWrites.isEmpty(), "相同 typed 进入态仍重复设置 raster/target/depth/lightmap: " + calls.stateWrites);
                require(entry.sameDrawState(entryCapture.capture(HEIGHT, 1, 0)), "相同 typed scope 污染进入态");

                GL20.glStencilFuncSeparate(GL11.GL_FRONT, GL11.GL_EQUAL, 4, 0x35);
                FontRenderContext partial = entryCapture.capture(HEIGHT, 1, 0);
                stateA(0);
                calls.reset();
                try (GlQuadRenderer.Scope scope = quads.begin(partial)) {
                    require(partial.sameDrawState(entryCapture.capture(HEIGHT, 1, 0)), "单项 stencil 目标未应用");
                }
                calls.captured(false);
                require(calls.stateWrites.equals(Collections.singletonMap("glStencilFuncSeparate", 2)),
                        "单项变化扩大为其他 setter: " + calls.stateWrites);
                require(entry.sameDrawState(entryCapture.capture(HEIGHT, 1, 0)), "单项 stencil 未恢复");

                stateB(base.drawFramebuffer);
                // 普通 world 的 scissor 由 draw 应用；本段只验证 begin/close 拥有的状态。
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(7, 9, 11, 13);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, targetTexture);
                FontRenderContext target = targetCapture.capture(HEIGHT, 1, 0);
                stateA(0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, entryTexture);
                if (samplers) GL33.glBindSampler(1, entrySampler);
                GL13.glActiveTexture(GL13.GL_TEXTURE3);
                String[] failures = samplers
                        ? new String[] {null, "glDisable", "glDepthMask", "glStencilFuncSeparate", "glBindTexture", "glBindSampler"}
                        : new String[] {null, "glDisable", "glDepthMask", "glStencilFuncSeparate", "glBindTexture"};
                for (String failureMethod : failures) {
                    calls.reset();
                    calls.failMethod = failureMethod;
                    calls.failName = "glBindSampler".equals(failureMethod) ? 1
                            : "glDisable".equals(failureMethod) ? GL11.GL_DEPTH_TEST : 0;
                    try (GlQuadRenderer.Scope scope = quads.begin(target)) {
                        require(failureMethod == null, "异常探针未触发: " + failureMethod);
                        require(target.sameDrawState(targetCapture.capture(HEIGHT, 1, 0)), "typed 历史光栅未完整应用");
                        GL13.glActiveTexture(GL13.GL_TEXTURE1);
                        require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == targetTexture
                                        && (!samplers || GL11.glGetInteger(GL33.GL_SAMPLER_BINDING) == 0),
                                "历史 lightmap 未应用");
                        GL13.glActiveTexture(GL13.GL_TEXTURE0);
                    } catch (IllegalStateException failure) {
                        require(failureMethod != null && failure.getMessage().equals("真实GL异常探针:" + failureMethod),
                                "丢失差异 apply 原始异常");
                    } finally { calls.failMethod = null; }
                    calls.captured(false);
                    require(entry.sameDrawState(entryCapture.capture(HEIGHT, 1, 0)), "差异 apply 前缀失败未恢复全部进入态");
                    require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE3, "差异 scope 泄漏活动单元");
                    GL13.glActiveTexture(GL13.GL_TEXTURE1);
                    require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == entryTexture
                                    && (!samplers || GL11.glGetInteger(GL33.GL_SAMPLER_BINDING) == entrySampler),
                            "差异 scope 未恢复 lightmap 绑定");
                    GL13.glActiveTexture(GL13.GL_TEXTURE3);
                }
            }
            GlProgramProvider failedOpen = new GlProgramProvider() {
                public boolean preview(FontRenderContext context) { return true; }
                public GlProgramScope open(FontRenderContext context) {
                    stateB(base.drawFramebuffer);
                    GL13.glActiveTexture(GL13.GL_TEXTURE1);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, targetTexture);
                    if (samplers) GL33.glBindSampler(1, 0);
                    throw new IllegalStateException("真实pass打开失败");
                }
            };
            try (GlQuadRenderer quads = new GlQuadRenderer(calls.gl, 4, (resource, generation) -> null, failedOpen)) {
                calls.reset();
                try {
                    quads.begin(entry);
                    throw new AssertionError("preview/open 异常未触发");
                } catch (IllegalStateException failure) {
                    require("真实pass打开失败".equals(failure.getMessage()), "丢失 pass 原始异常");
                }
                calls.captured(false);
                require(entry.sameDrawState(entryCapture.capture(HEIGHT, 1, 0)), "preview/open 写入后未完整恢复");
                require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE3, "preview/open 泄漏活动单元");
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == entryTexture
                                && (!samplers || GL11.glGetInteger(GL33.GL_SAMPLER_BINDING) == entrySampler),
                        "preview/open 未完整恢复 lightmap");
                GL13.glActiveTexture(GL13.GL_TEXTURE3);
            }
        } finally {
            if (samplers) GL33.glDeleteSamplers(entrySampler);
            GL11.glDeleteTextures(entryTexture);
            GL11.glDeleteTextures(targetTexture);
        }
        System.out.println("SCOPE_DIFFERENCE_REAL_GL unchangedSettersSkipped=true partialStencil=true applyPrefixFailureRestored=true previewOpenFullRestore=true");
    }

    private static int dualTexture(int format, byte[] rgba) {
        int texture = GL11.glGenTextures();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        ByteBuffer data = BufferUtils.createByteBuffer(rgba.length);
        data.put(rgba).flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, 1, 1, 0,
                format == GL30.GL_R8 ? GL11.GL_RED : GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return texture;
    }

    private static int[] dualFrame(GlQuadRenderer renderer, FontRenderContext context, State[] states,
            Quad[] quads, boolean split, GlCalls calls) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, context.drawFramebuffer);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glColorMask(true, true, true, true);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        stateA(0);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        calls.reset();
        try (GlQuadRenderer.Scope scope = renderer.begin(context)) {
            OrderedQuadCollector collector = new OrderedQuadCollector(8, scope::consumeBatch);
            for (int i = 0; i < states.length; i++) {
                collector.add(states[i], quads[i]);
                // 参考路径按原quad/index次序逐Draw提交，以单纹理结果互证。
                if (split) collector.flush();
            }
            collector.flush();
        }
        return pixels(context.drawFramebuffer);
    }

    private static void dualRestored(GlCalls calls, int texture, int sampler, boolean samplers) {
        require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE3, "dual 活动纹理单元未恢复");
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == texture
                        && (!samplers || GL11.glGetInteger(GL33.GL_SAMPLER_BINDING) == sampler),
                "dual 未恢复 unit2 实际 texture/sampler");
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        calls.captured(false);
    }

    /** 以原序逐Draw路径互证typed world像素，覆盖双纹理单元的异常恢复。 */
    private static void dualScopes(FontRenderContext base) {
        GlCalls calls = new GlCalls();
        int mono = dualTexture(GL30.GL_R8, new byte[] {(byte) 160});
        int color = dualTexture(GL11.GL_RGBA8, new byte[] {64, 32, 96, (byte) 128});
        int third = dualTexture(GL11.GL_RGBA8, new byte[] {0, 0, 0, (byte) 192});
        int light = dualTexture(GL11.GL_RGBA8, new byte[] {(byte) 128, (byte) 192, (byte) 255, (byte) 255});
        int entry = dualTexture(GL11.GL_RGBA8, new byte[] {7, 11, 13, (byte) 255});
        boolean samplers = calls.actual.capabilities().samplerObjects;
        int sampler = samplers ? GL33.glGenSamplers() : 0;
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, entry);
            if (samplers) {
                GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL33.glBindSampler(2, sampler);
            }
            FontRenderContext value = uniformContext(base, .75f, new float[] {.5f, .75f, 1}, light, .5f,
                    true, GL11.GL_GREATER, .05f, true);
            FontRenderContext context = new FontRenderContext(value.projection, value.drawFramebuffer, HEIGHT,
                    false, value.depthFunction, false, true, new int[] {16, 16, 100, 48}, value.state,
                    value.contextIdentity, value.revision, value.passKey, value.rasterState);
            State[] states = {new State(1, 1, 7, null), new State(2, 2, 7, null),
                    new State(1, 1, 7, null), new State(2, 2, 7, null)};
            Quad[] quads = {new Quad(8, 20, 64, 38, 0, 0, 1, 1, 0, 0xb0ff4000),
                    new Quad(24, 24, 64, 38, 0, 0, 1, 1, 0, 0x90ffffff),
                    new Quad(40, 28, 64, 38, 0, 0, 1, 1, 0, 0x8000ff40),
                    new Quad(56, 32, 64, 38, 0, 0, 1, 1, 0, 0xc0ffffff)};
            try (GlQuadRenderer renderer = new GlQuadRenderer(calls.gl, 8, (resource, generation) -> {
                if (generation != 7) throw new IllegalArgumentException("dual 资源代际失配");
                return new GlQuadRenderer.Texture(GL11.GL_TEXTURE_2D,
                        resource == 1 ? mono : resource == 2 ? color : third,
                        resource == 1 ? GlQuadRenderer.CoverageChannel.RED : GlQuadRenderer.CoverageChannel.ALPHA);
            }, GlProgramProvider.NONE)) {
                long draws = renderer.counters().draws;
                long uploads;
                int[] reference = dualFrame(renderer, context, states, quads, true, calls);
                require(renderer.counters().draws - draws == states.length, "dual 旧路径参考未逐 Draw 提交");
                dualRestored(calls, entry, sampler, samplers);
                draws = renderer.counters().draws;
                uploads = renderer.counters().vertexUploads;
                int[] actual = dualFrame(renderer, context, states, quads, false, calls);
                require(Arrays.equals(reference, actual) && grayPixels(actual) > 0,
                        "双纹理与独立旧路径原顺序像素不一致");
                require(renderer.counters().draws - draws == 1 && renderer.counters().vertexUploads - uploads == 1,
                        "dual 未保持一次上传/一次保序 draw");
                dualRestored(calls, entry, sampler, samplers);
                State[] reversed = {states[1], states[0], states[3], states[2]};
                reference = dualFrame(renderer, context, reversed, quads, true, calls);
                actual = dualFrame(renderer, context, reversed, quads, false, calls);
                require(Arrays.equals(reference, actual), "dual slot/material 反转改变覆盖次序");
                dualRestored(calls, entry, sampler, samplers);
                // 第三资源或不同clip沿原多Draw路径提交。
                for (int fallback = 0; fallback < 2; fallback++) {
                    State[] changed = states.clone();
                    changed[2] = new State(1, fallback == 0 ? 3 : 1, 7,
                            fallback == 0 ? null : new LayoutBox(0, 0, WIDTH, HEIGHT));
                    reference = dualFrame(renderer, context, changed, quads, true, calls);
                    draws = renderer.counters().draws;
                    actual = dualFrame(renderer, context, changed, quads, false, calls);
                    require(Arrays.equals(reference, actual) && renderer.counters().draws - draws == changed.length,
                            "dual fallback 改变原路径像素或 draw");
                    dualRestored(calls, entry, sampler, samplers);
                }
                String[] failures = samplers
                        ? new String[] {"glActiveTexture", "glGetInteger", "glBindTexture", "glBindSampler", "glUseProgram", "glDrawElements"}
                        : new String[] {"glActiveTexture", "glGetInteger", "glBindTexture", "glUseProgram", "glDrawElements"};
                for (String failureMethod : failures) {
                    calls.failMethod = failureMethod;
                    calls.failName = failureMethod.equals("glActiveTexture") ? GL13.GL_TEXTURE2
                            : failureMethod.equals("glGetInteger") ? GL11.GL_TEXTURE_BINDING_2D
                            : failureMethod.equals("glBindTexture") ? GL11.GL_TEXTURE_2D
                            : failureMethod.equals("glBindSampler") ? 2 : 0;
                    calls.failUnit = failureMethod.equals("glGetInteger") || failureMethod.equals("glBindTexture")
                            ? GL13.GL_TEXTURE2 : -1;
                    try {
                        dualFrame(renderer, context, states, quads, false, calls);
                        throw new AssertionError("dual 异常探针未触发:" + failureMethod);
                    } catch (IllegalStateException failure) {
                        require(failure.getMessage().equals("真实GL异常探针:" + failureMethod), "dual 丢失原始异常");
                    } finally { calls.failMethod = null; calls.failUnit = -1; }
                    dualRestored(calls, entry, sampler, samplers);
                }
                actual = dualFrame(renderer, context, states, quads, false, calls);
                reference = dualFrame(renderer, context, states, quads, true, calls);
                require(Arrays.equals(reference, actual), "dual 异常后再次回放不一致");
                dualRestored(calls, entry, sampler, samplers);
            }
            require(calls.programLinks == 3 && calls.programDeletes == 3, "dual 第三个 program 未完整释放");
            GlCalls failed = new GlCalls();
            failed.failThirdProgram = true;
            try {
                new GlQuadRenderer(failed.gl, 8, (resource, generation) -> null, GlProgramProvider.NONE);
                throw new AssertionError("dual 第三 program 构造异常未触发");
            } catch (IllegalStateException failure) {
                require("真实GL异常探针:glLinkProgram".equals(failure.getMessage())
                                && failed.programLinks == 3 && failed.programDeletes == 3,
                        "dual program 中途构造失败未完整释放");
            }
            System.out.println("DUAL_2D_REAL_GL orderedReference=true oneDraw=true typedWorldOnly=true unit2Restored=true failureRestored=true");
        } finally {
            if (samplers) { GL33.glBindSampler(2, 0); GL33.glDeleteSamplers(sampler); }
            for (int texture : new int[] {mono, color, third, light, entry}) GL11.glDeleteTextures(texture);
        }
    }

    private void coverage(GlfwTestEnvironment environment, boolean compatibility) throws Exception {
        own(
                environment.window(
                        WIDTH,
                        HEIGHT,
                        "standard-font-coverage",
                        compatibility ? Profile.COMPATIBILITY : Profile.CORE,
                        compatibility ? 3 : 2));
        GlCalls calls = new GlCalls();
        GlApi gl = calls.gl;
        ClientFontServices.Settings defaults = ClientFontServices.Settings.defaults();
        ClientFontServices.Settings settings = new ClientFontServices.Settings(
                defaults.cpu, compatibility ? GlGlyphAtlas.Mode.TEXTURE_ARRAY : GlGlyphAtlas.Mode.TEXTURE_2D,
                defaults.atlas, defaults.upload, defaults.quadCapacity, defaults.maxDemand);
        ClientFontServices owner =
                new ClientFontServices(
                        getClass().getClassLoader(),
                        1,
                        settings,
                        IsolatedFontEngine.shared(),
                        gl,
                        GlProgramProvider.NONE);
        onClose(
                () -> {
                    owner.close();
                    require(owner.awaitRasterizerClose(10000), "worker关闭失败");
                });
        GlPaintPlanRenderer renderer = owner.sharedPlanRenderer();
        int resolvedUniforms = calls.uniforms;
        require(resolvedUniforms > 0, "真实 program 未解析 uniform");
        int texture = GL11.glGenTextures(), fbo = GL30.glGenFramebuffers();
        onClose(
                () -> {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                    GL30.glDeleteFramebuffers(fbo);
                    GL11.glDeleteTextures(texture);
                });
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA8,
                WIDTH,
                HEIGHT,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        require(
                GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE,
                "coverage FBO不完整");
        GL11.glDisable(GL11.GL_DITHER);
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glColorMask(true, true, true, true);
        GL11.glViewport(0, 0, WIDTH, HEIGHT);
        float[] projection = IDENTITY.clone();
        projection[0] = 2f / WIDTH;
        projection[5] = -2f / HEIGHT;
        projection[12] = -1;
        projection[13] = 1;
        FontRenderContext.MatrixState state =
                new FontRenderContext.MatrixState(
                        projection,
                        IDENTITY,
                        new float[] {1, 1, 1, 1},
                        true,
                        GL11.GL_GREATER,
                        .1f,
                        false,
                        0,
                        0,
                        0,
                        0,
                        new float[] {0, 0, 1},
                        new float[] {1, 1, 1},
                        -1);
        if (compatibility) {
            FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
            matrix.put(projection).flip();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadMatrixf(matrix);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, .1f);
        }
        LwjglFontContextCapture capture =
                new LwjglFontContextCapture(
                        gl,
                        compatibility
                                ? LwjglFontContextCapture.compatibility(gl)
                                : LwjglFontContextCapture.explicit(gl, state));
        FontRenderContext raw = capture.capture(HEIGHT, 1, 0),
                standard = StandardFontContext.from(raw);
        require(!GL11.glIsEnabled(GL11.GL_BLEND), "标准快照写宿主driver");
        String[] args = arguments();
        FontSource source =
                args.length > 0
                        ? FontSource.file(
                                "coverage-system",
                                1,
                                Paths.get(args[0]),
                                0,
                                false,
                                Collections.<String>emptySet()).prepare()
                        : null;
        TextStyle style =
                new TextStyle(
                        "default",
                        18,
                        0xffffffff,
                        source == null ? null : Collections.singletonList(source));
        TextLayout text = owner.layout("Minecraft 1.7.10  微软雅黑 中文 ABC 123", style, WIDTH - 20);
        if (source != null)
            for (TextLayout.Line line : text.lines())
                for (TextLayout.Glyph glyph : line.glyphs())
                    require(glyph.source.equals(source), "未命中指定系统字体");
        PaintPlan plan =
                new PaintPlanBuilder()
                        .add(PaintCommand.text(new LayoutBox(10, 12, WIDTH - 20, 60), null, text))
                        .build();
        int[] bad = render(owner, renderer, plan, raw);
        String profile = (compatibility ? "compat" : "core") + (source == null ? "-packaged" : "");
        image(bad, profile + "-bad");
        int badVisible = 0, badPartialAlpha = 0;
        for (int i = 0; i < bad.length; i += 4)
            if (bad[i + 3] > 0) {
                badVisible++;
                if (bad[i + 3] < 255) badPartialAlpha++;
                require(bad[i] == 255 && bad[i + 1] == 255 && bad[i + 2] == 255, "no-blend未复现直色硬边");
            }
        require(badVisible > 0 && badPartialAlpha > 0 && grayPixels(bad) == 0, "no-blend反例没有覆盖率");
        System.out.println(
                profile
                        + " BAD_REAL_GPU visible="
                        + badVisible
                        + " partialAlpha="
                        + badPartialAlpha
                        + " grayRgb="
                        + grayPixels(bad));
        int[] good = render(owner, renderer, plan, standard);
        image(good, profile + "-good");
        int goodGray = grayPixels(good);
        require(goodGray > 0, "标准字体混合关闭导致覆盖率丢失");
        for (int i = 0; i < good.length; i += 4)
            require(
                    Math.abs(good[i] - good[i + 3]) <= 1
                            && good[i] == good[i + 1]
                            && good[i] == good[i + 2],
                    "coverage合成RGB/alpha不一致");
        int[] opaque = render(owner, renderer, plan, standard, true);
        image(opaque, profile + "-opaque");
        for (int i = 0; i < opaque.length; i += 4)
            require(opaque[i] == good[i] && opaque[i + 3] == 255, "不透明背景RGB coverage错误");
        require(grayPixels(opaque) == goodGray, "不透明背景灰度丢失");
        TextLayout translucent =
                owner.layout(text.input.text, style.withColor(0x80ffffff), WIDTH - 20);
        PaintPlan half =
                new PaintPlanBuilder()
                        .add(
                                PaintCommand.text(
                                        new LayoutBox(10, 12, WIDTH - 20, 60), null, translucent))
                        .build();
        int[] halfPixels = render(owner, renderer, half, standard);
        image(halfPixels, profile + "-half");
        int maximum = 0;
        for (int i = 0; i < halfPixels.length; i += 4) {
            maximum = Math.max(maximum, halfPixels[i]);
            require(
                    halfPixels[i] <= 128 && Math.abs(halfPixels[i] - halfPixels[i + 3]) <= 1,
                    "半透明文字被双乘alpha或丢alpha");
        }
        require(maximum >= 127 && grayPixels(halfPixels) > 0, "半透明真实文字未绘制");
        uniformScopes(owner, renderer, standard, half, halfPixels, compatibility, calls);
        exceptionScopes(owner, renderer, standard, half, halfPixels, calls);
        require(calls.uniforms == resolvedUniforms, "重复 world/UI scope 重新查询 uniform 位置");
        differenceScopes(standard);
        dualScopes(standard);
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "coverage GL错误");
        System.out.println(
                profile
                        + " GOOD_REAL_GPU grayRgb="
                        + goodGray
                        + " halfMaximum="
                        + maximum
                        + " restoredBlendDisabled=true source="
                        + (source == null ? "packaged" : source.origin)
                        + " renderer="
                        + GL11.glGetString(GL11.GL_RENDERER)
                        + " GL="
                        + GL11.glGetString(GL11.GL_VERSION));
    }

    public static void main(String[] args) throws Exception { new FontDeferredStateScenario().run(args); }

    @Override
    protected void runTests() throws Exception {
        scenario("值对象复制与兼容构造", FontRenderContextValueScenario::verifyValue);

        GlfwTestEnvironment environment = own(new GlfwTestEnvironment());
        scenario("core历史状态捕捉", () -> capture(environment, false));
        scenario("compatibility历史状态捕捉", () -> capture(environment, true));
        scenario("core标准字体真实coverage", () -> coverage(environment, false));
        scenario("compatibility标准字体真实coverage", () -> coverage(environment, true));
    }

}
