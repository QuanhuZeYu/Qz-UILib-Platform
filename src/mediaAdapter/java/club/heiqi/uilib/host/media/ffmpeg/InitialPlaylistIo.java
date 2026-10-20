package club.heiqi.uilib.host.media.ffmpeg;

import club.heiqi.uilib.ui.media.MediaSource;
import club.heiqi.uilib.ui.media.spi.MediaCancellation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVProgram;
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static club.heiqi.uilib.host.media.NativeMediaResourceTracker.*;

/** 有界首列表及同流media快照；原生子输入仍由FFmpeg打开、读取和关闭。 */
final class InitialPlaylistIo implements AutoCloseable {
    private AVIOContext io;
    private BytePointer allocated;
    private boolean bufferOwned;
    private Read_packet_Pointer_BytePointer_int reader;
    private int offset;
    private AVFormatContext.Io_open_AVFormatContext_PointerPointer_BytePointer_int_PointerPointer opener;
    private AVFormatContext.Io_close2_AVFormatContext_AVIOContext closer;
    private final Map<Long,Capture> captures=new HashMap<>();
    private final Map<Long,InitialPlaylistIo> cached=new HashMap<>();
    private final Map<URI,byte[]> playlists=new HashMap<>();
    private final java.util.List<HlsSourceContext.SegmentInput> openedSegments=new java.util.ArrayList<>();
    private HlsSourceContext sourceContext;
    private MediaCancellation cancellation;
    private URI selectedUri;
    private boolean selected;
    private int capturedBytes;
    private Throwable failure,releaseFailure;

