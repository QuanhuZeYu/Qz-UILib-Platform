package club.heiqi.uilib.host.embedded;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import club.heiqi.uilib.host.client.font.ClientFontServices;
import club.heiqi.uilib.host.client.font.FontResourcePreparation;
import club.heiqi.uilib.host.client.gl.GlApi;
import club.heiqi.uilib.host.client.gl.GlProgramProvider;
import club.heiqi.uilib.host.client.image.ClientImageServices;
import club.heiqi.uilib.host.client.image.JavaImageLoader;
import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigKey;
import club.heiqi.uilib.ui.config.ConfigSchema;
import club.heiqi.uilib.ui.config.ConfigSession;
import club.heiqi.uilib.ui.reactive.Owner;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.scene.control.ThemeConfiguration;
import club.heiqi.uilib.ui.scene.image.ImageService;
import club.heiqi.uilib.ui.scene.image.SharedImageService;
import club.heiqi.uilib.ui.scene.text.font.FontCandidateConfiguration;
import club.heiqi.uilib.ui.scene.text.font.FontSource;
import club.heiqi.uilib.ui.scene.text.font.FontTextService;
import club.heiqi.uilib.ui.scene.text.font.FontResources;
import club.heiqi.uilib.ui.scene.text.font.nativefont.FontEngine;
import club.heiqi.uilib.ui.settings.SettingsConfiguration;

/** 嵌入式应用的资源与配置归属。主线程 capture/apply，worker 只准备不可变文档与字体。
 * 宿主在原 tick/frame 调 pumpResources；ready 完成前不挂载页面或消费测量服务。
 */
public final class EmbeddedSceneServices implements AutoCloseable {
    private final Thread thread = Thread.currentThread();
    private final ConcurrentLinkedQueue<Runnable> ownerActions = new ConcurrentLinkedQueue<>();
    private final ReactiveDomain domain = new ReactiveDomain(this::executeOwner);
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private final FontResourcePreparation preparation = new FontResourcePreparation();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    public interface FontSources {
        List<FontSource> forGeneration(long generation) throws IOException;
        default void resourcesReloaded() {}
        default FontResourcePreparation.Sources capture(long generation) throws IOException {
            return new FontResourcePreparation.Sources(FontTextService.class.getClassLoader(), generation, forGeneration(generation));
        }
    }
    private final FontSources fontSources;
    private FontResourcePreparation.Sources sources;
    private FontResources publishedFonts;
    private ClientFontServices fonts;
    private ClientImageServices images;
    private ConfigSession configuration;
    private ConfigKey<List<String>> candidates;
    private SettingsConfiguration settings;
    private ThemeConfiguration themes;
    private long revision = 1, resourceRevision = 1, fontRequest;
    private final java.util.Set<CompletableFuture<Void>> fontUpdates = new java.util.LinkedHashSet<>();
    private volatile boolean closed;
    private boolean closing;

