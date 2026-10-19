package club.heiqi.uilib.host.media.ffmpeg;

import club.heiqi.uilib.ui.media.MediaSource;
import club.heiqi.uilib.ui.media.spi.MediaCancellation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static club.heiqi.uilib.host.media.NativeMediaResourceTracker.*;

/** 首次HTTP响应的owner：内容识别与解封装复用同一AVIO，不另发预探测请求。 */
final class FfmpegInput implements AutoCloseable {
    private AVIOContext io;
    private byte[] playlist;

    FfmpegInput(MediaSource source, AVIOInterruptCB interrupt, MediaCancellation cancellation) throws IOException {
        AVDictionary options = new AVDictionary(null);
        try {
            FfmpegDecoder.configureOptions(source, 0, options);
            io = new AVIOContext(null);
            int opened=avio_open2(io, source.uri.toString(), AVIO_FLAG_READ, interrupt, options);
            if(!io.isNull())acquired(AVIO);
            cancellation.check();FfmpegDecoder.checkNative(opened,"avio_open2");
            byte[] prefix = new byte[7];
            int count = avio_read(io, prefix, prefix.length);
            cancellation.check();
            if (count < 0 && count != AVERROR_EOF) FfmpegDecoder.checkNative(count, "avio_read prefix");
            if (count == prefix.length && Arrays.equals(prefix, new byte[] {'#','E','X','T','M','3','U'})) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                bytes.write(prefix, 0, count);
                byte[] chunk = new byte[4096];
                for (;;) {
                    cancellation.check();
                    int read = avio_read_partial(io, chunk, chunk.length);
                    cancellation.check();
                    if (read == AVERROR_EOF || read == 0) break;
                    FfmpegDecoder.checkNative(read, "avio_read playlist");
                    if (bytes.size() + read > HlsSourceContext.MAX_BYTES) throw new IOException("HLS列表字节超过预算");
                    bytes.write(chunk, 0, read);
                }
                playlist = bytes.toByteArray();
                close();
            } else {
                // 前缀仍在AVIO原缓冲内；回卷只移动buf_ptr，不能重开HTTP或丢弃首包。
                if (avio_seek(io, 0, 0) != 0) throw new IOException("首次媒体输入不能回卷已读前缀");
            }
        } catch (RuntimeException | Error | IOException failure) {
            try { close(); } catch (Throwable next) { if (next != failure) failure.addSuppressed(next); }
            throw failure;
        } finally { av_dict_free(options); }
    }

    byte[] playlist() { return playlist; }
    void attach(AVFormatContext context) {
        context.pb(io); context.flags(context.flags() | AVFormatContext.AVFMT_FLAG_CUSTOM_IO);
    }
    @Override public void close() {
        if (io == null) return;
        AVIOContext owned = io; io = null;
        if (!owned.isNull()) {
            int result = avio_closep(owned); released(AVIO);
            FfmpegDecoder.checkNative(result, "avio_closep");
        }
    }
}
