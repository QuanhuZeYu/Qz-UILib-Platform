package club.heiqi.uilib.host.client.gl;

import static club.heiqi.uilib.host.client.gl.GlTokens.*;

import club.heiqi.uilib.host.client.image.ClientImageServices;
import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.scene.image.ImageContent;
import club.heiqi.uilib.ui.scene.image.ImageLoader;
import club.heiqi.uilib.ui.scene.image.ImageOptions;
import club.heiqi.uilib.ui.scene.image.ImagePlacement;
import club.heiqi.uilib.ui.scene.image.ImageSource;
import club.heiqi.uilib.ui.scene.image.SharedImageService;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.paint.PaintCommand;
import club.heiqi.uilib.ui.scene.paint.PaintPlan;
import club.heiqi.uilib.ui.scene.paint.PaintPlanBuilder;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 无窗口关闭故障：GL端口登记实际申请的句柄并在删除边界抛错，owner走生产prepare/capture/close。 */
public final class ImageCloseScenario extends TestApplication {
    private static final LayoutBox BOX = new LayoutBox(0, 0, 2, 2);
    private static final GlImageResources.HostItems HOST = new GlImageResources.HostItems() {
        public boolean supports(ImageContent content) { return true; }
        public void render(ImagePlacement image, LayoutBox clip, int width, int height) {}
    };

    @Override protected void runTests() throws Exception {
        scenario("物品合成释放保留首个Error并继续其余句柄", this::captureFailure);
        scenario("图像缓存释放跨过合成和纹理错误并幂等结束", this::texturesFailure);
        scenario("客户端图像首错不被GPU覆盖且worker结束", this::clientFailure);
    }

    private void captureFailure() throws Exception {
        Boundary boundary = new Boundary(new AssertionError("首个合成关闭Error"));
        GlHostItemCapture capture = new GlHostItemCapture(boundary.gl, 1024);
        try {
            capture.captureLocal(placement(new ImageContent(2, 2) {}), BOX, HOST);
            boundary.armed = true;
            Throwable failure = closeFailure(capture);
            boundary.requireReleased(failure);
            require(capture.retainedBytes() == 0, "合成关闭后仍保留旧分配");
            int attempts = boundary.attempts.size();
            capture.close();
            require(boundary.attempts.size() == attempts, "合成重复close再次删除句柄");
            // close也用于调整合成面尺寸，不能把它改成永久不可分配的终态。
            boundary.armed = false;
            LayoutBox resized = new LayoutBox(0, 0, 3, 2);
            capture.captureLocal(ImagePlacement.fit(new ImageContent(3, 2) {}, resized, ImageOptions.defaults()), resized, HOST);
            capture.close();
            require(boundary.live.isEmpty(), "重建后的合成面没有按原生命周期释放");
        } finally { boundary.armed = false; capture.close(); }
    }

    private void texturesFailure() throws Exception {
        Boundary boundary = new Boundary(new IllegalStateException("首个图像GPU关闭失败"));
        GlImageResources textures = new GlImageResources(boundary.gl, 4, 1024);
        try {
            GlImageResources.Frame frame = prepare(textures);
            int attempts = boundary.attempts.size();
            require(closeFailure(textures) instanceof IllegalStateException, "活动Frame没有阻止owner关闭");
            require(boundary.attempts.size() == attempts, "活动Frame被关闭路径提前删除");
            frame.close();
            boundary.armed = true;
            Throwable failure = closeFailure(textures);
            boundary.requireReleased(failure);
            int ended = boundary.attempts.size();
            textures.close();
            require(boundary.attempts.size() == ended, "GPU owner重复close再次删除");
            requireClosed(textures::retainedBytes);
        } finally { boundary.armed = false; textures.close(); }
    }

