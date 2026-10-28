package club.heiqi.uilib.host.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 只管理本次成功工件的资格；执行、失败报告及资源清理归TestApplication。 */
final class DesktopGalleryResult {
    private final Path result;
    private DesktopGalleryResult(Path result) { this.result=result; }

    static DesktopGalleryResult begin(Path output) throws IOException {
        Files.createDirectories(output);
        Path result=output.resolve("result.txt");
        Files.deleteIfExists(result);
        return new DesktopGalleryResult(result);
    }

    /** 应用run及资源清理全部成功返回后写入通过结果。 */
    void passed() throws IOException {
        Files.write(result,"PASS sharedJourney actualGLFWCallbacks actualImagePixels resourceReload close\n"
                .getBytes(StandardCharsets.UTF_8));
    }
}
