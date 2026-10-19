package club.heiqi.uilib.testing.media;

import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.host.media.IsolatedMediaAdapters;
import club.heiqi.uilib.ui.media.MediaBudget;
import club.heiqi.uilib.ui.media.MediaService;
import club.heiqi.uilib.ui.media.MediaSession;
import club.heiqi.uilib.ui.media.MediaState;
import club.heiqi.uilib.ui.media.spi.MediaDecoderFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 换源会话依赖真实 OpenAL 输出就绪；只由显式设备入口运行。 */
public final class MediaSourceTransitionScenario extends TestApplication {
    public static void main(String[] args) throws Exception { new MediaSourceTransitionScenario().run(args); }

    @Override protected void runTests() throws Exception {
        long before = IsolatedMediaAdapters.resources().activeHandles();
        scenario("新来源尚未打开时不能提前结算定位", () -> {
            try (MediaNetworkFixture fixture = new MediaNetworkFixture()) {
                CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
                AtomicInteger opens = new AtomicInteger();
                MediaDecoderFactory nativeFactory = IsolatedMediaAdapters.decoderFactory();
                MediaDecoderFactory gated = (source, token) -> {
                    if (opens.incrementAndGet() > 1) {
                        entered.countDown();
                        while (!release.await(10, TimeUnit.MILLISECONDS)) token.check();
                    }
                    return nativeFactory.open(source, token);
                };
                try (MediaService service = new MediaService(gated, IsolatedMediaAdapters.audioSinkFactory(), MediaBudget.DEFAULT)) {
                    MediaSession session = service.open(fixture.split(false), false);
                    MediaTestSupport.await(session, s -> s.info.ready && s.outputReady, 5000);
                    session.source(fixture.split(false), 500_000_000L);
                    try {
                        require(entered.await(2, TimeUnit.SECONDS), "新来源读取没有进入门控");
                        long until = System.nanoTime() + 200_000_000L;
                        while (session.snapshot().state == MediaState.SEEKING && System.nanoTime() < until) Thread.sleep(2);
                        require(session.snapshot().state == MediaState.SEEKING,
                                "新来源事实未知时提前完成定位：" + session.snapshot().state);
                    } finally {
                        release.countDown();
                    }
                }
            }
        });
        require(IsolatedMediaAdapters.resources().activeHandles() == before, "换源会话关闭后 native 资源未归还");
    }
}
