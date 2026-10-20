package club.heiqi.uilib.host.media.ffmpeg;

import club.heiqi.uilib.ui.media.MediaSource;
import club.heiqi.uilib.ui.media.MediaRange;
import club.heiqi.uilib.ui.media.MediaInfo;
import club.heiqi.uilib.ui.media.MediaBudget;
import club.heiqi.uilib.ui.media.spi.MediaCancellation;
import club.heiqi.uilib.ui.media.spi.MediaDecodeRequest;
import club.heiqi.uilib.ui.media.spi.MediaDecoderBuffering;
import club.heiqi.uilib.ui.media.spi.MediaDecoded;
import club.heiqi.uilib.ui.media.spi.MediaInputState;
import club.heiqi.uilib.ui.media.spi.MediaDecoder;


import static club.heiqi.uilib.host.media.NativeMediaResourceTracker.*;

import java.nio.ByteBuffer;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;
import static org.bytedeco.ffmpeg.global.swresample.*;

/** native 读取/解码/归一化，所有资源在创建线程释放。共同调度、代际和设备均不在这里。 */
final class FfmpegDecoder implements MediaDecoder {
    private final Thread owner=Thread.currentThread();
    private final MediaCancellation cancellation;
    private AVFormatContext input;
    private boolean ownsFormat;
    private HlsSourceContext sourceContext;
    private InitialPlaylistIo initialIo;
    private FfmpegInput openedInput;
    private AVCodecContext video,audio;
    private AVPacket packet;
    private AVFrame frame;
    private AVIOInterruptCB.Callback_Pointer callback;
    private SwsContext scale;
    private SwrContext resample;
    private BytePointer rgba,pcm;
    private PointerPointer<Pointer> rgbaPlanes,pcmPlanes;
    private IntPointer rgbaStride;
    private AVChannelLayout outputLayout;
    private int videoStream=-1,audioStream=-1,rgbaSize,pcmSize;
    private int inputRate,inputFormat=-1,inputChannels,outputChannels;
    private long originNanos,seekOriginNanos;
    private boolean hlsSeekClock,seekClockUnavailable;
    private long seekWindowStart;
    private int seekPlaylist=-1;
    private final java.util.ArrayDeque<AVPacket> observedPackets=new java.util.ArrayDeque<>();
    private long observedBytes;
    private boolean demuxEof,closed,videoFlushed,audioFlushed,videoDone,audioDone;
    private final java.util.ArrayDeque<Held> deferred=new java.util.ArrayDeque<>();
    private long deferredBytes,deferredNanos,lastAudioPacketEnd=-1,lastVideoPacketEnd=-1;
    private MediaInputState terminal=MediaInputState.UNKNOWN;
    private volatile MediaInfo info=MediaInfo.UNKNOWN;

    FfmpegDecoder(MediaSource source,MediaCancellation cancellation) throws Exception {
        this(source,cancellation,null,null,0);
    }
    FfmpegDecoder(MediaSource source,MediaCancellation cancellation,HlsSourceContext context,byte[] initial,int index) throws Exception {
        this(source,cancellation,context,initial,index,false);
    }
    FfmpegDecoder(MediaSource source,MediaCancellation cancellation,HlsSourceContext context,byte[] initial,int index,boolean sharedTimeline) throws Exception {
        this(source,cancellation,context,initial,index,sharedTimeline,false);
    }
    FfmpegDecoder(MediaSource source,MediaCancellation cancellation,HlsSourceContext context,byte[] initial,int index,boolean sharedTimeline,boolean seeking) throws Exception {
        this.cancellation=cancellation;this.sourceContext=context;
        try {
            cancellation.check();
            allocateInput();
            openInput(source,initial,index);
            discoverTracks(sharedTimeline,seeking);
            allocateFrames();
            publishInfo();
        } catch(Throwable error) {
            try { close(); } catch(Throwable next) { if (next!=error) error.addSuppressed(next); }
            rethrow(error);
        }
    }
    private void allocateInput(){
        input=avformat_alloc_context();
        if(input==null||input.isNull())throw new IllegalStateException("avformat_alloc_context returned null");
        acquired(FORMAT);ownsFormat=true;
        callback=new AVIOInterruptCB.Callback_Pointer(){
            @Override public int call(Pointer opaque){return cancellation.cancelled()?1:0;}
        };
        acquired(CALLBACK);input.interrupt_callback().callback(callback);
    }

