package club.heiqi.uilib.host.media.ffmpeg;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import club.heiqi.uilib.ui.media.MediaSource;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static club.heiqi.uilib.host.media.NativeMediaResourceTracker.*;

/** 有界初始列表。AVIO buffer和回调按实际创建/释放计数，后续reload仍用真实URL。 */
final class InitialPlaylistIo implements AutoCloseable {
    private AVIOContext io;
    private BytePointer allocated;
    private boolean bufferOwned;
    private Read_packet_Pointer_BytePointer_int reader;
    private int offset;
    private AVFormatContext.Io_open_AVFormatContext_PointerPointer_BytePointer_int_PointerPointer opener;
    InitialPlaylistIo(final byte[] bytes,final MediaSource source) {
        try {
            reader=new Read_packet_Pointer_BytePointer_int() {
                @Override public int call(Pointer opaque,BytePointer target,int size) {
                    if(offset==bytes.length)return AVERROR_EOF;
                    int count=Math.min(size,bytes.length-offset);target.put(bytes,offset,count);offset+=count;return count;
                }
            };
            acquired(CALLBACK);
            // 自定义首列表没有HTTP协议的AVOptions；片段/子列表仍须带原来源请求参数。
            opener=new AVFormatContext.Io_open_AVFormatContext_PointerPointer_BytePointer_int_PointerPointer(){
                @Override public int call(AVFormatContext context,PointerPointer target,BytePointer url,int flags,PointerPointer options){
                    AVDictionary values=new AVDictionary(options.get(0));
                    FfmpegDecoder.configureOptions(source,0,values);options.put(values);
                    return avio_open2(target,url,flags,context.interrupt_callback(),options);
                }
            };
            acquired(CALLBACK);
            allocated=new BytePointer(av_malloc(4096));
            if(allocated.isNull())throw new IllegalStateException("av_malloc返回空");
            acquired(BUFFER);bufferOwned=true;
            io=avio_alloc_context(allocated,4096,0,null,reader,null,null);
            if(io==null||io.isNull()){io=null;throw new IllegalStateException("avio_alloc_context返回空");}
            acquired(AVIO);allocated=null;
        } catch(RuntimeException | Error failure) {
            try {close();}catch(Throwable next){if(next!=failure)failure.addSuppressed(next);}
            throw failure;
        }
    }
    void attach(AVFormatContext context) { context.pb(io);context.flags(context.flags()|AVFormatContext.AVFMT_FLAG_CUSTOM_IO);context.io_open(opener); }
    @Override public void close() {
        Throwable failure=null;
        if(bufferOwned)try {
            av_free(io==null?allocated:io.buffer());released(BUFFER);bufferOwned=false;allocated=null;
            if(io!=null)io.buffer((BytePointer)null);
        }catch(Throwable next){failure=next;}
        if(io!=null)try{avio_context_free(io);released(AVIO);io=null;}catch(Throwable next){failure=combine(failure,next);}
        if(reader!=null)try{reader.close();released(CALLBACK);reader=null;}catch(Throwable next){failure=combine(failure,next);}
        if(opener!=null)try{opener.close();released(CALLBACK);opener=null;}catch(Throwable next){failure=combine(failure,next);}
        if(failure instanceof Error)throw(Error)failure;
        if(failure instanceof RuntimeException)throw(RuntimeException)failure;
    }
    private static Throwable combine(Throwable first,Throwable next){if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;}
}
