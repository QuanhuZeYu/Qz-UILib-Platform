package club.heiqi.uilib.ui.scene.integration;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import club.heiqi.uilib.host.client.gl.GlApi;
import club.heiqi.uilib.host.embedded.EmbeddedSceneServices;
import club.heiqi.uilib.host.font.IsolatedFontEngine;
import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.ui.scene.text.TextLayout;
import club.heiqi.uilib.ui.scene.text.TextStyle;
import club.heiqi.uilib.ui.scene.text.font.FontSource;
import club.heiqi.uilib.ui.scene.text.font.FontTextService;

/** 正式嵌入资源服务与真实CPU字体路径；任何GL调用都使无设备场景失败。 */
public final class EmbeddedFontSourcesScenario extends TestApplication {
    public static void main(String[] args) throws Exception { new EmbeddedFontSourcesScenario().run(); }
    @Override protected void runTests() throws Exception {
        scenario("旧open保持classpath来源且无新资源前提", () -> exercise(false));
        scenario("显式按代来源推进正式字体缓存与布局", () -> exercise(true));
        scenario("worker卡在read时owner关闭立即返回并继续清理streams", this::nonBlockingClose);
        scenario("已排域操作失败仍关闭配置观察future", this::closeAfterQueuedFailure);
        scenario("域drain已排队时资源重载按实际发布完成", this::queuedDomainReload);
    }

    private void exercise(boolean explicit) throws Exception {
        Thread owner = Thread.currentThread();
        ClassLoader parent = getClass().getClassLoader();
        java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        ClassLoader loader = new ClassLoader(parent) {
            @Override public java.io.InputStream getResourceAsStream(String path) {
                require(Thread.currentThread() != owner, "UI开字体流: " + path);
                java.io.InputStream stream = parent.getResourceAsStream(path);
                if (stream == null) return null;
                opens.incrementAndGet();
                return new java.io.FilterInputStream(stream) {
                    private void worker() { require(Thread.currentThread() != owner, "UI read/close字体流"); }
                    @Override public int read() throws java.io.IOException { worker(); return in.read(); }
                    @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException { worker(); return in.read(bytes, offset, length); }
                    @Override public void close() throws java.io.IOException { worker(); in.close(); }
                };
            }
        };
        GlApi gl = (GlApi) Proxy.newProxyInstance(GlApi.class.getClassLoader(), new Class<?>[] {GlApi.class},
                (proxy, method, args) -> { throw new AssertionError("无设备路径访问GL: " + method.getName()); });
        Path directory = Files.createTempDirectory("uilib-embedded-font-");
        Path configuration = directory.resolve("settings.yaml");
        List<Long> generations = new ArrayList<>();
        List<FontSource> supplied = new ArrayList<>();
        EmbeddedSceneServices.FontSources provider = new EmbeddedSceneServices.FontSources() {
            @Override public List<FontSource> forGeneration(long generation) throws java.io.IOException {
                generations.add(generation);
                List<FontSource> sources = FontTextService.defaultSources(loader, generation);
                supplied.clear(); supplied.addAll(sources);
                return sources;
            }
            @Override public club.heiqi.uilib.host.client.font.FontResourcePreparation.Sources capture(long generation) throws java.io.IOException {
                return new club.heiqi.uilib.host.client.font.FontResourcePreparation.Sources(loader, generation, forGeneration(generation));
            }
        };
        try (EmbeddedSceneServices services = explicit
                ? EmbeddedSceneServices.open(configuration, loader, IsolatedFontEngine.shared(), gl, null, provider)
                : EmbeddedSceneServices.open(configuration, loader, IsolatedFontEngine.shared(), gl, null)) {
            require(!services.ready().isDone(), "初始服务在完整准备前发布");
            await(services, services.ready());
            int preparedOpens = opens.get();
            TextStyle style = new TextStyle("default", 16, -1);
            TextLayout first = services.fonts().layout("A中文", style, 500);
            require(!first.lines().get(0).glyphs().isEmpty(), "正式字体服务未完成真实整形");
            long before = services.fonts().fonts().generation();
            if (explicit) require(supplied.contains(first.lines().get(0).glyphs().get(0).source), "正式布局没有使用提供的来源");
            services.fonts().layout("Math text", new TextStyle("math-text-roman", 16, -1), 500);
            services.fonts().layout("Path", new TextStyle("settings-path", 16, -1), 500);
            require(services.fonts().mathFont(style) != null, "真实MATH来源未准备");
            require(opens.get() == preparedOpens, "布局或数学冷尾链重新开资源");
            java.util.concurrent.CompletableFuture<Void> reloaded = services.resourcesReloaded();
            await(services, reloaded);
            TextLayout next = services.fonts().layout("A中文", style, 500);
            require(next.generation > before && next != first, "按代字体来源未使正式布局缓存失效");
            if (explicit) {
                require(generations.size() >= 2 && generations.get(generations.size() - 1) > generations.get(0),
                        "资源重载没有再次请求新代默认来源");
                require(supplied.contains(next.lines().get(0).glyphs().get(0).source), "新布局未使用新代来源");
            }
            require(!services.fonts().hasGlResources(), "CPU资源重载创建了GPU字体资源");
            System.out.println("EMBEDDED_FONT_SOURCES explicit=" + explicit + " realLayout=true generationChanged=true oldOpenPreserved=true noGL=true workerOpenReadClose=true nativeOwnerPreserved=true completeMathAndUi=true");
        } finally {
            Files.deleteIfExists(configuration); Files.deleteIfExists(directory);
        }
    }
    private void queuedDomainReload() throws Exception {
        Path directory = Files.createTempDirectory("uilib-font-domain-");
        Path path = directory.resolve("settings.yaml");
        GlApi gl = (GlApi) Proxy.newProxyInstance(GlApi.class.getClassLoader(), new Class<?>[] {GlApi.class},
                (proxy, method, args) -> { throw new AssertionError("无设备路径访问GL"); });
        try (EmbeddedSceneServices services = EmbeddedSceneServices.open(path, getClass().getClassLoader(), IsolatedFontEngine.shared(), gl, null)) {
            await(services, services.ready());
            long before = services.fonts().fonts().generation();
            java.util.concurrent.atomic.AtomicBoolean executed = new java.util.concurrent.atomic.AtomicBoolean();
            Thread producer = new Thread(() -> services.domain().enqueue(() -> executed.set(true)), "font-domain-queued");
            producer.start(); producer.join();
            java.util.concurrent.CompletableFuture<Void> reloaded = services.resourcesReloaded();
            require(!executed.get() && !reloaded.isDone(), "重载在原域结算前报告完成");
            await(services, reloaded);
            require(executed.get() && services.fonts().fonts().generation() > before, "队列重载没有发布新字体代");
            System.out.println("FONT_RESOURCE_DOMAIN queuedDrain=true noSynchronousReadback=true completedAfterApply=true");
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(directory); }
    }

