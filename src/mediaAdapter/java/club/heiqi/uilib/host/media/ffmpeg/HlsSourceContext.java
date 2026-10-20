package club.heiqi.uilib.host.media.ffmpeg;

import club.heiqi.uilib.ui.media.MediaSource;
import club.heiqi.uilib.ui.media.MediaRange;
import club.heiqi.uilib.ui.media.MediaInfo;
import club.heiqi.uilib.ui.media.spi.MediaCancellation;
import club.heiqi.uilib.ui.media.spi.MediaDecoder;
import club.heiqi.uilib.ui.media.spi.MediaDecoderSource;



import io.lindstrom.m3u8.model.MediaPlaylist;
import io.lindstrom.m3u8.parser.MasterPlaylistParser;
import io.lindstrom.m3u8.model.MediaSegment;
import io.lindstrom.m3u8.parser.MediaPlaylistParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 单个会话/来源的有限列表与媒体锚点；首次事实来自decoder的同一输入，刷新仍归本owner。 */
final class HlsSourceContext implements MediaDecoderSource {
    static final int MAX_BYTES=262144;
    private static final int MAX_SEGMENTS=2048,MAX_URL=8192;
    private final MediaSource source;
    private final MediaCancellation lifetime;
    private Snapshot window;
    private byte[] master;
    private List<URI> variants;
    private List<Long> variantBitrates;
    private long windowStart,openingPosition,requestPosition,origin=Long.MIN_VALUE,deadline=Long.MAX_VALUE;
    private boolean requestSeeking;
    private boolean closed;
    private boolean liveTimeline;
    HlsSourceContext(MediaSource source,MediaCancellation lifetime) {
        this.source=source;this.lifetime=lifetime;
    }
    int firstPlaylist(byte[] bytes,MediaCancellation cancellation)throws Exception {
        check(cancellation);
        String text=new String(bytes,StandardCharsets.UTF_8);
        // 同一根响应只记录声明归属；最终使用哪一项由FFmpeg实际消费的stream所属program决定。
        if(text.contains("\n#EXT-X-STREAM-INF:")||text.contains("\n#EXT-X-I-FRAME-STREAM-INF:")){
            variants=new ArrayList<>();variantBitrates=new ArrayList<>();
            new MasterPlaylistParser().readPlaylist(text).variants().forEach(variant->{variants.add(source.uri.resolve(variant.uri()));variantBitrates.add(variant.bandwidth());});
            if(variants.isEmpty()||variants.size()>MAX_SEGMENTS)throw new IOException("HLS变体数为空或超过预算");
            master=bytes;return -3;
        }
        window=parse(source.uri,bytes,cancellation);liveTimeline=window.live;
        return liveTimeline?selectOpening(requestPosition,requestSeeking):0;
    }
    boolean hasMaster(){return master!=null;}
    boolean liveMedia(){return window!=null&&window.live;}
    URI mediaUri(){return window==null?null:window.uri;}
    long windowStartNanos(){return windowStart;}
    long playlistDurationNanos(){return window.live?-1:Math.subtractExact(Math.addExact(range().endNanos,1),windowStart);}
    URI variantUri(int program,long bitrate)throws IOException{
        if(program<0||program>=variants.size())throw new IOException("FFmpeg HLS program不属于当前Master: "+program);
        if(variantBitrates.get(program)!=bitrate)throw new IOException("FFmpeg HLS program码率与Master声明不符: "+program);
        return variants.get(program);
    }
    boolean observesPlaylist(URI uri){return variants==null?window!=null&&window.uri.equals(uri):variants.contains(uri);}
    byte[] cachedPlaylist(URI uri){return window!=null&&window.uri.equals(uri)?window.bytes:null;}
    void selectedPlaylist(URI uri,byte[] bytes,MediaCancellation cancellation)throws Exception{
        check(cancellation);
        Snapshot next=parse(uri,bytes,cancellation);
        if(window==null){window=next;liveTimeline=next.live;}
        else update(next);
    }
    void openedSegments(java.util.List<SegmentInput> opened)throws IOException{
        if(!liveTimeline)return;
        long start=windowStart;
        // FFmpeg可能按其他live列表同步而推进起播片段；只有实际IO URI可成为PTS锚点事实。
        for(int i=0;i<window.uris.length;i++){
            for(SegmentInput input:opened)if(input.matches(window.uris[i],window.offsets[i],window.ends[i])){openingPosition=start;return;}
            start=Math.addExact(start,window.durations[i]);
        }
        throw new IOException("FFmpeg实际起播片段不在所选media快照内");
    }
    @Override public MediaDecoder open(long position,boolean seeking,MediaCancellation operation) throws Exception {
        check(operation);
        final MediaCancellation request=operation;
        MediaCancellation active=()->lifetime.cancelled()||request.cancelled();
        requestPosition=position;requestSeeking=seeking;
        if(!liveTimeline)return openVod(position,seeking,active);
        if(origin!=Long.MIN_VALUE&&window.live)update(active);
        int index=selectOpening(position,seeking);
        // ENDLIST只改变正常结束事实；同来源cursor仍用原origin，不能退回独立VOD零点。
        FfmpegDecoder decoder=new FfmpegDecoder(source,active,this,master==null?window.bytes:master,window.live?index:0,false,!window.live&&seeking);
        try{if(!window.live&&seeking)decoder.seek(position);return decoder;}
        catch(Throwable error){try{decoder.close();}catch(Throwable next){error.addSuppressed(next);}if(error instanceof Error)throw(Error)error;if(error instanceof Exception)throw(Exception)error;throw new RuntimeException(error);}
    }
    private int selectOpening(long position,boolean seeking){
        // 此处只表达请求起点；实际openingPosition在同流IO打开片段后确认。
        int index=seeking?0:Math.max(window.durations.length-3,0);
        if(seeking) {
            MediaRange range=range();
            if(range==null||!range.contains(position))throw new IllegalArgumentException("live seek 目标已退出当前窗口: "+position);
            long start=windowStart;
            while(index<window.durations.length&&position>=start+window.durations[index])start+=window.durations[index++];
            if(index==window.durations.length)throw new IllegalArgumentException("live窗口末端没有可解码片段");
        }
        openingPosition=windowStart;for(int i=0;i<index;i++)openingPosition=Math.addExact(openingPosition,window.durations[i]);
        return index;
    }
    private MediaDecoder openVod(long position,boolean seeking,MediaCancellation operation) throws Exception {
        FfmpegDecoder decoder=window==null?new FfmpegDecoder(source,operation,this,null,0,false,seeking&&position!=0)
                :new FfmpegDecoder(source,operation,this,master==null?window.bytes:master,0,false,seeking&&position!=0);
        // 新建VOD cursor已位于首包；0应保留首音频。HLS首DTS可晚于format.start_time，
        // 再物理seek到0会被demuxer拒绝，钳到首视频又会丢失合法的前导音频。
        try{
            // 首次Master需先由真实轨道确定media；显式定位再用同一快照选择片段，不重读首列表。
            if(master!=null&&liveTimeline&&seeking){decoder.close();int index=selectOpening(position,true);decoder=new FfmpegDecoder(source,operation,this,master,index);}
            else if(!liveTimeline&&seeking&&position!=0)decoder.seek(position);
            return decoder;
        }
        catch(Throwable error){try{decoder.close();}catch(Throwable next){error.addSuppressed(next);}if(error instanceof Error)throw(Error)error;if(error instanceof Exception)throw(Exception)error;throw new RuntimeException(error);}
    }
    long anchor(long detected) {
        if(!liveTimeline)return detected;
        if(origin==Long.MIN_VALUE)origin=Math.subtractExact(detected,openingPosition);
        return origin;
    }
    MediaInfo facts(MediaInfo decoded) {
        if(!liveTimeline||origin==Long.MIN_VALUE)return decoded;
        MediaRange range=range();
        long duration=window.live?-1:Math.addExact(range.endNanos,1);
        return new MediaInfo(decoded.ready,decoded.hasVideo,decoded.hasAudio,duration,range,decoded.buffered,decoded.width,decoded.height);
    }
    private MediaRange range() {
        if(window==null)return null;
        long end=windowStart;
        for(long duration:window.durations)end=Math.addExact(end,duration);
        return end<=windowStart?null:new MediaRange(windowStart,end-1);
    }
    @Override public long initialPositionNanos(){return openingPosition;}
    @Override public long refreshDeadlineNanos(){return deadline;}
    @Override public MediaInfo refresh(MediaInfo current,MediaCancellation cancellation) throws Exception {
        check(cancellation);
        if(window!=null&&window.live&&System.nanoTime()>=deadline)update(cancellation);
        return facts(current);
    }
    private void update(MediaCancellation cancellation) throws Exception {
        update(load(cancellation));
    }
    private void update(Snapshot next)throws IOException{
        if(next.sequence<window.sequence)throw new IOException("HLS MEDIA-SEQUENCE倒退");
        long offset=next.sequence-window.sequence;
        if(offset>window.durations.length)throw new IOException("HLS刷新无重叠，缺少真实时长锚点");
        if(offset<window.durations.length) {
            int overlap=(int)offset;
            if(!window.uris[overlap].equals(next.uris[0])||window.offsets[overlap]!=next.offsets[0]||window.ends[overlap]!=next.ends[0]||window.discontinuities[overlap]!=next.discontinuities[0])
                throw new IOException("HLS重叠片段身份/PTS epoch改变");
        }
        for(int i=0;i<offset;i++)windowStart=Math.addExact(windowStart,window.durations[i]);
        window=next;
    }
    private Snapshot load(MediaCancellation cancellation) throws Exception {
        check(cancellation);
        if(window.uri.toString().length()>MAX_URL)throw new IOException("媒体URL超过预算");
        HttpURLConnection connection=(HttpURLConnection)window.uri.toURL().openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(5000);connection.setRequestProperty("Cache-Control","no-cache");
        source.headers.forEach(connection::setRequestProperty);
        byte[] bytes;
        try {
            int status=connection.getResponseCode();
            if(status<200||status>=300) {
                IOException cause;
                try(InputStream rejected=connection.getInputStream()){cause=new IOException(connection.getResponseMessage());}
                catch(IOException original){cause=original;}
                throw new IOException("HLS manifest HTTP "+status+" source="+window.uri,cause);
            }
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int count;
                while((count=input.read(buffer))>=0){check(cancellation);if(output.size()+count>MAX_BYTES)throw new IOException("HLS列表字节超过预算");output.write(buffer,0,count);}
                bytes=output.toByteArray();
            }
        }finally{connection.disconnect();}
        return parse(window.uri,bytes,cancellation);
    }
    private Snapshot parse(URI uri,byte[] bytes,MediaCancellation cancellation)throws Exception {
        if(uri.toString().length()>MAX_URL)throw new IOException("媒体URL超过预算");
        MediaPlaylist parsed=new MediaPlaylistParser().readPlaylist(new String(bytes,StandardCharsets.UTF_8));
        List<MediaSegment> segments=parsed.mediaSegments();
        if(segments.isEmpty()||segments.size()>MAX_SEGMENTS)throw new IOException("HLS片段数为空或超过预算");
        long[] durations=new long[segments.size()],epochs=new long[segments.size()],offsets=new long[segments.size()],ends=new long[segments.size()];String[] uris=new String[segments.size()];
        long previousEnd=0;
        long epoch=parsed.discontinuitySequence();
        for(int i=0;i<segments.size();i++){
            MediaSegment segment=segments.get(i);double value=segment.duration();
            if(!Double.isFinite(value)||value<=0||value>86400)throw new IOException("无效EXTINF");
            durations[i]=Math.round(value*1e9);
            if(segment.discontinuity())epoch++;
            epochs[i]=epoch;uris[i]=uri.resolve(segment.uri()).toString();
            if(uris[i].length()>MAX_URL)throw new IOException("片段URL超过预算");
            if(segment.byteRange().isPresent()){
                io.lindstrom.m3u8.model.ByteRange range=segment.byteRange().get();
                offsets[i]=range.offset().orElse(previousEnd);ends[i]=Math.addExact(offsets[i],range.length());
                if(offsets[i]<0||range.length()<=0)throw new IOException("无效HLS BYTERANGE");
                previousEnd=ends[i];
            }else{ends[i]=-1;previousEnd=0;}
            if(epoch!=parsed.discontinuitySequence())throw new IOException("首期来源出现未映射的PTS discontinuity");
        }
        deadline=parsed.ongoing()?System.nanoTime()+Math.max(1,parsed.targetDuration())*1_000_000_000L:Long.MAX_VALUE;
        check(cancellation);return new Snapshot(uri,bytes,parsed.mediaSequence(),parsed.ongoing(),durations,uris,epochs,offsets,ends);
    }
    private void check(MediaCancellation cancellation){if(closed)throw new IllegalStateException("来源上下文已关闭");lifetime.check();cancellation.check();}
    @Override public void close(){closed=true;window=null;master=null;variants=null;variantBitrates=null;deadline=Long.MAX_VALUE;}
    static final class SegmentInput {
        final URI uri;final long offset,end;
        SegmentInput(URI uri,long offset,long end){this.uri=uri;this.offset=offset;this.end=end;}
        boolean matches(String url,long expectedOffset,long expectedEnd){return uri.toString().equals(url)&&offset==expectedOffset&&end==expectedEnd;}
    }
    private static final class Snapshot {
        final URI uri;final byte[] bytes;final long sequence;final boolean live;final long[] durations,discontinuities,offsets,ends;final String[] uris;
        Snapshot(URI uri,byte[] bytes,long sequence,boolean live,long[] durations,String[] uris,long[] discontinuities,long[] offsets,long[] ends){this.uri=uri;this.bytes=bytes;this.sequence=sequence;this.live=live;this.durations=durations;this.uris=uris;this.discontinuities=discontinuities;this.offsets=offsets;this.ends=ends;}
    }
}