    private void clientFailure() throws Exception {
        Boundary boundary = new Boundary(new AssertionError("图像GPU关闭Error"));
        IOException original = new IOException("资源快照关闭失败");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        ClientImageServices client = new ClientImageServices(boundary.gl, source -> new ImageLoader.Task() {
            public ImageContent call() throws Exception { started.countDown(); release.await(); return pixels(); }
            public void close() throws IOException { closes.incrementAndGet(); release.countDown(); throw original; }
        }, SharedImageService.Budget.defaults());
        SharedImageService shared = client.shared();
        GlImageResources textures = client.textures();
        try {
            try (GlImageResources.Frame frame = prepare(textures)) {}
            ReactiveDomain domain = ReactiveDomain.direct();
            domain.execute(() -> shared.acquire(ImageSource.resource("test:close-failure"), domain));
            shared.drain();
            require(started.await(2, TimeUnit.SECONDS), "真实图像worker没有进入任务");
            boundary.armed = true;
            Throwable failure = closeFailure(client);
            require(failure.getCause() == original, "GPU关闭替换了CPU资源首错");
            require(Arrays.equals(failure.getSuppressed(), new Throwable[] {boundary.failures.get(0)}),
                    "GPU错误没有附在CPU首错之后");
            boundary.requireReleased(boundary.failures.get(0));
            require(shared.awaitClose(2000), "关闭失败后图像worker没有结束");
            requireClosed(client::images);
            requireClosed(textures::retainedBytes);
            int attempts = boundary.attempts.size(), cpuCloses = closes.get();
            client.close();
            require(boundary.attempts.size() == attempts && closes.get() == cpuCloses,
                    "客户端图像重复close重新执行了释放");
        } finally {
            release.countDown(); boundary.armed = false;
            try { client.close(); } finally { textures.close(); shared.awaitClose(2000); }
        }
    }

    private static GlImageResources.Frame prepare(GlImageResources textures) {
        textures.hostItems(HOST);
        ImageContent host = new ImageContent(2, 2) {};
        PaintPlan plan = new PaintPlanBuilder().add(image(pixels())).add(image(pixels())).add(image(host)).build();
        GlImageResources.Frame frame = textures.prepare(plan);
        try {
            for (PaintCommand command : plan.commands())
                require(frame.ready(command.image.content.identity), "没有取得待关闭的生产图像资源");
            require(textures.captureHostLocal(placement(host), BOX) != null, "没有分配生产物品合成面");
            return frame;
        } catch (RuntimeException | Error failure) { frame.close(); throw failure; }
    }

    private static ImageContent pixels() { return new ImageContent(2, 2, new byte[16]); }
    private static ImagePlacement placement(ImageContent content) { return ImagePlacement.fit(content, BOX, ImageOptions.defaults()); }
    private static PaintCommand image(ImageContent content) {
        return PaintCommand.image(BOX, BOX, placement(content), null, null, null);
    }
    private static Throwable closeFailure(AutoCloseable resource) throws Exception {
        try { resource.close(); } catch (RuntimeException | Error failure) { return failure; }
        throw new AssertionError("关闭故障没有报告");
    }
    private static void requireClosed(Runnable access) {
        try { access.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("资源owner关闭后仍接受访问");
    }

    private static final class Boundary implements InvocationHandler {
        final Set<String> live = new LinkedHashSet<>();
        final List<String> attempts = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        final GlApi gl = (GlApi) Proxy.newProxyInstance(GlApi.class.getClassLoader(), new Class<?>[] {GlApi.class}, this);
        final Throwable first;
        boolean armed;
        int next;
        Boundary(Throwable first) { this.first = first; }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "glGenTextures": case "glGenFramebuffers": case "glGenRenderbuffers":
                    int id = ++next; live.add(name.substring(5) + ":" + id); return id;
                case "glDeleteTextures": case "glDeleteFramebuffers": case "glDeleteRenderbuffers":
                    String handle = name.substring(8) + ":" + args[0];
                    require(live.remove(handle), "句柄重复删除或不属于owner: " + handle);
                    attempts.add(handle);
                    if (armed) {
                        Throwable failure = failures.isEmpty() ? first : new IllegalStateException("后续释放失败: " + handle);
                        failures.add(failure); throw failure;
                    }
                    return null;
                case "glGetInteger": return (Integer) args[0] == GL_MAX_TEXTURE_SIZE ? 64
                        : (Integer) args[0] == GL_ACTIVE_TEXTURE ? GL_TEXTURE0 : 0;
                case "glCheckFramebufferStatus": return GL_FRAMEBUFFER_COMPLETE;
                default:
                    if (method.getReturnType() == void.class) return null;
                    if (method.getReturnType() == boolean.class) return false;
                    throw new AssertionError("关闭场景不需要该GL查询: " + name);
            }
        }
        void requireReleased(Throwable failure) {
            require(live.isEmpty(), "单项释放失败跳过其余所属句柄: " + live);
            require(!failures.isEmpty() && failure == failures.get(0), "后续错误覆盖首错");
            require(Arrays.equals(failure.getSuppressed(), failures.subList(1, failures.size()).toArray(new Throwable[0])),
                    "后续清理错误没有按发生顺序附在首错上");
        }
    }

    public static void main(String[] args) throws Exception { new ImageCloseScenario().run(args); }
}
