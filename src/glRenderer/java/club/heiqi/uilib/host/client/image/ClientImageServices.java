package club.heiqi.uilib.host.client.image;

import club.heiqi.uilib.host.client.gl.GlApi;
import club.heiqi.uilib.host.client.gl.GlImageResources;
import club.heiqi.uilib.ui.scene.image.ImageService;
import club.heiqi.uilib.ui.scene.image.SharedImageService;
import club.heiqi.uilib.ui.scene.image.ImageLoader;

/** 持有客户端共享图像服务与纹理，页面按各自生命周期释放consumer。 */
public final class ClientImageServices implements AutoCloseable {
    private final Thread thread = Thread.currentThread();
    private final GlApi gl;
    private final SharedImageService images;
    private GlImageResources textures;
    private boolean closed;

    public ClientImageServices(GlApi gl, ImageLoader loader, SharedImageService.Budget budget) {
        this.gl = java.util.Objects.requireNonNull(gl, "gl");
        images = new SharedImageService(loader, budget);
    }

    private void check() {
        if (Thread.currentThread() != thread || closed)
            throw new IllegalStateException("客户端图像owner已关闭或不在主线程");
    }

    public ImageService images() {
        check();
        return images;
    }

    public SharedImageService shared() {
        check();
        return images;
    }

    public void prepareResources() {
        check();
        images.drain();
    }

    public void resourcesReloaded() {
        check();
        images.resourcesReloaded();
    }

    public GlImageResources textures() {
        check();
        if (textures == null)
            textures =
                    new GlImageResources(
                            gl,
                            64,
                            32L * 1024 * 1024,
                            16L * 1024 * 1024,
                            4,
                            new GlImageResources.Listener() {
                                public void presentation(
                                        long identity, boolean pending, String message) {
                                    images.presentation(identity, pending, message);
                                }

                                public void failure(
                                        long identity,
                                        String stage,
                                        boolean unsupported,
                                        String message) {
                                    images.presentation(
                                            identity, false, stage, unsupported, message);
                                }
                            });
        return textures;
    }

    @Override
    public void close() {
        if (closed) return;
        check();
        closed = true;
        Throwable failure = null;
        // CPU与GPU owner分别完成所属清理，首错保留，后续错误附入suppressed。
        try { images.close(); }
        catch (RuntimeException | Error next) { failure = next; }
        try { if (textures != null) textures.close(); }
        catch (RuntimeException | Error next) {
            if (failure == null) failure = next;
            else if (failure != next) failure.addSuppressed(next);
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw (RuntimeException) failure;
    }
}