    InitialPlaylistIo(byte[] bytes,MediaSource source,HlsSourceContext context,MediaCancellation cancellation) {
        this(bytes);this.sourceContext=context;this.cancellation=cancellation;
        try {
            // 自定义首列表没有HTTP协议AVOptions，子列表/片段继续使用所属来源的请求头。
            opener=new AVFormatContext.Io_open_AVFormatContext_PointerPointer_BytePointer_int_PointerPointer(){
                @Override public int call(AVFormatContext format,PointerPointer target,BytePointer url,int flags,PointerPointer options){
                    try {
                        cancellation.check();URI uri=URI.create(url.getString());
                        byte[] snapshot=context==null||selected?null:context.cachedPlaylist(uri);
                        if(snapshot!=null){
                            InitialPlaylistIo child=new InitialPlaylistIo(snapshot);cached.put(child.io.address(),child);
                            playlists.put(uri,snapshot);target.put(child.io);return 0;
                        }
                        AVDictionary values=new AVDictionary(options.get(0));
                        FfmpegDecoder.configureOptions(source,0,values);options.put(values);
                        // avio_open2会消费这些公开AVOptions；BYTERANGE身份必须在打开前与URI一起保存。
                        long start=option(values,"offset",0),end=option(values,"end_offset",-1);
                        int result=avio_open2(target,url,flags,format.interrupt_callback(),options);
                        if(result>=0&&!selected)openedSegments.add(new HlsSourceContext.SegmentInput(uri,start,end));
                        if(result>=0&&context!=null&&(selected?uri.equals(selectedUri):context.observesPlaylist(uri))){
                            AVIOContext opened=new AVIOContext(target.get(0));
                            Capture capture=new Capture(opened,uri);captures.put(opened.address(),capture);
                        }
                        return result;
                    }catch(Throwable error){failure=combine(failure,error);return AVERROR_EXTERNAL;}
                }
            };
            acquired(CALLBACK);
            closer=new AVFormatContext.Io_close2_AVFormatContext_AVIOContext(){
                @Override public int call(AVFormatContext format,AVIOContext opened){
                    try {
                        InitialPlaylistIo child=cached.remove(opened.address());
                        if(child!=null){child.close();return 0;}
                        Capture capture=captures.remove(opened.address());
                        try{if(capture!=null)capture.close();}catch(Throwable error){releaseFailure=combine(releaseFailure,error);}
                        int result=avio_closep(opened);FfmpegDecoder.checkNative(result,"avio_closep HLS child");return result;
                    }catch(Throwable error){releaseFailure=combine(releaseFailure,error);return AVERROR_EXTERNAL;}
                }
            };
            acquired(CALLBACK);
        }catch(RuntimeException|Error error){try{close();}catch(Throwable next){error.addSuppressed(next);}throw error;}
    }
    private InitialPlaylistIo(final byte[] bytes) {
        try {
            reader=new Read_packet_Pointer_BytePointer_int() {
                @Override public int call(Pointer opaque,BytePointer target,int size) {
                    if(offset==bytes.length)return AVERROR_EOF;
                    int count=Math.min(size,bytes.length-offset);target.put(bytes,offset,count);offset+=count;return count;
                }
            };
            acquired(CALLBACK);
            allocated=new BytePointer(av_malloc(4096));
            if(allocated.isNull())throw new IllegalStateException("av_malloc返回空");
            acquired(BUFFER);bufferOwned=true;
            io=avio_alloc_context(allocated,4096,0,null,reader,null,null);
            if(io==null||io.isNull()){io=null;throw new IllegalStateException("avio_alloc_context返回空");}
            acquired(AVIO);allocated=null;
        }catch(RuntimeException|Error error){try{close();}catch(Throwable next){error.addSuppressed(next);}throw error;}
    }
    void attach(AVFormatContext context) {
        context.pb(io);context.flags(context.flags()|AVFormatContext.AVFMT_FLAG_CUSTOM_IO);
        context.io_open(opener);context.io_close2(closer);
    }
    AVProgram selectPlaylist(AVFormatContext format,int stream)throws Exception {
        checkFailure();AVProgram program=null;
        if(sourceContext!=null){
            if(sourceContext.hasMaster()){
                // FFmpeg 6.1.1 hls.c以声明的variant索引创建公开AVProgram，实际stream经add_stream_to_programs归属。
                // 只沿已选stream查program；io_open会读取多个变体，其请求先后不能表示播放选择。
                program=av_find_program_from_stream(format,(AVProgram)null,stream);
                if(program==null||program.isNull())throw new IOException("FFmpeg HLS已选轨道缺少program归属");
                org.bytedeco.ffmpeg.avutil.AVDictionaryEntry bitrate=av_dict_get(program.metadata(),"variant_bitrate",null,0);
                if(bitrate==null||bitrate.isNull())throw new IOException("FFmpeg HLS program缺少variant_bitrate");
                selectedUri=sourceContext.variantUri(program.id(),Long.parseLong(bitrate.value().getString()));
                byte[] bytes=playlists.get(selectedUri);
                if(bytes==null)throw new IOException("FFmpeg HLS已选media缺少同流快照: "+selectedUri);
                sourceContext.selectedPlaylist(selectedUri,bytes,cancellation);
            }else selectedUri=sourceContext.mediaUri();
        }
        if(sourceContext!=null)sourceContext.openedSegments(openedSegments);
        selected=true;playlists.clear();openedSegments.clear();capturedBytes=0;return program;
    }
    private static long option(AVDictionary values,String key,long absent){
        org.bytedeco.ffmpeg.avutil.AVDictionaryEntry value=av_dict_get(values,key,null,0);
        return value==null||value.isNull()?absent:Long.parseLong(value.value().getString());
    }
    void checkFailure(){
        if(failure instanceof Error)throw(Error)failure;
        if(failure instanceof RuntimeException)throw(RuntimeException)failure;
        if(failure!=null)throw new IllegalStateException("HLS子列表同流读取失败",failure);
        if(releaseFailure!=null)throw new IllegalStateException("HLS子输入释放失败",releaseFailure);
    }
    private final class Capture implements AutoCloseable {
        private final AVIOContext opened;
        private final AVIOContext.Read_packet_Pointer_BytePointer_int original;
        private final AVIOContext.Read_packet_Pointer_BytePointer_int observer;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private boolean complete;
        Capture(AVIOContext opened,URI uri){
            this.opened=opened;original=opened.read_packet();
            observer=new AVIOContext.Read_packet_Pointer_BytePointer_int(){
                @Override public int call(Pointer opaque,BytePointer target,int size){
                    int count=original.call(opaque,target,size);
                    try {
                        cancellation.check();
                        if(count>0){
                            if(bytes.size()+count>HlsSourceContext.MAX_BYTES||(!selected&&capturedBytes+count>HlsSourceContext.MAX_BYTES))
                                throw new IOException("HLS子列表字节超过预算: "+uri);
                            byte[] chunk=new byte[count];target.get(chunk,0,count);bytes.write(chunk,0,count);
                            if(!selected)capturedBytes+=count;
                        }else if(!complete&&(count==0||count==AVERROR_EOF)){
                            complete=true;byte[] snapshot=bytes.toByteArray();
                            if(selected)sourceContext.selectedPlaylist(uri,snapshot,cancellation);else playlists.put(uri,snapshot);
                        }
                        return count;
                    }catch(Throwable error){failure=combine(failure,error);return AVERROR_EXTERNAL;}
                }
            };
            acquired(CALLBACK);opened.read_packet(observer);
        }
        @Override public void close(){opened.read_packet(original);observer.close();released(CALLBACK);}
    }
    @Override public void close() {
        Throwable cleanup=releaseFailure;
        for(Capture capture:captures.values())try{capture.close();}catch(Throwable next){cleanup=combine(cleanup,next);}
        captures.clear();
        for(InitialPlaylistIo child:cached.values())try{child.close();}catch(Throwable next){cleanup=combine(cleanup,next);}
        cached.clear();playlists.clear();openedSegments.clear();
        if(bufferOwned)try {
            av_free(io==null?allocated:io.buffer());released(BUFFER);bufferOwned=false;allocated=null;
            if(io!=null)io.buffer((BytePointer)null);
        }catch(Throwable next){cleanup=combine(cleanup,next);}
        if(io!=null)try{avio_context_free(io);released(AVIO);io=null;}catch(Throwable next){cleanup=combine(cleanup,next);}
        if(reader!=null)try{reader.close();released(CALLBACK);reader=null;}catch(Throwable next){cleanup=combine(cleanup,next);}
        if(opener!=null)try{opener.close();released(CALLBACK);opener=null;}catch(Throwable next){cleanup=combine(cleanup,next);}
        if(closer!=null)try{closer.close();released(CALLBACK);closer=null;}catch(Throwable next){cleanup=combine(cleanup,next);}
        if(cleanup instanceof Error)throw(Error)cleanup;
        if(cleanup instanceof RuntimeException)throw(RuntimeException)cleanup;
        if(cleanup!=null)throw new IllegalStateException("HLS输入释放失败",cleanup);
    }
    private static Throwable combine(Throwable first,Throwable next){if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;}
}
