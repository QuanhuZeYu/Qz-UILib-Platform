package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.EnumSet;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import club.heiqi.uilib.host.client.font.ClientFontServices;
import club.heiqi.uilib.host.client.font.FramebufferSceneRenderer;
import club.heiqi.uilib.host.client.gl.GlProgramProvider;
import club.heiqi.uilib.host.client.gl.Lwjgl3GlApi;
import club.heiqi.uilib.host.font.freetype.LwjglFontEngine;
import club.heiqi.uilib.host.client.image.ClientImageServices;
import club.heiqi.uilib.host.client.image.JavaImageLoader;
import club.heiqi.uilib.ui.scene.image.ImageService;
import club.heiqi.uilib.ui.scene.image.SharedImageService;
import club.heiqi.uilib.ui.config.ConfigKey;
import club.heiqi.uilib.ui.config.ConfigSession;
import club.heiqi.uilib.ui.scene.control.ThemeConfiguration;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.settings.SettingsConfiguration;
import club.heiqi.uilib.ui.settings.SettingsPage;
import club.heiqi.uilib.ui.scene.host.SceneHost;
import club.heiqi.uilib.ui.scene.host.ScenePage;
import club.heiqi.uilib.ui.scene.host.ScenePageSession;
import club.heiqi.uilib.ui.scene.input.SceneEditEvent;
import club.heiqi.uilib.ui.scene.host.SceneKeyInputAdapter;
import club.heiqi.uilib.ui.scene.host.SceneKeyPressInputAdapter;
import club.heiqi.uilib.ui.scene.input.SceneKey;
import club.heiqi.uilib.ui.scene.input.SceneKeyEvent;
import club.heiqi.uilib.ui.scene.input.ScenePointerEvent;
import club.heiqi.uilib.ui.scene.paint.PaintPlan;
import club.heiqi.uilib.ui.scene.semantics.SceneSemanticRequest;
import club.heiqi.uilib.ui.scene.semantics.SceneSemanticSnapshot;
import club.heiqi.uilib.ui.scene.text.font.FontCandidateConfiguration;
import club.heiqi.uilib.ui.scene.text.font.FontFamilyResolver;
import club.heiqi.uilib.ui.scene.text.font.FontSource;
import club.heiqi.uilib.ui.scene.text.font.FontTextService;

/** 单窗口GLFW宿主；核心空间使用framebuffer像素，原生事件只在此转换一次。
 * GLFW只提供Unicode提交，不提供平台IME预编辑；不宣称支持预编辑组合界面。
 * 窗口、输入与GL操作须在创建线程执行；语义快照可跨线程读取，语义请求经原session异步结算。
 * 关闭仍在有效GL上下文内结束GPU资源。
 */
public final class DesktopWindow implements SceneHost, AutoCloseable {
    final DesktopApplicationConfiguration applicationConfiguration;
    final ReactiveDomain domain;
    final Path configPath;
    private final Thread thread = Thread.currentThread();
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private long window, revision;
    private int framebufferWidth, framebufferHeight, windowWidth, windowHeight;
    private double cursorX, cursorY, scrollRemainder;
    private boolean closed;
    private String title;
    private SettingsConfiguration settingsConfiguration;
    private ThemeConfiguration themeConfiguration;
    private boolean pendingClose, pendingReload;
    private DesktopPageServices pageServices;
    ConfigSession configuration;
    ConfigKey<List<String>> fontCandidates;
    ClientFontServices fonts;
    private ClientImageServices images;
    private FramebufferSceneRenderer renderer;
    private final club.heiqi.uilib.ui.media.MediaService mediaService;
    private club.heiqi.uilib.ui.scene.video.MediaViewPump mediaViews;
    private List<club.heiqi.uilib.ui.media.MediaPresentation> mediaPresentations = Collections.emptyList();
    volatile ScenePageSession page;
    private volatile SceneSemanticSnapshot semanticSnapshot = SceneSemanticSnapshot.EMPTY;
    PaintPlan lastPlan;
    int resizeCallbacks;
    private final SceneKeyInputAdapter keys = new SceneKeyInputAdapter(this::dispatchKey);
    // 保留桌面单次DOWN门；所有合法起手的UP继续交Core，小键盘Enter保持独立。
    private final SceneKeyPressInputAdapter tab = new SceneKeyPressInputAdapter(this::dispatchSpecialKey);
    private final SceneKeyPressInputAdapter enter = new SceneKeyPressInputAdapter(this::dispatchSpecialKey);
    private final SceneKeyPressInputAdapter keypadEnter = new SceneKeyPressInputAdapter(this::dispatchSpecialKey);
    private final SceneKeyPressInputAdapter space = new SceneKeyPressInputAdapter(this::dispatchSpecialKey);
    private final SceneKeyPressInputAdapter escape = new SceneKeyPressInputAdapter(this::dispatchSpecialKey);
    private SceneKeyEvent specialKey;