    private EmbeddedSceneServices(FontSources fontSources) {
        this.fontSources = java.util.Objects.requireNonNull(fontSources, "fontSources");
        resources.push(preparation);
    }
    public static EmbeddedSceneServices open(Path path, ClassLoader loader, FontEngine engine, GlApi gl,
            JavaImageLoader.Resources imageResources) throws IOException {
        return open(path, loader, engine, gl, imageResources, new FontSources() {
            @Override public List<FontSource> forGeneration(long generation) throws IOException { return FontTextService.defaultSources(loader, generation); }
            @Override public FontResourcePreparation.Sources capture(long generation) throws IOException {
                return new FontResourcePreparation.Sources(loader, generation, forGeneration(generation));
            }
        });
    }
    /** 返回资源 owner；真实文档/字体就绪由 ready 表达，不能在 UI 上 join/get。 */
    public static EmbeddedSceneServices open(Path path, ClassLoader loader, FontEngine engine, GlApi gl,
            JavaImageLoader.Resources imageResources, FontSources fontSources) throws IOException {
        EmbeddedSceneServices result = new EmbeddedSceneServices(fontSources);
        try { result.initialize(path.toAbsolutePath().normalize(), engine, gl, imageResources); return result; }
        catch (IOException | RuntimeException | Error failure) {
            try { result.close(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private void initialize(Path path, FontEngine engine, GlApi gl, JavaImageLoader.Resources imageResources) throws IOException {
        sources = fontSources.capture(revision);
        List<FontSource> defaults = sources.builtins;
        candidates = FontCandidateConfiguration.key(defaults);
        ConfigSchema schema = ConfigSchema.of(candidates, ThemeConfiguration.KEY);
        // 先把捕获的 streams 移交worker，文档失败也不会遗留未消费的资源流。
        preparation.prepare(sources, FontCandidateConfiguration.defaults(defaults), Collections.emptyList());
        CompletableFuture.supplyAsync(() -> {
            try {
                if (Files.exists(path)) return ConfigDocument.read(schema, path);
                ConfigDocument document = ConfigDocument.defaults(schema);
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                document.write(path);
                return document;
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }, preparation.executor()).whenComplete((document, failure) -> postOwner(() -> {
            if (closed || closing) return;
            if (failure != null) { ready.completeExceptionally(failure); throw resourceFailure(failure); }
            preparation.prepare(sources, document.get(candidates), Collections.emptyList()).whenComplete((prepared, error) -> postOwner(() -> {
                if (closed || closing) return;
                if (error != null) { ready.completeExceptionally(error); throw resourceFailure(error); }
                try {
                    fonts = new ClientFontServices(prepared, ClientFontServices.Settings.defaults(), false, engine, gl, GlProgramProvider.NONE);
                    resources.push(this::closeFonts);
                    publishedFonts = prepared;
                    images = new ClientImageServices(gl, new JavaImageLoader(imageResources, JavaImageLoader.Budget.defaults()), SharedImageService.Budget.defaults());
                    resources.push(this::closeImages);
                    domain.enqueue(() -> {
                        if (closed || closing) { ready.cancel(false); return; }
                        Owner owner = domain.owner();
                        resources.push(() -> domain.execute(owner::close));
                        configuration = new ConfigSession(domain, schema);
                        configuration.publish(document);
                        themes = new ThemeConfiguration(domain, configuration, owner);
                        settings = SettingsConfiguration.withAsyncValidation(domain, configuration, candidates, null, path,
                                FontCandidateConfiguration.defaults(defaults), this::validateFonts, this::resourcesReloaded);
                        domain.effect(owner, () -> applyFonts(configuration.get(candidates)));
                        domain.deferCleanup(() -> ready.complete(null));
                    }, ready::completeExceptionally);
                } catch (RuntimeException | Error next) { ready.completeExceptionally(next); throw next; }
            }));
        }));
    }
    private void postOwner(Runnable action) {
        if (closed) return;
        ownerActions.add(action);
        if (closed) ownerActions.remove(action);
    }
    private void executeOwner(Runnable action) {
        if (closed) return;
        if (Thread.currentThread() == thread) action.run();
        else { ownerActions.add(action); if (closed) ownerActions.remove(action); }
    }
    /** 仅由宿主原有主线程 tick/frame 推进，不在worker直接drain状态域。 */
    public void pumpResources() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("嵌入资源必须由owner线程推进");
        if (closed) return;
        for (Runnable action; (action = ownerActions.poll()) != null;) action.run();
    }
    public CompletableFuture<Void> ready() { return ready; }
    private void requireReady() {
        if (Thread.currentThread() != thread || fonts == null || !ready.isDone() || ready.isCompletedExceptionally() || closed) throw new IllegalStateException("嵌入资源尚未在owner发布或已关闭");
    }
    public ReactiveDomain domain() { return domain; }
    public ClientFontServices fonts() { requireReady(); return fonts; }
    public SettingsConfiguration settings() { requireReady(); return settings; }
    public ThemeConfiguration themes() { requireReady(); return themes; }
    public ImageService images() { requireReady(); return images.images(); }
    ClientImageServices clientImages() { requireReady(); return images; }
    private CompletableFuture<Void> validateFonts(List<String> declarations) {
        return preparation.validate(sources, declarations, Collections.emptyList()).thenApply(prepared -> null);
    }
    private CompletableFuture<Void> applyFonts(List<String> declarations) {
        long request = ++fontRequest;
        CompletableFuture<Void> completion = new CompletableFuture<>();
        fontUpdates.add(completion);
        completion.whenComplete((ignored, failure) -> fontUpdates.remove(completion));
        FontResourcePreparation.Sources requestedSources = sources;
        preparation.prepare(requestedSources, declarations, Collections.emptyList()).whenComplete((prepared, failure) -> postOwner(() -> {
            if (closed || closing || request != fontRequest) { completion.cancel(false); return; }
            try {
                domain.enqueue(() -> {
                    if (closed || closing || request != fontRequest) { completion.cancel(false); return; }
                    settings.updateResourcesOrThrow(() -> {
                        if (failure != null) throw resourceFailure(failure);
                        if (publishedFonts != prepared) { fonts.reload(prepared); publishedFonts = prepared; revision = prepared.revision; }
                    });
                    preparation.applied(requestedSources, declarations);
                    domain.deferCleanup(() -> completion.complete(null));
                }, completion::completeExceptionally);
            } catch (RuntimeException | Error error) { completion.completeExceptionally(error); }
        }));
        return completion;
    }
    /** 真实资源重载捕获下一代 stream；配置候选重排复用已准备的同一代。 */
    public CompletableFuture<Void> resourcesReloaded() {
        if (Thread.currentThread() != thread || closed || closing) throw new IllegalStateException("资源重载必须在有效owner线程递交");
        fontSources.resourcesReloaded();
        try { sources = fontSources.capture(++resourceRevision); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        preparation.prepare(sources, FontCandidateConfiguration.defaults(sources.builtins), Collections.emptyList());
        CompletableFuture<Void> completion = new CompletableFuture<>();
        fontUpdates.add(completion);
        completion.whenComplete((ignored, failure) -> fontUpdates.remove(completion));
        ready.whenComplete((ignored, failure) -> postOwner(() -> {
            if (closed || closing) { completion.cancel(false); return; }
            if (failure != null) { completion.completeExceptionally(failure); return; }
            domain.enqueue(() -> {
                if (closed || closing) { completion.cancel(false); return; }
                images.resourcesReloaded();
                applyFonts(configuration.get(candidates)).whenComplete((unused, error) -> {
                    if (error == null) completion.complete(null); else completion.completeExceptionally(error);
                });
            }, completion::completeExceptionally);
        }));
        return completion;
    }

    private static RuntimeException resourceFailure(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof Error) throw (Error) failure;
        return failure instanceof RuntimeException ? (RuntimeException) failure : new IllegalStateException("字体资源准备失败", failure);
    }
    private void closeFonts() { fonts.close(); }
    private void closeImages() { images.close(); }
    @Override public void close() {
        if (closed || closing) return;
        if (Thread.currentThread() != thread) throw new IllegalStateException("嵌入资源必须在owner线程结束");
        closing = true;
        if (!ready.isDone()) ready.cancel(false);
        for (CompletableFuture<Void> update : new java.util.ArrayList<>(fontUpdates)) update.cancel(false);
        Throwable failure = null;
        try { pumpResources(); } catch (Throwable next) { failure = next; }
        try { domain.execute(() -> { if (settings != null) settings.close(); }); pumpResources(); }
        catch (Throwable next) { if (failure == null) failure = next; else if (failure != next) failure.addSuppressed(next); }
        while (!resources.isEmpty()) try { resources.pop().close(); pumpResources(); }
        catch (Throwable next) { if (failure == null) failure = next; else if (failure != next) failure.addSuppressed(next); }
        closed = true;
        ownerActions.clear();
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure != null) throw new IllegalStateException("嵌入宿主资源释放失败", failure);
    }
}
