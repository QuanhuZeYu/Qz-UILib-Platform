package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static club.heiqi.uilib.host.desktop.DesktopTestFrames.settle;

import club.heiqi.uilib.testing.gallery.TestGallery;
import club.heiqi.uilib.ui.render.batch.BatchingConfiguration;
import club.heiqi.uilib.ui.scene.control.ThemeConfiguration;
import club.heiqi.uilib.ui.scene.control.ThemeMode;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.SceneGeometryView;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.semantics.SceneSemanticSnapshot;
import club.heiqi.uilib.ui.scene.semantics.SceneSemantics;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** 同一真实广场画面切换合批策略；GPU 像素必须保留选择器与下层内容的遮挡。 */
final class DesktopGalleryPopupEvidence {
    private DesktopGalleryPopupEvidence() {}

    static void verify(Path output) throws Exception {
        Path config = output.resolve("gallery-popup.yaml");
        try (DesktopWindow window = DesktopWindow.openWithServices(config,
                services -> DesktopGallery.createGallery(services, TestGallery.Page.SETTINGS))) {
            glfwSetWindowSize(window.handle(), 480, 900);
            DesktopTestInput input = new DesktopTestInput(window);
            try (DesktopTestInput.ScriptedCursor cursor = input.scriptedCursor()) {
                input.focus.invoke(window.handle(), true);
                for (ThemeMode mode : ThemeMode.values()) {
                    window.domain.execute(() -> {
                        window.configuration.set(ThemeConfiguration.KEY, mode);
                        window.configuration.set(BatchingConfiguration.KEY, false);
                    });
                    settle(window);
                    SceneSemanticSnapshot.Entry select = window.semantics().entries.stream()
                            .filter(entry -> entry.values.role == SceneSemantics.Role.SELECT).findFirst()
                            .orElseThrow(() -> new AssertionError("真实广场配置页缺少窄屏分类选择器"));
                    LayoutBox trigger = select.box;
                    input.enqueueAt(trigger.x + trigger.width / 2.0, trigger.y + trigger.height / 2.0);
                    window.frame();
                    input.point(4, 4);
                    settle(window);
                    SceneSemanticSnapshot.Entry popup = window.semantics().entries.stream()
                            .filter(entry -> entry.values.role == SceneSemantics.Role.LISTBOX).findFirst()
                            .orElseThrow(() -> new AssertionError("广场真实输入未打开分类弹层"));
                    BufferedImage ordered = DesktopTestReadback.readFront(window);
                    String name = "gallery-popup-" + mode.name().toLowerCase(java.util.Locale.ROOT);
                    ImageIO.write(ordered, "png", output.resolve(name + "-ordered.png").toFile());
                    window.domain.execute(() -> window.configuration.set(BatchingConfiguration.KEY, true));
                    settle(window);
                    BufferedImage grouped = DesktopTestReadback.readFront(window);
                    ImageIO.write(grouped, "png", output.resolve(name + "-grouped.png").toFile());
                    DesktopGalleryEvidence.saveGeometry(window, output.resolve(name + ".geometry.json"));
                    LayoutBox box = popup.box;
                    int changed = 0;
                    for (int y = box.y; y < box.y + box.height; y++)
                        for (int x = box.x; x < box.x + box.width; x++)
                            if (ordered.getRGB(x, y) != grouped.getRGB(x, y)) changed++;
                    System.out.println("GALLERY_POPUP theme=" + mode + " comparedPixels=" + box.width * box.height
                            + " changedPixels=" + changed + " realGallery=true installedCallbacks=true readBuffer=GL_FRONT");
                    if (changed != 0) throw new AssertionError("激进合批改变了广场选择器的实际遮挡，差异像素=" + changed);
                    input.key.invoke(window.handle(), GLFW_KEY_ESCAPE, 0, GLFW_PRESS, 0);
                    input.key.invoke(window.handle(), GLFW_KEY_ESCAPE, 0, GLFW_RELEASE, 0);
                    window.frame();
                }
                verifyTemplateSpacing(window, input, output);
            }
        }
    }

    private static void verifyTemplateSpacing(DesktopWindow window, DesktopTestInput input, Path output) throws Exception {
        for (int[] size : new int[][] {{480, 900}, {1440, 900}, {2560, 1529}}) {
            glfwSetWindowSize(window.handle(), size[0], size[1]);
            settle(window);
            SceneSemanticSnapshot.Entry field = window.semantics().entries.stream()
                    .filter(entry -> entry.values.role == SceneSemantics.Role.TEXT_AREA).findFirst()
                    .orElseThrow(() -> new AssertionError("广场实际文字样本字段未挂载"));
            LayoutBox[] viewport = {null}, content = {field.box};
            window.domain.execute(() -> {
                SceneGeometryView geometry = DesktopTestInput.geometry(window);
                SceneNode editor = geometry.ordered().stream().filter(entry -> SceneGeometryView.sameBox(entry.box, field.box))
                        .map(entry -> entry.node).findFirst().orElseThrow(() -> new AssertionError("样本语义与正式几何不同步"));
                SceneNode port = editor.parent();
                while (port != null && !(port.scrollable() && port.axis() == SceneNode.Axis.COLUMN)) port = port.parent();
                if (port == null) throw new AssertionError("实际字段没有所属纵向视口");
                LayoutBox area = geometry.get(port).box;
                viewport[0] = area;
                for (SceneNode parent = editor.parent(); parent != port; parent = parent.parent()) {
                    LayoutBox box = geometry.get(parent).box;
                    if (box.x > area.x && box.x + box.width < area.x + area.width && box.width > content[0].width)
                        content[0] = box;
                }
            });
            LayoutBox clip = viewport[0];
            if (clip == null) throw new AssertionError("配置正文缺少实际滚动视口");
            int leading = content[0].x - clip.x;
            int trailing = clip.x + clip.width - content[0].x - content[0].width;
            int rail = window.semantics().entries.stream()
                    .filter(entry -> entry.values.role == SceneSemantics.Role.SCROLLBAR
                            && entry.box.x >= clip.x && entry.box.x < clip.x + clip.width
                            && entry.box.height > entry.box.width)
                    .mapToInt(entry -> entry.box.width).max().orElse(0);
            if (leading <= 0 || trailing <= rail)
                throw new AssertionError("实际字段贴边或碰到滚动条：leading=" + leading + " trailing=" + trailing + " rail=" + rail);
            System.out.println("GALLERY_GUTTER width=" + window.width() + " leading=" + leading + " trailing=" + trailing + " rail=" + rail);
            input.point(clip.x + clip.width / 2.0, clip.y + clip.height / 2.0);
            input.scroll.invoke(window.handle(), 0, -20);
            settle(window);
            String name = "gallery-settings-gutter-" + size[0];
            ImageIO.write(DesktopTestReadback.readFront(window), "png", output.resolve(name + ".png").toFile());
            DesktopGalleryEvidence.saveGeometry(window, output.resolve(name + ".geometry.json"));
        }
    }
}