    private DesktopWindow(Path path) { this(path, null); }
    private DesktopWindow(Path path, club.heiqi.uilib.ui.media.MediaService mediaService) {
        this.mediaService = mediaService;
        applicationConfiguration = new DesktopApplicationConfiguration(path);
        domain = applicationConfiguration.domain;
        configPath = applicationConfiguration.path;
    }
    /** 默认打开正式设置页；配置应用、保存和关闭确认由共享页面负责。 */
    public static DesktopWindow open(Path configuration) throws IOException {
        return openSettings(configuration);
    }
    /** 业务页面直接接入既有ScenePage/ScenePageContext，无第二套页面抽象。 */
    public static DesktopWindow open(Path configuration, ScenePage content) throws IOException {
        return content == null ? open(configuration) : open(configuration, content, null, false, defaultVisible());
    }
    /** 图像owner就绪后声明业务页面；服务归窗口，页面订阅仍归页面作用域。 */
    public static DesktopWindow openWithImages(Path configuration, Function<ImageService, ScenePage> content) throws IOException {
        Objects.requireNonNull(content, "content");
        return openWithServices(configuration, services -> content.apply(services.images()));
    }
    /** 配置与图像服务就绪后声明业务页面；借用同一宿主会话，状态操作在页面域内执行。 */
    public static DesktopWindow openWithServices(Path configuration, Function<DesktopPageServices, ScenePage> content) throws IOException {
        return openWithServices(configuration, defaultVisible(), content);
    }
    /** 创建时选择原生窗口可见性；隐藏窗口仍拥有完整的GL上下文、输入回调和帧生命周期。 */
    public static DesktopWindow openWithServices(Path configuration, boolean visible, Function<DesktopPageServices, ScenePage> content) throws IOException {
        return open(configuration, null, Objects.requireNonNull(content, "content"), false, visible);
    }
    /** 显式借用媒体服务，默认无媒体窗口不会初始化任何音频或解码后端。 */
    public static DesktopWindow openWithMedia(Path configuration, club.heiqi.uilib.ui.media.MediaService media, ScenePage content) throws IOException {
        return open(configuration, Objects.requireNonNull(content, "content"), null, false, defaultVisible(), Objects.requireNonNull(media, "media"));
    }
    public static DesktopWindow openWithServices(Path configuration, club.heiqi.uilib.ui.media.MediaService media,
            boolean visible, Function<DesktopPageServices, ScenePage> content) throws IOException {
        return open(configuration, null, Objects.requireNonNull(content, "content"), false, visible, Objects.requireNonNull(media, "media"));
    }
    /** 与普通页面共用窗口、字体服务和配置域；关闭确认归共享配置页。 */
    public static DesktopWindow openSettings(Path configuration) throws IOException {
        return open(configuration, null, null, true, defaultVisible());
    }
    private static boolean defaultVisible() { return Boolean.parseBoolean(System.getProperty("uilib.desktop.visible", "true")); }
    private static DesktopWindow open(Path configuration, ScenePage content, Function<DesktopPageServices, ScenePage> factory, boolean settings, boolean visible) throws IOException {
        return open(configuration, content, factory, settings, visible, null);
    }
    private static DesktopWindow open(Path configuration, ScenePage content, Function<DesktopPageServices, ScenePage> factory,
            boolean settings, boolean visible, club.heiqi.uilib.ui.media.MediaService media) throws IOException {
        DesktopWindow result = new DesktopWindow(configuration, media);
        try {
            result.initialize(content, factory, settings, visible);
            return result;
        } catch (Throwable failure) {
            try { result.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof IOException) throw (IOException) failure;
            if (failure instanceof UncheckedIOException) throw ((UncheckedIOException) failure).getCause();
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException(failure);
        }
    }
    private <T extends AutoCloseable> T own(T resource) { resources.push(resource); return resource; }
    private void initialize(ScenePage supplied, Function<DesktopPageServices, ScenePage> factory, boolean settings, boolean visible) throws IOException {
        GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err);
        errors.set();
        own(() -> { glfwSetErrorCallback(null); errors.free(); });
        if (!glfwInit()) throw new IllegalStateException("GLFW初始化失败");
        own(() -> { GL.setCapabilities(null); glfwTerminate(); });
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_VISIBLE, visible ? GLFW_TRUE : GLFW_FALSE);
        window = glfwCreateWindow(800, 600, "UILib Desktop", 0, 0);
        if (window == 0) throw new IllegalStateException("桌面GL窗口创建失败");
        own(() -> { Callbacks.glfwFreeCallbacks(window); glfwMakeContextCurrent(0); glfwDestroyWindow(window); window = 0; });
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        glfwSwapInterval(1);
        updateDimensions();
        ClassLoader loader = DesktopWindow.class.getClassLoader();
        fonts = new ClientFontServices(loader, 1, ClientFontServices.Settings.defaults(),
                true, new LwjglFontEngine(), new Lwjgl3GlApi(), GlProgramProvider.NONE);
        // worker与其native会话在销毁窗口前完成结束；只在关闭等待，不在帧里等待字形。
        own(this::closeFonts);
        images = new ClientImageServices(fonts.gl(), new JavaImageLoader(id -> {
            // 桌面资源显式按domain:path映射，不依赖游戏ResourceManager或游戏类路径。
            int separator = id.indexOf(':');
            if (separator <= 0 || separator == id.length() - 1)
                throw new IOException("桌面图像资源标识须为domain:path: " + id);
            String resource = "assets/" + id.substring(0, separator) + "/" + id.substring(separator + 1);
            InputStream snapshot = loader.getResourceAsStream(resource);
            if (snapshot == null) throw new IOException("桌面图像资源不存在: " + id + " -> " + resource);
            return snapshot;
        }, JavaImageLoader.Budget.defaults()), SharedImageService.Budget.defaults());
        own(this::closeImages);
        if (mediaService != null) mediaViews = own(new club.heiqi.uilib.ui.scene.video.MediaViewPump());
        renderer = own(new FramebufferSceneRenderer(fonts, images, mediaService, true));
        List<FontSource> defaults = FontTextService.defaultSources(loader, 1);
        own(applicationConfiguration);
        applicationConfiguration.initialize(defaults, this::validateFontCandidates, this::resourcesReloaded);
        configuration = applicationConfiguration.session;
        fontCandidates = applicationConfiguration.fontCandidates;
        themeConfiguration = applicationConfiguration.theme;
        settingsConfiguration = applicationConfiguration.settings;
        domain.execute(() -> {
            Signal<Long> reload = domain.signal(0L);
            reloadRevision = reload;
            domain.effect(applicationConfiguration.owner, () -> {
                title = applicationConfiguration.title.get();
                glfwSetWindowTitle(window, title);
            });
            domain.effect(applicationConfiguration.owner, () -> {
                reload.get();
                List<String> declarations = configuration.get(fontCandidates);
                Runnable update = () -> {
                    try { applyFonts(declarations); }
                    catch (IOException failure) { throw new UncheckedIOException(failure); }
                };
                // 资源错误属于应用配置事实，与当前打开的页面类型无关。
                settingsConfiguration.updateResources(update);
            });
        });
        pageServices=new DesktopPageServices(images.images(),settingsConfiguration,configuration,this::reloadImmediatelyUnchecked);
        ScenePage content=factory==null?supplied:Objects.requireNonNull(factory.apply(pageServices),"content page");
        if (settings) {
            SettingsPage settingsPage = new SettingsPage(settingsConfiguration, this::completeWindowClose);
            pageServices.onCloseRequested(ignored -> settingsPage.requestClose());
            pageServices.onReloadRequested(ignored -> settingsPage.requestReload());
            content = settingsPage;
        }
        page = own(ScenePageSession.open(domain, this, fonts, content));
        installCallbacks();
        keyAvailability(true);
        if (visible) glfwShowWindow(window);
    }
    /** 最新已发布纯值；读取不进入状态域，不触发绘制或改变焦点。 */
    public SceneSemanticSnapshot semantics() { return semanticSnapshot; }
    @Override public void semanticsChanged(SceneSemanticSnapshot snapshot) { semanticSnapshot = snapshot; }
    /** 任意宿主线程可递交；完成表示原handler和Effect已结算，入队本身不表示业务接受。 */
    public CompletableFuture<SceneSemanticRequest.Result> semantic(SceneSemanticRequest request) {
        return page.semantic(request);
    }
    public CompletableFuture<SceneSemanticRequest.Result> semantic(SceneSemanticRequest request,
            BiConsumer<SceneSemanticRequest.Result, Throwable> completion) {
        return page.semantic(request, completion);
    }
    private Signal<Long> reloadRevision;
    private void resourcesReloaded() {
        domain.checkWrite();
        reloadRevision.set(reloadRevision.get() + 1);
        images.resourcesReloaded();
    }
    private void closeImages() throws Exception {
        // close后owner不可再访问，先取得共享服务；仅关闭阶段等待worker，不阻塞正常帧。
        SharedImageService shared = images.shared();
        Throwable failure = null;
        try { images.close(); } catch (Throwable next) { failure = next; }
        try {
            if (!shared.awaitClose(5000)) throw new IllegalStateException("图像worker未结束");
        } catch (Throwable next) {
            if (next instanceof InterruptedException) Thread.currentThread().interrupt();
            if (failure == null) failure = next;
            else if (failure != next) failure.addSuppressed(next);
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure != null) throw new IllegalStateException(failure);
    }
    private void closeFonts() throws Exception {
        Throwable failure = null;
        try { fonts.close(); } catch (Throwable next) { failure = next; }
        try {
            if (!fonts.awaitRasterizerClose(5000)) throw new IllegalStateException("字体worker未结束");
        } catch (Throwable next) {
            if (next instanceof InterruptedException) Thread.currentThread().interrupt();
            if (failure == null) failure = next;
            else if (failure != next) failure.addSuppressed(next);
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure != null) throw new IllegalStateException(failure);
    }
    private void validateFontCandidates(List<String> declarations) {
        try { resolveFonts(declarations, revision + 1); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private void applyFonts(List<String> declarations) throws IOException {
        fonts.reload(resolveFonts(declarations, ++revision));
    }
    private List<FontSource> resolveFonts(List<String> declarations, long generation) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String path : FontCandidateConfiguration.files(declarations)) files.add(Paths.get(path));
        List<FontSource> builtins = FontTextService.defaultSources(DesktopWindow.class.getClassLoader(), generation);
        FontFamilyResolver resolver = new FontFamilyResolver(files, Collections.emptyList(), generation, System.err::println);
        return FontCandidateConfiguration.resolve(declarations, builtins,
                (path, position) -> resolver.family(Paths.get(path), "desktop-file-" + position));
    }
    public void reloadConfiguration() throws IOException {
        check();
        if (pageServices.handlesReload()){pendingReload=true;return;}
        reloadImmediately();
    }
    private void reloadImmediately() throws IOException {
        try {
            domain.execute(() -> {
                try { applicationConfiguration.reload(); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
    }
    private void reloadImmediatelyUnchecked(){
        try{reloadImmediately();}catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    private void reloadUnchecked() {
        try { reloadConfiguration(); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private void installCallbacks() {
        glfwSetCursorPosCallback(window, (w, x, y) -> cursor(x, y));
        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> mouse(button, action));
        glfwSetScrollCallback(window, (w, x, y) -> scroll(y));
        glfwSetCharCallback(window, (w, codepoint) -> character(codepoint));
        glfwSetKeyCallback(window, (w, key, scan, action, mods) -> key(key, action, mods));
        glfwSetWindowFocusCallback(window, (w, focused) -> focus(focused));
        glfwSetFramebufferSizeCallback(window, (w, width, height) -> { resizeCallbacks++; updateDimensions(); });
        glfwSetWindowSizeCallback(window, (w, width, height) -> updateDimensions());
        glfwSetWindowContentScaleCallback(window, (w, x, y) -> { revision++; updateDimensions(); });
        glfwSetWindowCloseCallback(window, w -> {
            // GLFW已置关闭标记；确认期间必须继续帧循环，只有leave才真正置true。
            if(pageServices.handlesClose())glfwSetWindowShouldClose(w,false);
            requestClose();
        });
        double[] x = new double[1], y = new double[1];
        glfwGetCursorPos(window, x, y);
        cursorX = x[0]; cursorY = y[0];
    }
    private void updateDimensions() {
        int[] width = new int[1], height = new int[1];
        glfwGetFramebufferSize(window, width, height);
        framebufferWidth = width[0]; framebufferHeight = height[0];
        glfwGetWindowSize(window, width, height);
        windowWidth = width[0]; windowHeight = height[0];
    }
    void cursor(double x, double y) {
        check(); cursorX = x; cursorY = y;
        page.pointer(ScenePointerEvent.move(sceneX(), sceneY()));
    }
    private int sceneX() { return windowWidth <= 0 ? 0 : (int)Math.floor(cursorX * framebufferWidth / windowWidth); }
    private int sceneY() { return windowHeight <= 0 ? 0 : (int)Math.floor(cursorY * framebufferHeight / windowHeight); }
    void mouse(int button, int action) {
        check();
        page.pointer(action == GLFW_PRESS ? ScenePointerEvent.down(button, sceneX(), sceneY())
                : ScenePointerEvent.up(button, sceneX(), sceneY()));
    }
    void scroll(double delta) {
        check(); scrollRemainder += delta;
        int whole = (int)scrollRemainder;
        if (whole != 0) { scrollRemainder -= whole; page.pointer(ScenePointerEvent.scroll(sceneX(), sceneY(), whole)); }
    }
    void character(int codepoint) {
        check();
        page.edit(SceneEditEvent.text(new String(Character.toChars(codepoint))));
    }
    private final SceneEditEvent.Clipboard clipboard = new SceneEditEvent.Clipboard() {
        public String read() {
            glfwGetError(null);
            String text = glfwGetClipboardString(window);
            checkClipboardError("读取", true);
            return text == null ? "" : text;
        }
        public void write(String text) {
            glfwGetError(null);
            glfwSetClipboardString(window, text);
            checkClipboardError("写入", false);
        }
    };
    private void checkClipboardError(String operation, boolean emptyTextAllowed) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer description = stack.mallocPointer(1);
            int error = glfwGetError(description);
            if (error == GLFW_NO_ERROR || emptyTextAllowed && error == GLFW_FORMAT_UNAVAILABLE) return;
            String reason = description.get(0) == 0 ? "" : description.getStringUTF8(0);
            throw new IllegalStateException("GLFW剪贴板" + operation + "失败 (" + error + "): " + reason);
        }
    }
    void key(int key, int action, int mods) {
        check();
        SceneKey named = namedKey(key);
        if (named == null) return;
        boolean down = action != GLFW_RELEASE;
        SceneKeyEvent event = new SceneKeyEvent(named, (mods & GLFW_MOD_CONTROL) != 0,
                (mods & GLFW_MOD_SHIFT) != 0, (mods & GLFW_MOD_ALT) != 0,
                (mods & GLFW_MOD_SUPER) != 0, action == GLFW_REPEAT, clipboard,
                down ? SceneKeyEvent.Phase.DOWN : SceneKeyEvent.Phase.UP);
        specialKey = event;
        switch (key) {
            case GLFW_KEY_TAB: tab.key(down, event.repeat); break;
            case GLFW_KEY_ENTER: enter.key(down, event.repeat); break;
            case GLFW_KEY_KP_ENTER: keypadEnter.key(down, event.repeat); break;
            case GLFW_KEY_SPACE: space.key(down, event.repeat); break;
            case GLFW_KEY_ESCAPE: escape.key(down, event.repeat); break;
            default: keys.key(event); return;
        }
        // DOWN由既有单次门递交；UP经同一中性适配器核对合法起手，宿主不合成click。
        if (!down) keys.key(event);
    }
    private void dispatchSpecialKey() { keys.key(specialKey); }
    private void dispatchKey(SceneKeyEvent event) {
        Runnable fallback = null;
        if (event.phase == SceneKeyEvent.Phase.DOWN) {
            if (event.key == SceneKey.ESCAPE) fallback = this::requestClose;
            else if (event.key == SceneKey.R && event.primary && !event.alt && !event.extra) fallback = this::reloadUnchecked;
        }
        page.key(event, fallback);
    }
    private static SceneKey namedKey(int key) {
        if (key >= GLFW_KEY_A && key <= GLFW_KEY_Z) return SceneKey.values()[key - GLFW_KEY_A];
        if (key >= GLFW_KEY_F1 && key <= GLFW_KEY_F12) return SceneKey.valueOf("F" + (key - GLFW_KEY_F1 + 1));
        if (key >= GLFW_KEY_0 && key <= GLFW_KEY_9) return SceneKey.valueOf("DIGIT_" + (key - GLFW_KEY_0));
        switch (key) {
            case GLFW_KEY_LEFT: return SceneKey.LEFT;
            case GLFW_KEY_RIGHT: return SceneKey.RIGHT;
            case GLFW_KEY_UP: return SceneKey.UP;
            case GLFW_KEY_DOWN: return SceneKey.DOWN;
            case GLFW_KEY_HOME: return SceneKey.HOME;
            case GLFW_KEY_END: return SceneKey.END;
            case GLFW_KEY_PAGE_UP: return SceneKey.PAGE_UP;
            case GLFW_KEY_PAGE_DOWN: return SceneKey.PAGE_DOWN;
            case GLFW_KEY_BACKSPACE: return SceneKey.BACKSPACE;
            case GLFW_KEY_DELETE: return SceneKey.DELETE;
            case GLFW_KEY_INSERT: return SceneKey.INSERT;
            case GLFW_KEY_SLASH: return SceneKey.SLASH;
            case GLFW_KEY_COMMA: return SceneKey.COMMA;
            case GLFW_KEY_PERIOD: return SceneKey.PERIOD;
            case GLFW_KEY_MINUS: return SceneKey.MINUS;
            case GLFW_KEY_EQUAL: return SceneKey.EQUAL;
            case GLFW_KEY_SEMICOLON: return SceneKey.SEMICOLON;
            case GLFW_KEY_APOSTROPHE: return SceneKey.APOSTROPHE;
            case GLFW_KEY_LEFT_BRACKET: return SceneKey.LEFT_BRACKET;
            case GLFW_KEY_RIGHT_BRACKET: return SceneKey.RIGHT_BRACKET;
            case GLFW_KEY_BACKSLASH: return SceneKey.BACKSLASH;
            case GLFW_KEY_GRAVE_ACCENT: return SceneKey.GRAVE;
            case GLFW_KEY_TAB: return SceneKey.TAB;
            case GLFW_KEY_ENTER: return SceneKey.ENTER;
            case GLFW_KEY_KP_ENTER: return SceneKey.KEYPAD_ENTER;
            case GLFW_KEY_SPACE: return SceneKey.SPACE;
            case GLFW_KEY_ESCAPE: return SceneKey.ESCAPE;
            default: return null;
        }
    }
    private void keyAvailability(boolean available) {
        keys.availability(available); tab.availability(available); enter.availability(available);
        keypadEnter.availability(available); space.availability(available); escape.availability(available);
    }
    private void keyHistoryDrained() {
        // 本宿主的唯一poll已耗尽本批回调，才读取真实物理释放事实。
        Set<SceneKey> physical = EnumSet.noneOf(SceneKey.class);
        for (int key = GLFW_KEY_SPACE; key <= GLFW_KEY_LAST; key++) {
            SceneKey named = namedKey(key);
            if (named != null && glfwGetKey(window, key) == GLFW_PRESS) physical.add(named);
        }
        keys.historyDrained(physical);
        tab.historyDrained(glfwGetKey(window, GLFW_KEY_TAB) == GLFW_PRESS);
        enter.historyDrained(glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS);
        keypadEnter.historyDrained(glfwGetKey(window, GLFW_KEY_KP_ENTER) == GLFW_PRESS);
        space.historyDrained(glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS);
        escape.historyDrained(glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS);
    }
    void focus(boolean focused) {
        check();
        keyAvailability(focused);
        if (!focused) {
            page.focusLost();
        } else {
            boolean released = true;
            for (int b = 0; b <= GLFW_MOUSE_BUTTON_LAST; b++) released &= glfwGetMouseButton(window, b) == GLFW_RELEASE;
            if (released) page.pointer(ScenePointerEvent.allButtonsReleased());
        }
    }
    private void check() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("桌面窗口操作必须在创建线程");
        if (closed) throw new IllegalStateException("桌面窗口已关闭");
    }
    @Override public ThemeConfiguration themeConfiguration() { return themeConfiguration; }
    @Override public int width() { return framebufferWidth; }
    @Override public int height() { return framebufferHeight; }
    // ready只改变可画资源；字体代际才改变度量，与共同字体服务的职责一致。
    @Override public long metricsRevision() { return revision + fonts.fonts().generation(); }
    /** 正式帧管线在测量/输入前接收完成结果，由既有图像信号使相关声明失效。 */
    @Override public void prepareResources() { check(); images.prepareResources(); if (mediaViews != null) mediaViews.prepareResources(); }
    @Override public club.heiqi.uilib.ui.scene.video.MediaViewPump mediaViews() { return mediaViews; }
    /** 本次成功交换的新帧回执；同帧重复绘制不增加媒体统计。 */
    public List<club.heiqi.uilib.ui.media.MediaPresentation> mediaPresentations() { check(); return mediaPresentations; }
    /** 仅资源纯值；允许close后读取最终归零/残留，不暴露GL句柄或修改能力。 */
    public club.heiqi.uilib.host.client.gl.GlVideoResources.Snapshot mediaResources() {
        return renderer==null?club.heiqi.uilib.host.client.gl.GlVideoResources.Snapshot.EMPTY:renderer.mediaResources();
    }
    @Override public void replay(PaintPlan plan) { lastPlan = plan; renderer.replay(plan, width(), height()); }
    public void frame() {
        check(); glfwPollEvents();
        keyHistoryDrained();
        if (shouldClose() || width() <= 0 || height() <= 0) return;
        glfwMakeContextCurrent(window);
        glViewport(0, 0, width(), height());
        glDisable(GL_DEPTH_TEST); glDisable(GL_SCISSOR_TEST);
        glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
        mediaPresentations = Collections.emptyList();
        try {
        fonts.beginPresentation(++presentation);
        try { page.frame(); } finally { fonts.endPresentation(); }
        if (closed || window == 0) return;
        dispatchPageRequests();
        glfwSwapBuffers(window);
        mediaPresentations = renderer.completePresentation(System.nanoTime());
        } catch (RuntimeException | Error failure) {
            try { renderer.discardPresentation(); } catch (Throwable next) { if (next != failure) failure.addSuppressed(next); }
            throw failure;
        }
    }
    /** 本批输入结算后一次性转交页面；关闭与重载同时到达时优先离开确认。 */
    private void dispatchPageRequests() {
        if (!pendingClose && !pendingReload) return;
        boolean close = pendingClose;
        pendingClose = pendingReload = false;
        if (shouldClose()) return;
        if (close) pageServices.closeRequested(this::completeWindowClose);
        else pageServices.reloadRequested();
    }
    private long presentation;
    public boolean shouldClose() { return closed || glfwWindowShouldClose(window); }
    public void requestClose() {
        check();
        page.pointer(ScenePointerEvent.cancel());
        if(pageServices.handlesClose())pendingClose=true;
        else glfwSetWindowShouldClose(window, true);
    }
    private void completeWindowClose() {
        check();
        // 确认后的真实关闭只结束窗口；持久化始终由页面显式保存承担。
        glfwSetWindowShouldClose(window, true);
    }
    long handle() { return window; }
    String title() { return title; }
    @Override public void close() {
        if (closed) return;
        if (Thread.currentThread() != thread) throw new IllegalStateException("桌面窗口必须在创建线程关闭");
        keyAvailability(false);
        closed = true;
        lastPlan = null; mediaPresentations = Collections.emptyList();
        if(pageServices!=null)pageServices.releaseRequests();
        Throwable failure = null;
        while (!resources.isEmpty()) {
            try { resources.pop().close(); }
            catch (Throwable next) { if (failure == null) failure = next; else if (failure != next) failure.addSuppressed(next); }
        }
        semanticSnapshot = SceneSemanticSnapshot.EMPTY;
        if (failure instanceof Error) throw (Error)failure;
        if (failure instanceof RuntimeException) throw (RuntimeException)failure;
        if (failure != null) throw new IllegalStateException("桌面窗口释放失败", failure);
    }
}
