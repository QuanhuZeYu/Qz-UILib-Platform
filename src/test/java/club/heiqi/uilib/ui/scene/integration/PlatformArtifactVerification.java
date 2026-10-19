package club.heiqi.uilib.ui.scene.integration;

import club.heiqi.uilib.testing.TestApplication;

/** 每种受支持 JVM 在一个进程核对正式字体、媒体与桌面产物，整个检查不创建窗口。 */
public final class PlatformArtifactVerification extends TestApplication {
    @Override protected void runTests() throws Exception {
        String[] args = arguments();
        require(args.length == 4 || args.length == 5, "需要Java目标、Core、字体、媒体及可选桌面Jar");
        boolean modern = !"8".equals(args[0]);
        scenario("正式字体像素与JNI隔离", () -> new PackagedFontScenario().run(args[1], args[2]));
        scenario("正式媒体私有绑定与许可", () -> new CoreArtifactScenario().run(modern
                ? new String[] {"--java21", "--media", args[3]} : new String[] {"--media", args[3]}));
        if (args.length == 5) scenario("正式桌面依赖及平台隔离", () -> new CoreArtifactScenario().run(
                "--java21", "--desktop", args[1], args[2], args[4]));
    }
    public static void main(String[] args) throws Exception { new PlatformArtifactVerification().run(args); }
}
