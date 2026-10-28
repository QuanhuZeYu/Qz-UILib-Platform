package club.heiqi.uilib.host.media.ffmpeg;

import club.heiqi.uilib.ui.media.spi.MediaCancellation;
import club.heiqi.uilib.ui.media.spi.MediaDecoder;
import club.heiqi.uilib.ui.media.spi.MediaDecoderFactory;
import club.heiqi.uilib.ui.media.spi.MediaDecoderSource;

import club.heiqi.uilib.ui.media.MediaSource;


/** 显式open时初始化JavaCPP/FFmpeg，返回进程内解码器。 */
public final class FfmpegDecoderFactory implements MediaDecoderFactory {
    @Override public MediaDecoderSource openSource(MediaSource source,MediaCancellation lifetime) throws Exception {
        if(source.audio!=null)return MediaDecoderFactory.super.openSource(source,lifetime);
        return new HlsSourceContext(source,lifetime);
    }
    @Override public MediaDecoder open(MediaSource source, MediaCancellation cancellation) throws Exception {
        return source.audio==null?new FfmpegDecoder(source,cancellation):new SplitMediaDecoder(source,cancellation);
    }
}