    private void openInput(MediaSource source,byte[] initial,int index)throws Exception{
        AVDictionary options=new AVDictionary(null);
        try{
            String scheme=source.uri.getScheme();
            if(initial==null&&("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme))){
                openedInput=new FfmpegInput(source,input.interrupt_callback(),cancellation);
                initial=openedInput.playlist();
                if(initial==null)openedInput.attach(input);
                else if(sourceContext!=null)index=sourceContext.firstPlaylist(initial,cancellation);
            }
            configureInput(source,initial,index,options);
            // file URI保留来源身份；native文件API消费平台路径，保留空格与Unicode语义。
            String name="file".equalsIgnoreCase(source.uri.getScheme())?java.nio.file.Paths.get(source.uri).toString():source.uri.toString();
            int result=avformat_open_input(input,name,initial==null?null:av_find_input_format("hls"),options);
            // FFmpeg打开失败会自行释放format并清空指针，计数在真实释放边界同步。
            if(input.isNull()){released(FORMAT);ownsFormat=false;}
            check(result,"avformat_open_input");
        }finally{av_dict_free(options);}
    }

    static void configureOptions(MediaSource source,int index,AVDictionary options){
        checkNative(av_dict_set(options,"live_start_index",Integer.toString(index),0),"live_start_index");
        if(!source.headers.isEmpty()){
            StringBuilder headers=new StringBuilder();
            source.headers.forEach((name,value)->headers.append(name).append(": ").append(value).append("\r\n"));
            checkNative(av_dict_set(options,"headers",headers.toString(),0),"headers");
        }
        if("http".equalsIgnoreCase(source.uri.getScheme())||"https".equalsIgnoreCase(source.uri.getScheme()))
            checkNative(av_dict_set(options,"rw_timeout","10000000",0),"rw_timeout");
    }
    private void configureInput(MediaSource source,byte[] initial,int index,AVDictionary options){
        configureOptions(source,index,options);
        if(initial!=null){
            initialIo=new InitialPlaylistIo(initial,source,sourceContext,cancellation);initialIo.attach(input);
            // 每次正常reload通过同一io_open交回完整快照，避免HTTP keepalive在回调外复用响应。
            if(sourceContext!=null)check(av_dict_set(options,"http_persistent","0",0),"http_persistent");
            check(av_dict_set(options,"protocol_whitelist","http,https,tcp,tls,crypto",0),"protocol_whitelist");
        }
    }

    private void discoverTracks(boolean sharedTimeline,boolean seeking){
        org.bytedeco.ffmpeg.avformat.AVProgram selected=null;
        boolean bound=false;int clockPrimary=-1;
        if(seeking&&initialIo!=null&&sourceContext!=null){
            int primary=-1;
            for(int i=0;i<input.nb_streams();i++){
                int type=input.streams(i).codecpar().codec_type();
                if(type==AVMEDIA_TYPE_VIDEO){primary=i;break;}
                if(primary<0&&type==AVMEDIA_TYPE_AUDIO)primary=i;
            }
            if(primary<0)throw new IllegalStateException("HLS header没有可定位的实际轨道");
            try{selected=initialIo.selectPlaylist(input,primary);bound=true;}
            catch(Exception error){throw new IllegalStateException("HLS实际media事实绑定失败",error);}
            if(!sourceContext.liveMedia()){
                clockPrimary=primary;seekPlaylist=input.streams(primary).id();seekWindowStart=sourceContext.windowStartNanos();
                hlsSeekClock=true;seekOriginNanos=Long.MIN_VALUE;
                int[] discard=new int[input.nb_streams()];
                try{
                    // 只有本次物理seek的游标先读primary playlist；native first_timestamp与公开首packet DTS同源。
                    for(int i=0;i<discard.length;i++){discard[i]=input.streams(i).discard();input.streams(i).discard(input.streams(i).id()==seekPlaylist?AVDISCARD_DEFAULT:AVDISCARD_ALL);}
                    readSeekClock();
                }finally{for(int i=0;i<discard.length;i++)input.streams(i).discard(discard[i]);}
            }
        }
        check(avformat_find_stream_info(input,(PointerPointer)null),"avformat_find_stream_info");
        cancellation.check();
        for(int i=0;i<input.nb_streams();i++){
            int type=input.streams(i).codecpar().codec_type();
            if(type==AVMEDIA_TYPE_VIDEO&&videoStream<0)videoStream=i;
            if(type==AVMEDIA_TYPE_AUDIO&&audioStream<0)audioStream=i;
        }
        if(videoStream<0&&audioStream<0)throw new IllegalStateException("来源没有音视频轨道");
        if(clockPrimary>=0&&clockPrimary!=(videoStream>=0?videoStream:audioStream))throw new IllegalStateException("HLS seek实际轨道在find_stream_info后改变");
        try{if(initialIo!=null&&!bound)selected=initialIo.selectPlaylist(input,videoStream>=0?videoStream:audioStream);}
        catch(RuntimeException|Error error){throw error;}
        catch(Exception error){throw new IllegalStateException("HLS实际media事实绑定失败",error);}
        if(selected!=null&&videoStream>=0){
            audioStream=-1;
            for(int i=0;i<selected.nb_stream_indexes();i++){
                int stream=selected.stream_index().get(i);
                if(input.streams(stream).codecpar().codec_type()==AVMEDIA_TYPE_AUDIO){audioStream=stream;break;}
            }
        }
        // 共享时间线不能逐轨减去不同的首PTS；先绑定实际media，再建立同来源anchor。
        originNanos=sharedTimeline||input.start_time()==AV_NOPTS_VALUE?0:Math.multiplyExact(input.start_time(),1000L);
        if(selected!=null){
            long selectedStart=Math.min(trackStart(videoStream),trackStart(audioStream));
            if(selectedStart!=Long.MAX_VALUE)originNanos=selectedStart;
        }
        if(sourceContext!=null)originNanos=sourceContext.anchor(originNanos);
        if(!hlsSeekClock)seekOriginNanos=originNanos;
        if(selected!=null){
            // 普通join不额外读取packet；实际物理seek的时钟已在唯一primary活动时确认。
            long duration=sourceContext.playlistDurationNanos();input.duration(duration<0?AV_NOPTS_VALUE:duration/1000);
        }
        if(videoStream>=0)video=openCodec(videoStream);
        if(audioStream>=0)audio=openCodec(audioStream);
    }

    private long trackStart(int stream){
        if(stream<0||input.streams(stream).start_time()==AV_NOPTS_VALUE)return Long.MAX_VALUE;
        return nanos(input.streams(stream).start_time(),input.streams(stream).time_base());
    }
    private void allocateFrames(){
        packet=av_packet_alloc();
        if(packet==null||packet.isNull())throw new IllegalStateException("av_packet_alloc returned null");
        acquired(PACKET);frame=av_frame_alloc();
        if(frame==null||frame.isNull())throw new IllegalStateException("av_frame_alloc returned null");
        acquired(FRAME);
        rgbaPlanes=buffer(new PointerPointer<Pointer>(4));pcmPlanes=buffer(new PointerPointer<Pointer>(1));rgbaStride=buffer(new IntPointer(4));
    }

    private void publishInfo(){
        long duration=input.duration()==AV_NOPTS_VALUE||input.duration()<0?-1:Math.multiplyExact(input.duration(),1000L);
        info=new MediaInfo(true,video!=null,audio!=null,duration,duration<0?null:new MediaRange(0,duration),null,
                video==null?0:video.width(),video==null?0:video.height());
    }

    private AVCodecContext openCodec(int stream) {
        AVCodecParameters parameters=input.streams(stream).codecpar();
        AVCodec codec=avcodec_find_decoder(parameters.codec_id());
        if (codec==null || codec.isNull()) throw new IllegalStateException("avcodec_find_decoder codec="+parameters.codec_id());
        AVCodecContext context=avcodec_alloc_context3(codec);
        if(context==null || context.isNull())throw new IllegalStateException("avcodec_alloc_context3 returned null");
        acquired(CODEC);
        try {
            check(avcodec_parameters_to_context(context,parameters),"avcodec_parameters_to_context");
            check(avcodec_open2(context,codec,(AVDictionary)null),"avcodec_open2");
            return context;
        } catch(Throwable error) { avcodec_free_context(context); released(CODEC); throw error; }
    }
    @Override public MediaInfo info() { return sourceContext==null?info:sourceContext.facts(info); }
    @Override public MediaDecoded read() {
        MediaBudget budget=MediaBudget.DEFAULT;
        return read(new MediaDecodeRequest(true,true,budget.deferredBytes,budget.deferredPackets,budget.deferredNanos));
    }
    @Override public MediaDecoderBuffering buffering() { return new MediaDecoderBuffering(deferredBytes,deferred.size(),deferredNanos); }
    @Override public MediaInputState inputState(){return terminal;}
    private static long packetEnd(AVFormatContext context,AVPacket packet,long origin){
        if(packet.pts()==AV_NOPTS_VALUE)return -1;
        return nanos(packet.pts()+Math.max(0,packet.duration()),context.streams(packet.stream_index()).time_base())-origin;
    }
    @Override public MediaDecoded read(MediaDecodeRequest request) {
        owner(); cancellation.check(); av_frame_unref(frame);
        for (;;) {
            cancellation.check();
            MediaDecoded decoded = receive(request);
            if (decoded != null) return decoded;
            if ((video == null || videoDone) && (audio == null || audioDone)) return null;
            if (sendDeferred(request)) continue;
            if (demuxEof) {
                if (drain(request)) continue;
                return MediaDecoded.YIELD;
            }
            readPacket(request);
        }
    }

    /** 先消费codec已接收的数据，再读取下一packet，保留原有双轨背压顺序。 */
    private MediaDecoded receive(MediaDecodeRequest request) {
        if (request.video && video != null && !videoDone) {
            MediaDecoded decoded = receiveFrame(true);
            if (decoded != null) return decoded;
        }
        if (request.audio && audio != null && !audioDone) return receiveFrame(false);
        return null;
    }

    private MediaDecoded receiveFrame(boolean isVideo) {
        int result = avcodec_receive_frame(isVideo ? video : audio, frame);
        if (result >= 0) return convert(isVideo);
        if (result == AVERROR_EOF) {
            if (isVideo) videoDone = true; else audioDone = true;
        } else if (result != -11) check(result, "avcodec_receive_frame " + (isVideo ? "video" : "audio"));
        return null;
    }

    private boolean sendDeferred(MediaDecodeRequest request) {
        Held next = takeDeferred(request);
        if (next == null) return false;
        try { check(avcodec_send_packet(next.video ? video : audio, next.packet), "avcodec_send_packet deferred"); }
        finally { av_packet_free(next.packet); released(PACKET); }
        return true;
    }

    private Held takeDeferred(MediaDecodeRequest request) {
        java.util.Iterator<Held> iterator = deferred.iterator();
        while (iterator.hasNext()) {
            Held held = iterator.next();
            if (!(held.video ? request.video : request.audio)) continue;
            iterator.remove(); deferredBytes -= held.packet.size(); deferredNanos -= held.duration;
            return held;
        }
        return null;
    }

    private boolean drain(MediaDecodeRequest request) {
        if (request.video && video != null && !videoFlushed) {
            check(avcodec_send_packet(video, null), "avcodec_send_packet video drain"); videoFlushed = true; return true;
        }
        if (request.audio && audio != null && !audioFlushed) {
            check(avcodec_send_packet(audio, null), "avcodec_send_packet audio drain"); audioFlushed = true; return true;
        }
        return false;
    }

    private void readPacket(MediaDecodeRequest request) {
        int result;
        AVPacket observed=observedPackets.pollFirst();
        if(observed==null)result=av_read_frame(input,packet);
        else{observedBytes-=observed.size();av_packet_move_ref(packet,observed);av_packet_free(observed);released(PACKET);result=0;}
        cancellation.check(); // HLS中断可能以EOF传播，取消和同流捕获失败优先于终结事实。
        if(initialIo!=null)initialIo.checkFailure();
        if (result == AVERROR_EOF) {
            demuxEof = true; terminal = new MediaInputState(true, true, true, lastAudioPacketEnd, lastVideoPacketEnd);
            return;
        }
        check(result, "av_read_frame");
        observeSeekClock(packet);
        try { dispatchPacket(request); }
        finally { av_packet_unref(packet); }
    }

    private void dispatchPacket(MediaDecodeRequest request) {
        boolean isVideo = packet.stream_index() == videoStream;
        AVCodecContext target = isVideo ? video : packet.stream_index() == audioStream ? audio : null;
        if (target == null) return;
        long endpoint = packetEnd(input, packet, originNanos);
        if (isVideo) lastVideoPacketEnd = Math.max(lastVideoPacketEnd, endpoint);
        else lastAudioPacketEnd = Math.max(lastAudioPacketEnd, endpoint);
        if (isVideo ? request.video : request.audio) check(avcodec_send_packet(target, packet), "avcodec_send_packet");
        else deferPacket(isVideo, request);
    }

    private void deferPacket(boolean isVideo, MediaDecodeRequest request) {
        long duration = Math.max(0, nanos(packet.duration(), input.streams(packet.stream_index()).time_base()));
        if (deferredBytes + packet.size() > request.deferredBytes || deferred.size() >= request.deferredPackets
                || deferredNanos + duration > request.deferredNanos)
            throw new IllegalStateException("轨道交织超过压缩packet延后预算");
        AVPacket copy = av_packet_clone(packet);
        if (copy == null || copy.isNull()) throw new IllegalStateException("av_packet_clone returned null");
        acquired(PACKET);
        deferred.add(new Held(copy, isVideo, duration)); deferredBytes += packet.size(); deferredNanos += duration;
    }

    private MediaDecoded convert(boolean isVideo) {
        long timestamp = frame.best_effort_timestamp();
        if (timestamp == AV_NOPTS_VALUE) throw new IllegalStateException("解码帧没有可用PTS");
        int stream = isVideo ? videoStream : audioStream;
        long pts = nanos(timestamp, input.streams(stream).time_base()) - originNanos;
        return isVideo ? convertVideo(pts) : convertAudio(pts);
    }

    private MediaDecoded convertVideo(long pts) {
        int width = frame.width(), height = frame.height();
        AVRational sar = frame.sample_aspect_ratio();
        if (sar.num() > 0 && sar.den() > 0) width = Math.max(1, (int) Math.round(width * (double) sar.num() / sar.den()));
        long bytes = (long) width * height * 4;
        if (bytes > Integer.MAX_VALUE) throw new IllegalStateException("视频尺寸超过可寻址范围");
        configureScale(width, height);
        ensureRgba(width, (int) bytes);
        int rows = sws_scale(scale, frame.data(), frame.linesize(), 0, frame.height(), rgbaPlanes, rgbaStride);
        if (rows != height) throw new IllegalStateException("sws_scale rows=" + rows + " height=" + height);
        long duration = videoDuration();
        if (info.width != width || info.height != height) info = new MediaInfo(true, true, info.hasAudio,
                info.durationNanos, info.seekable, info.buffered, width, height);
        return MediaDecoded.video(pts, duration, width, height, rgba.position(0).limit(bytes).asByteBuffer());
    }

    private void configureScale(int width, int height) {
        boolean owned = scale != null && !scale.isNull();
        scale = sws_getCachedContext(scale, frame.width(), frame.height(), frame.format(), width, height, AV_PIX_FMT_RGBA,
                SWS_BILINEAR, null, null, (DoublePointer) null);
        boolean available = scale != null && !scale.isNull();
        if (owned && !available) released(CONVERSION);
        if (!owned && available) acquired(CONVERSION);
        if (!available) throw new IllegalStateException("sws_getCachedContext returned null");
        int colors = frame.colorspace();
        IntPointer table = sws_getCoefficients(colors == AVCOL_SPC_UNSPECIFIED ? SWS_CS_DEFAULT : colors);
        check(sws_setColorspaceDetails(scale, table, frame.color_range() == AVCOL_RANGE_JPEG ? 1 : 0, table, 1, 0, 1 << 16, 1 << 16), "sws_setColorspaceDetails");
    }

    private void ensureRgba(int width, int bytes) {
        if (rgba == null || rgbaSize != bytes) {
            if (rgba != null) { closeBuffer(rgba); rgba = null; }
            rgba = buffer(new BytePointer(bytes)); rgbaSize = bytes;
        }
        // 等面积变尺寸可复用buffer，但每帧行跨度必须随实际宽度刷新。
        rgbaPlanes.put(0, rgba); rgbaStride.put(0, width * 4);
    }

    private long videoDuration() {
        long duration = frame.duration() > 0 ? nanos(frame.duration(), input.streams(videoStream).time_base()) : 0;
        if (duration != 0) return duration;
        AVRational rate = av_guess_frame_rate(input, input.streams(videoStream), frame);
        return rate.num() > 0 && rate.den() > 0 ? 1_000_000_000L * rate.den() / rate.num() : 0;
    }

    private MediaDecoded convertAudio(long pts) {
        int rate = frame.sample_rate(), channels = frame.ch_layout().nb_channels();
        if (rate < 1 || channels < 1) throw new IllegalStateException("音频帧缺少采样格式");
        configureResample(rate, channels);
        int frames = Math.toIntExact(frame.nb_samples() + swr_get_delay(resample, rate) + 32);
        int bytes = Math.multiplyExact(Math.multiplyExact(frames, outputChannels), 2);
        ensurePcm(bytes);
        int produced = swr_convert(resample, pcmPlanes, frames, frame.extended_data(), frame.nb_samples());
        check(produced, "swr_convert");
        return MediaDecoded.audio(pts, rate, outputChannels, pcm.position(0).limit(produced * outputChannels * 2).asByteBuffer());
    }

    private void configureResample(int rate, int channels) {
        if (resample != null && inputRate == rate && inputFormat == frame.format() && inputChannels == channels) return;
        if (resample != null) { swr_free(resample); released(CONVERSION); resample = null; }
        if (outputLayout != null) { av_channel_layout_uninit(outputLayout); closeBuffer(outputLayout); outputLayout = null; }
        inputRate = rate; inputFormat = frame.format(); inputChannels = channels; outputChannels = Math.min(2, channels);
        outputLayout = buffer(new AVChannelLayout()); av_channel_layout_default(outputLayout, outputChannels);
        resample = new SwrContext(null);
        int allocated = swr_alloc_set_opts2(resample, outputLayout, AV_SAMPLE_FMT_S16, rate, frame.ch_layout(), frame.format(), rate, 0, null);
        if (!resample.isNull()) acquired(CONVERSION); else resample = null;
        check(allocated, "swr_alloc_set_opts2"); check(swr_init(resample), "swr_init");
    }

    private void ensurePcm(int bytes) {
        if (pcm != null && pcmSize >= bytes) return;
        if (pcm != null) { closeBuffer(pcm); pcm = null; }
        pcm = buffer(new BytePointer(bytes)); pcmSize = bytes; pcmPlanes.put(0, pcm);
    }
    @Override public void seek(long positionNanos) {
        owner(); cancellation.check();
        if(hlsSeekClock&&seekOriginNanos==Long.MIN_VALUE)readSeekClock();
        long timestamp=Math.addExact(seekOriginNanos,positionNanos)/1000;
        check(avformat_seek_file(input,-1,Long.MIN_VALUE,timestamp,timestamp,AVSEEK_FLAG_BACKWARD),"avformat_seek_file");
        if (video!=null) avcodec_flush_buffers(video);
        if (audio!=null) avcodec_flush_buffers(audio);
        if (resample!=null) { swr_free(resample); released(CONVERSION); resample=null; }
        clearObserved();clearDeferred(); demuxEof=videoFlushed=audioFlushed=videoDone=audioDone=false;
        lastAudioPacketEnd=lastVideoPacketEnd=-1;terminal=MediaInputState.UNKNOWN;
        av_packet_unref(packet); av_frame_unref(frame);
    }
    private void observeSeekClock(AVPacket value){
        if(!hlsSeekClock||seekClockUnavailable||seekOriginNanos!=Long.MIN_VALUE||input.streams(value.stream_index()).id()!=seekPlaylist)return;
        if(value.dts()==AV_NOPTS_VALUE){seekClockUnavailable=true;return;}
        // FFmpeg 6.1.1 hls_read_packet以首个成功packet DTS设置first_timestamp。
        // 此次seek初始化只启用primary playlist；公开首packet直接给出同一时钟，不从PTS或其他variant推断。
        seekOriginNanos=Math.subtractExact(Math.multiplyExact(av_rescale_q(value.dts(),input.streams(value.stream_index()).time_base(),av_get_time_base_q()),1000L),seekWindowStart);
    }
    private void readSeekClock(){
        MediaBudget budget=MediaBudget.DEFAULT;
        while(seekOriginNanos==Long.MIN_VALUE){
            if(seekClockUnavailable)throw new IllegalStateException("HLS首playlist packet缺少可证的DTS时钟");
            if(observedPackets.size()>=budget.deferredPackets||observedBytes>=budget.deferredBytes)throw new IllegalStateException("HLS seek时钟观测超过原packet预算");
            AVPacket next=av_packet_alloc();if(next==null||next.isNull())throw new IllegalStateException("av_packet_alloc返回空");acquired(PACKET);
            boolean retained=false;
            try{
                check(av_read_frame(input,next),"av_read_frame HLS seek clock");
                observedPackets.addLast(next);observedBytes=Math.addExact(observedBytes,next.size());retained=true;
                if(observedBytes>budget.deferredBytes)throw new IllegalStateException("HLS seek时钟观测超过原字节预算");
                observeSeekClock(next);
            }finally{if(!retained){av_packet_free(next);released(PACKET);}}
        }
    }
    private void clearObserved(){
        for(AVPacket next;(next=observedPackets.pollFirst())!=null;){av_packet_free(next);released(PACKET);}observedBytes=0;
    }
    private static long nanos(long value,AVRational base) {
        return Math.round(value*(double)base.num()*1_000_000_000d/base.den());
    }
    private void owner() {
        if (Thread.currentThread()!=owner) throw new IllegalStateException("FFmpeg线程归属错误");
        if (closed) throw new IllegalStateException("FFmpeg decoder已关闭");
    }
    private void check(int result,String stage) {
        cancellation.check();
        if(initialIo!=null)initialIo.checkFailure();
        checkNative(result,stage);
    }
    static void checkNative(int result,String stage) {
        if (result<0) {
            byte[] text=new byte[256]; av_strerror(result,text,text.length);
            int length=0; while(length<text.length && text[length]!=0) length++;
            throw new IllegalStateException(stage+"="+result+" "+new String(text,0,length,java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    @Override public void close() throws Exception {
        if (closed) return;
        if (Thread.currentThread()!=owner) throw new IllegalStateException("FFmpeg释放线程错误");
        closed=true; Throwable failure=null;
        // 每项仍单独尝试，部分分配/释放失败不会跳过后续资源；顺序与原owner保持一致。
        AutoCloseable[] releases={
            this::clearObserved,this::clearDeferred,this::releaseFrame,this::releasePacket,
            ()->releaseCodec(video),()->releaseCodec(audio),this::releaseScale,this::releaseResample,
            ()->releaseBuffer(rgba),()->releaseBuffer(pcm),()->releaseBuffer(rgbaPlanes),()->releaseBuffer(pcmPlanes),
            ()->releaseBuffer(rgbaStride),this::releaseOutputLayout,this::releaseInput,this::releaseInitialIo,()->{if(openedInput!=null)openedInput.close();},this::releaseCallback
        };
        for(AutoCloseable release:releases)try{release.close();}catch(Throwable next){
            if(failure==null)failure=next;else if(failure!=next)failure.addSuppressed(next);
        }
        if(failure!=null)rethrow(failure);
    }
    private void releaseFrame(){
        if(frame!=null&&!frame.isNull()){av_frame_free(frame);released(FRAME);}
    }
    private void releasePacket(){
        if(packet!=null&&!packet.isNull()){av_packet_free(packet);released(PACKET);}
    }
    private void releaseCodec(AVCodecContext codec){
        if(codec!=null){avcodec_free_context(codec);released(CODEC);}
    }
    private void releaseScale(){
        if(scale!=null&&!scale.isNull()){sws_freeContext(scale);released(CONVERSION);}
    }
    private void releaseResample(){
        if(resample!=null){swr_free(resample);released(CONVERSION);}
    }
    private static void releaseBuffer(Pointer buffer){if(buffer!=null)closeBuffer(buffer);}
    private void releaseOutputLayout(){
        if(outputLayout!=null){av_channel_layout_uninit(outputLayout);closeBuffer(outputLayout);}
    }
    private void releaseInput(){
        if(ownsFormat&&input!=null&&!input.isNull()){avformat_close_input(input);released(FORMAT);ownsFormat=false;}
    }
    private void releaseInitialIo()throws Exception{if(initialIo!=null)initialIo.close();}
    private void releaseCallback(){if(callback!=null){callback.close();released(CALLBACK);}}
    private void clearDeferred() {
        while(!deferred.isEmpty()) { av_packet_free(deferred.remove().packet); released(PACKET); }
        deferredBytes=deferredNanos=0;
    }
    private static <T extends Pointer> T buffer(T value) {
        if(value==null || value.isNull())throw new IllegalStateException("媒体native buffer分配失败");
        acquired(BUFFER);return value;
    }
    private static void closeBuffer(Pointer value) { value.close(); released(BUFFER); }
    private static final class Held {
        final AVPacket packet; final boolean video; final long duration;
        Held(AVPacket packet,boolean video,long duration) {this.packet=packet;this.video=video;this.duration=duration;}
    }
    private static void rethrow(Throwable error) throws Exception {
        if (error instanceof Error) throw (Error)error;
        if (error instanceof Exception) throw (Exception)error;
        throw new RuntimeException(error);
    }
}
