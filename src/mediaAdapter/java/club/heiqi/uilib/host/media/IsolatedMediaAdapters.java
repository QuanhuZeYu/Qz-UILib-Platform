package club.heiqi.uilib.host.media;

import club.heiqi.uilib.ui.media.spi.MediaCancellation;
import club.heiqi.uilib.ui.media.spi.MediaAudioSinkFactory;
import club.heiqi.uilib.ui.media.spi.MediaAudioSink;
import club.heiqi.uilib.ui.media.spi.MediaDecoder;
import club.heiqi.uilib.ui.media.spi.MediaDecoderFactory;
import club.heiqi.uilib.ui.media.spi.MediaDecoderSource;

import club.heiqi.uilib.ui.media.MediaBudget;
import club.heiqi.uilib.ui.media.MediaSource;


/** 显式选择随包媒体后端；只共享中性SPI，首次真实open才加载native。 */
public final class IsolatedMediaAdapters {
    private static MediaBindingRuntime runtime;
    private IsolatedMediaAdapters() {}
    private static synchronized MediaBindingRuntime runtime() {
        if(runtime==null) runtime=new MediaBindingRuntime();
        return runtime;
    }
    public static MediaDecoderFactory decoderFactory() {
        return new MediaDecoderFactory() {
            @Override public MediaDecoder open(MediaSource source,MediaCancellation cancellation) throws Exception {
                return runtime().decoderFactory().open(source,cancellation);
            }
            @Override public MediaDecoderSource openSource(MediaSource source,MediaCancellation lifetime) throws Exception {
                // 转交真实来源owner，持续持有稳定PTS锚点。
                return runtime().decoderFactory().openSource(source,lifetime);
            }
        };
    }
    public static MediaAudioSinkFactory audioSinkFactory() { return audioSinkFactory(null); }
    public static MediaAudioSinkFactory audioSinkFactory(final String deviceName) {
        return new MediaAudioSinkFactory() {
            @Override public MediaAudioSink open(MediaBudget budget) throws Exception { return runtime().audioFactory(deviceName).open(budget); }
        };
    }
    /** 从已有运行域读取资源快照，支持关闭会话后核对所属句柄。 */
    public static synchronized MediaNativeResources resources() {
        return runtime==null ? new MediaNativeResources(new long[12],0) : new MediaNativeResources(runtime.snapshot(),1);
    }
}
