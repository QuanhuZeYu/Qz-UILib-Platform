package club.heiqi.uilib.host.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigKey;
import club.heiqi.uilib.ui.config.ConfigSchema;
import club.heiqi.uilib.ui.config.ConfigSession;
import club.heiqi.uilib.ui.reactive.Computed;
import club.heiqi.uilib.ui.reactive.Owner;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.scene.control.ThemeConfiguration;
import club.heiqi.uilib.ui.scene.text.font.FontCandidateConfiguration;
import club.heiqi.uilib.ui.scene.text.font.FontSource;
import club.heiqi.uilib.ui.settings.SettingsConfiguration;

/** 单窗口桌面应用的配置归属；窗口和页面借用同一会话，资源执行仍归窗口。 */
final class DesktopApplicationConfiguration implements AutoCloseable {
    final ReactiveDomain domain;
    final Path path;
    private ConfigSchema schema;
    private boolean closed;
    Owner owner;
    ConfigSession session;
    ConfigKey<List<String>> fontCandidates;
    ThemeConfiguration theme;
    SettingsConfiguration settings;
    Computed<String> title;

    DesktopApplicationConfiguration(Path path, Executor ownerExecutor) {
        this.path = Objects.requireNonNull(path, "configPath").toAbsolutePath().normalize();
        domain = new ReactiveDomain(Objects.requireNonNull(ownerExecutor, "ownerExecutor"));
    }

    /** owner 在线程提交后台任务前只建立声明；读取和发布必须共享同一组键身份。 */
    void declare(List<FontSource> defaults) {
        domain.execute(() -> {
            if (closed || schema != null) throw new IllegalStateException("桌面配置声明已安装或关闭");
            fontCandidates = FontCandidateConfiguration.key(defaults);
            schema = ConfigSchema.of(DesktopWindowConfiguration.PROFILE, fontCandidates, ThemeConfiguration.KEY);
        });
    }

    /** worker 只处理不可变声明与文档；不创建状态域、Signal、主题或资源。 */
    ConfigDocument prepare(List<FontSource> defaults) throws IOException {
        checkDeclaration(defaults);
        if (Files.exists(path)) return ConfigDocument.read(schema, path);
        ConfigDocument document = ConfigDocument.defaults(schema);
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        document.write(path);
        return document;
    }

    private void checkDeclaration(List<FontSource> defaults) {
        if (schema == null) throw new IllegalStateException("桌面配置尚未声明");
        if (!fontCandidates.defaultValue().equals(FontCandidateConfiguration.defaults(defaults)))
            throw new IllegalArgumentException("桌面配置默认字体声明不一致");
    }

    /** 字体和文档准备完成后由 owner 装配；这里不执行文件或字体 I/O。 */
    void initialize(List<FontSource> defaults, ConfigDocument document,
            Function<List<String>, CompletableFuture<Void>> validate, Runnable resourcesReloaded) {
        Objects.requireNonNull(resourcesReloaded, "resourcesReloaded");
        domain.execute(() -> {
            if (closed || session != null) throw new IllegalStateException("桌面配置已安装或关闭");
            checkDeclaration(defaults);
            owner = domain.owner();
            session = new ConfigSession(domain, schema);
            session.publish(document);
            // 文档发布后才建立应用投影与真实文件基线，页面关闭不结束这些对象。
            theme = new ThemeConfiguration(domain, session, owner);
            settings = SettingsConfiguration.withAsyncValidation(domain, session, fontCandidates, null, path,
                    FontCandidateConfiguration.defaults(defaults), validate, () -> {
                        if (closed) throw new CancellationException("桌面配置所属窗口已关闭");
                        resourcesReloaded.run();
                    });
            title = domain.computed(owner, () -> session.get(DesktopWindowConfiguration.PROFILE).title);
        });
    }

    CompletableFuture<Void> reload() {
        domain.checkWrite();
        return settings.reload();
    }

    @Override public void close() {
        domain.execute(() -> {
            closed = true;
            try (Owner closing = owner) {
                if (settings != null) settings.close();
            }
        });
    }
}
