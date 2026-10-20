package club.heiqi.uilib.host.client.font;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import club.heiqi.uilib.ui.scene.text.font.FontCandidateConfiguration;
import club.heiqi.uilib.ui.scene.text.font.FontFamilyResolver;
import club.heiqi.uilib.ui.scene.text.font.FontResources;
import club.heiqi.uilib.ui.scene.text.font.FontSource;

/** 客户端资源 owner 的字体准备 worker；只产生 bytes/metadata，绝不打开 native 或访问状态域。
 * 每个 Sources 的完整资源只准备一次，紧接着的 validate/apply 共用同一候选 future。
 * 完成回调由宿主的真实 owner executor 递交；本对象不把 ReactiveDomain.direct 当作线程切换。
 */
public final class FontResourcePreparation implements AutoCloseable {
    public static final class Sources {
        public final ClassLoader loader;
        public final long revision;
        public final List<FontSource> builtins;
        private CompletableFuture<FontResources> prepared;
        private final AutoCloseable preparationStreams;
        public Sources(ClassLoader loader, long revision, List<FontSource> builtins) {
            this(loader, revision, builtins, null);
        }
        public Sources(ClassLoader loader, long revision, List<FontSource> builtins, AutoCloseable preparationStreams) {
            this.preparationStreams = preparationStreams;
            this.loader = Objects.requireNonNull(loader, "loader");
            this.revision = revision;
            this.builtins = Collections.unmodifiableList(new ArrayList<>(builtins));
        }
    }
    /** 已有同步bootstrap发布代可直接成为后续异步候选校验的资源底座。 */
    public static Sources preparedSources(ClassLoader loader, FontResources prepared, List<FontSource> builtins) {
        Sources sources = new Sources(loader, prepared.revision, builtins);
        sources.prepared = CompletableFuture.completedFuture(prepared);
        return sources;
    }
    private final Thread owner = Thread.currentThread();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "uilib-font-resources");
        thread.setDaemon(true);
        return thread;
    });
    private Sources requestedSources;
    private List<String> requestedDeclarations;
    private CompletableFuture<FontResources> requested;
    private boolean closed;

    public Executor executor() { check(); return worker; }

    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("字体准备请求必须在owner线程递交");
        if (closed) throw new IllegalStateException("字体准备owner已关闭");
    }

    /** 新一次校验重新观察文件事实；只有该次validate到apply共用候选结果。 */
    public CompletableFuture<FontResources> validate(Sources sources, List<String> declarations, List<Path> directories) {
        check();
        requestedSources = null; requestedDeclarations = null; requested = null;
        return prepare(sources, declarations, directories);
    }
    public void applied(Sources sources, List<String> declarations) {
        check();
        if (sources == requestedSources && declarations.equals(requestedDeclarations)) {
            requestedSources = null; requestedDeclarations = null; requested = null;
        }
    }
    public CompletableFuture<FontResources> prepare(Sources sources, List<String> declarations, List<Path> directories) {
        check();
        if (sources == requestedSources && declarations.equals(requestedDeclarations)) return requested;
        if (sources.prepared == null) {
            sources.prepared = CompletableFuture.supplyAsync(() -> {
                try (AutoCloseable streams = sources.preparationStreams) {
                    return FontResources.prepare(sources.loader, sources.revision, sources.builtins, sources.builtins);
                } catch (RuntimeException | Error failure) { throw failure; }
                catch (Exception failure) { throw new IllegalStateException("字体来源准备关闭失败", failure); }
            }, worker);
        }
        List<String> values = Collections.unmodifiableList(new ArrayList<>(declarations));
        List<Path> available = Collections.unmodifiableList(new ArrayList<>(directories));
        requestedSources = sources;
        requestedDeclarations = values;
        requested = sources.prepared.thenApplyAsync(base -> {
            try {
                List<Path> files = new ArrayList<>();
                for (String path : FontCandidateConfiguration.files(values)) files.add(Paths.get(path));
                FontFamilyResolver resolver = new FontFamilyResolver(files, available, sources.revision, System.err::println);
                List<FontSource> selected = FontCandidateConfiguration.resolve(values, sources.builtins,
                        (path, position) -> resolver.family(Paths.get(path), "system-file-" + position));
                return base.select(selected);
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }, worker);
        return requested;
    }

    @Override public void close() {
        if (closed) return;
        check();
        closed = true;
        requested = null;
        requestedSources = null;
        requestedDeclarations = null;
        // 已移交的 streams 仍由worker处理并关闭；关闭owner后宿主拒绝发布迟到结果。
        worker.shutdown();
    }
    public boolean awaitClose(long timeoutMillis) throws InterruptedException {
        return worker.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