    private void nonBlockingClose() throws Exception {
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
        ClassLoader parent = getClass().getClassLoader();
        ClassLoader loader = new ClassLoader(parent) {
            @Override public java.io.InputStream getResourceAsStream(String path) {
                java.io.InputStream source = parent.getResourceAsStream(path);
                if (source == null) return null;
                return new java.io.FilterInputStream(source) {
                    @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                        if (first.compareAndSet(true, false)) {
                            reading.countDown();
                            try { release.await(); } catch (InterruptedException failure) { throw new java.io.IOException(failure); }
                        }
                        return in.read(bytes, offset, length);
                    }
                    @Override public void close() throws java.io.IOException { closed.incrementAndGet(); in.close(); }
                };
            }
        };
        club.heiqi.uilib.host.client.font.FontResourcePreparation worker = new club.heiqi.uilib.host.client.font.FontResourcePreparation();
        java.util.concurrent.CompletableFuture<?> future = worker.prepare(
                new club.heiqi.uilib.host.client.font.FontResourcePreparation.Sources(loader, 1, FontTextService.defaultSources(loader, 1)),
                java.util.Collections.emptyList(), java.util.Collections.emptyList());
        try {
            require(reading.await(5, java.util.concurrent.TimeUnit.SECONDS), "未触达worker的真实read");
            worker.close();
            require(release.getCount() == 1 && closed.get() == 0, "关闭在释放read屏障前等待或代替worker关流");
        } finally { release.countDown(); worker.close(); }
        require(worker.awaitClose(5000), "测试等待字体资源worker未结束");
        require(future.isDone(), "关闭后准备future悬挂");
        require(closed.get() == club.heiqi.uilib.ui.scene.text.font.FontResources.resourcePaths().size(), "worker没有关闭完整资源流");
        System.out.println("FONT_OWNER_CLOSE blockedRead=true ownerReturnedBeforeRelease=true allStreamsClosed=true futureSettled=true");
    }

    private void closeAfterQueuedFailure() throws Exception {
        Path directory = Files.createTempDirectory("uilib-font-close-");
        Path path = directory.resolve("settings.yaml");
        GlApi gl = (GlApi) Proxy.newProxyInstance(GlApi.class.getClassLoader(), new Class<?>[] {GlApi.class},
                (proxy, method, args) -> { throw new AssertionError("无设备关闭访问GL"); });
        EmbeddedSceneServices services = EmbeddedSceneServices.open(path, getClass().getClassLoader(), IsolatedFontEngine.shared(), gl, null);
        try {
            await(services, services.ready());
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>> pending = new java.util.concurrent.atomic.AtomicReference<>();
            services.domain().execute(() -> pending.set(services.settings().reload()));
            IllegalStateException first = new IllegalStateException("待排域操作失败");
            Thread producer = new Thread(() -> services.domain().enqueue(() -> { throw first; }), "font-close-owner-queue");
            producer.start(); producer.join();
            try { services.close(); throw new AssertionError("关闭丢失待排首错"); }
            catch (IllegalStateException expected) { require(expected == first, "关闭未保留首个原异常"); }
            require(pending.get().isDone(), "首pump失败跳过配置close，观察future悬挂");
            System.out.println("EMBEDDED_CLOSE_FAILURE firstErrorPreserved=true configFutureSettled=true");
        } finally { services.close(); Files.deleteIfExists(path); Files.deleteIfExists(directory); }
    }

    private static void await(EmbeddedSceneServices services, java.util.concurrent.CompletableFuture<Void> future) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!future.isDone() && System.nanoTime() < deadline) {
            services.pumpResources();
            if (!future.isDone()) Thread.sleep(1);
        }
        if (!future.isDone()) throw new AssertionError("资源准备未完成");
        future.get();
    }

}
