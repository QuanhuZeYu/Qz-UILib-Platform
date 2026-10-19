package club.heiqi.uilib.testing.media;

import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.host.media.IsolatedMediaAdapters;
import club.heiqi.uilib.ui.media.MediaSource;
import club.heiqi.uilib.ui.media.spi.MediaDecoder;
import club.heiqi.uilib.ui.media.spi.MediaDecoderSource;
import club.heiqi.uilib.ui.media.spi.MediaDecoded;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 独立输入、必需HTTP头和EOF尾部的真实FFmpeg回归。 */
public final class MediaNetworkScenario extends TestApplication {
    public static void main(String[] args)throws Exception{new MediaNetworkScenario().run(args);}
    protected void runTests()throws Exception{
        long resourcesBefore=IsolatedMediaAdapters.resources().activeHandles();
        scenario("无后缀固定Master沿原HLS链读取首帧并保留请求头",()->{
            long before=IsolatedMediaAdapters.resources().activeHandles();
            try(MediaNetworkFixture fixture=new MediaNetworkFixture()){
                try(MediaDecoderSource source=IsolatedMediaAdapters.decoderFactory().openSource(fixture.source("/entry"),()->false);
                        MediaDecoder decoder=source.open(0,false,()->false)){
                    require(video(decoder)!=null,"固定Master未读取真实首视频帧");
                    require(fixture.requests("/entry")==1&&fixture.requests("/vod.m3u8")==1&&fixture.heads.get()==0,"Master或Media首列表出现重复预请求");
                    require(fixture.rejected.get()==0,"Master子列表或片段丢失请求头");
                    System.out.println("MASTER_INPUT_REQUESTS "+fixture.requestLog);
                }
            }
            require(IsolatedMediaAdapters.resources().activeHandles()==before,"Master链关闭后native资源未归还");
        });
        Throwable inputFailure=null;
        for(String path:new String[]{"/live","/live-no-mime"})try{
            scenario("HLS同流内容识别与窗口定位 "+path,()->liveInput(path));
        }catch(Throwable failure){if(inputFailure==null)inputFailure=failure;else inputFailure.addSuppressed(failure);}
        if(inputFailure instanceof Error)throw(Error)inputFailure;if(inputFailure instanceof Exception)throw(Exception)inputFailure;
        scenario("首次HLS显式定位使用同一列表和真实片段",()->{
            long before=IsolatedMediaAdapters.resources().activeHandles();
            try(MediaLiveFixture fixture=new MediaLiveFixture(true);
                    MediaDecoderSource source=IsolatedMediaAdapters.decoderFactory().openSource(MediaSource.of(fixture.uri("/live-no-mime")),()->false);
                    MediaDecoder decoder=source.open(9_000_000_000L,true,()->false)){
                require(source.initialPositionNanos()==8_000_000_000L,"首次定位未选择同一列表中的实际片段");
                require(Math.abs(video(decoder).presentationNanos-source.initialPositionNanos())<50_000_000L,"首次显式定位丢失真实PTS锚点");
                require(fixture.manifests.get()==1&&fixture.heads.get()==0,"首次定位额外抓取列表");
            }
            require(IsolatedMediaAdapters.resources().activeHandles()==before,"首次定位资源未归还");
        });
        scenario("HTTP头不可变并拒绝字段注入",()->{
            Map<String,String> headers=new LinkedHashMap<>();headers.put("Referer","https://fixture.example/watch");
            MediaSource source=MediaSource.http(java.net.URI.create("https://example.test/video"),headers);headers.clear();require(source.headers.size()==1,"请求头没有冻结");
            boolean failed=false;try{source.headers.clear();}catch(UnsupportedOperationException expected){failed=true;}require(failed,"请求头可被外部修改");
            headers.put("Referer","value\r\nInjected: yes");failed=false;try{MediaSource.http(source.uri,headers);}catch(IllegalArgumentException expected){failed=true;}require(failed,"HTTP换行未被拒绝");
        });
        scenario("新来源尚未打开时不能提前结算定位",()->{
            try(MediaNetworkFixture fixture=new MediaNetworkFixture()){
                java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicInteger opens=new java.util.concurrent.atomic.AtomicInteger();
                club.heiqi.uilib.ui.media.spi.MediaDecoderFactory nativeFactory=IsolatedMediaAdapters.decoderFactory();
                club.heiqi.uilib.ui.media.spi.MediaDecoderFactory gated=(source,token)->{
                    if(opens.incrementAndGet()>1){entered.countDown();while(!release.await(10,java.util.concurrent.TimeUnit.MILLISECONDS))token.check();}
                    return nativeFactory.open(source,token);
                };
                try(club.heiqi.uilib.ui.media.MediaService service=new club.heiqi.uilib.ui.media.MediaService(gated,IsolatedMediaAdapters.audioSinkFactory(),club.heiqi.uilib.ui.media.MediaBudget.DEFAULT)){
                    club.heiqi.uilib.ui.media.MediaSession session=service.open(fixture.split(false),false);
                    MediaTestSupport.await(session,s->s.info.ready&&s.outputReady,5000);
                    session.source(fixture.split(false),500_000_000L);
                    try{
                        require(entered.await(2,java.util.concurrent.TimeUnit.SECONDS),"新来源读取没有进入门控");
                        long until=System.nanoTime()+200_000_000L;
                        while(session.snapshot().state==club.heiqi.uilib.ui.media.MediaState.SEEKING&&System.nanoTime()<until)Thread.sleep(2);
                        require(session.snapshot().state==club.heiqi.uilib.ui.media.MediaState.SEEKING,"新来源事实未知时提前完成定位："+session.snapshot().state);
                    }finally{release.countDown();}
                }
            }
        });
        scenario("同字节数的真实动态视频尺寸刷新行跨度",()->{
            java.nio.file.Path file=java.nio.file.Files.createTempFile("uilib-equal-area-",".mjpg");
            try{
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                int[][] images={{64,96,0xffcc2222},{96,64,0xff2244cc}};
                for(int[] image:images){
                    java.awt.image.BufferedImage frame=new java.awt.image.BufferedImage(image[0],image[1],java.awt.image.BufferedImage.TYPE_INT_RGB);
                    for(int y=0;y<image[1];y++)for(int x=0;x<image[0];x++)frame.setRGB(x,y,image[2]);
                    javax.imageio.ImageIO.write(frame,"jpeg",bytes);
                }
                java.nio.file.Files.write(file,bytes.toByteArray());
                try(MediaDecoder decoder=IsolatedMediaAdapters.decoderFactory().open(MediaSource.of(file.toUri()),()->false)){
                    MediaDecoded first=decoder.read();require(first!=null&&first.video&&first.width==64&&first.height==96,"未读取首个真实窄帧");
                    MediaDecoded second=decoder.read();require(second!=null&&second.video&&second.width==96&&second.height==64,"真实MJPEG未变尺寸");
                    int pixel=(second.width*second.height-1)*4;
                    int red=second.data.get(pixel)&255,blue=second.data.get(pixel+2)&255;
                    System.out.println("RGBA_STRIDE equalBytes="+second.data.remaining()+" first=64x96 second=96x64 lastRed="+red+" lastBlue="+blue);
                    require(blue>red+80,"同字节数变宽后末像素仍来自旧帧，行跨度未更新");
                }
            }finally{java.nio.file.Files.deleteIfExists(file);}
        });
        scenario("首次HTTP媒体复用原请求并保留首包",()->{
            long before=IsolatedMediaAdapters.resources().activeHandles();
            try(MediaNetworkFixture fixture=new MediaNetworkFixture()){
                try(MediaDecoder decoder=IsolatedMediaAdapters.decoderFactory().open(fixture.source("/audio.wav"),()->false)){
                    MediaDecoded first=decoder.read();
                    require(first!=null&&!first.video&&first.presentationNanos==0,"HTTP探测丢失首音频包");
                    System.out.println("DIRECT_INPUT_REQUESTS "+fixture.requestLog);
                    require(fixture.requestLog.equals(java.util.Arrays.asList("GET /audio.wav bytes=0-","GET /audio.wav bytes=44-"))&&fixture.heads.get()==0,"非HLS请求超出原输入及WAV数据区定位");
                }
            }
            require(IsolatedMediaAdapters.resources().activeHandles()==before,"非HLS首次IO没有释放");
        });
        scenario("首列表同流读取取消结束native资源",()->{
            long before=IsolatedMediaAdapters.resources().activeHandles();
            try(MediaNetworkFixture fixture=new MediaNetworkFixture()){
                AtomicBoolean cancel=new AtomicBoolean();java.util.concurrent.atomic.AtomicReference<Throwable> result=new java.util.concurrent.atomic.AtomicReference<>();
                Thread reader=new Thread(()->{
                    try(MediaDecoderSource source=IsolatedMediaAdapters.decoderFactory().openSource(fixture.source("/blocked"),cancel::get);
                            MediaDecoder decoder=source.open(0,false,()->false)){
                        result.set(new AssertionError("未完整的首列表提前打开成功"));
                    }catch(Throwable failure){result.set(failure);}
                },"uilib-initial-input-verification");
                reader.start();
                try{
                    require(fixture.prefixSent.await(2,java.util.concurrent.TimeUnit.SECONDS),"原请求没有递交HLS前缀");
                    cancel.set(true);reader.join(2000);
                    require(!reader.isAlive()&&result.get() instanceof java.util.concurrent.CancellationException,"首列表阻塞读取没有响应原取消: "+result.get());
                    require(fixture.requests("/blocked")==1&&fixture.heads.get()==0,"取消路径重新请求来源");
                }finally{cancel.set(true);fixture.releasePrefix.countDown();reader.join(2000);}
            }
            require(IsolatedMediaAdapters.resources().activeHandles()==before,"取消首次输入后native资源未归还");
        });
        scenario("真实Range双输入不同尾部及定位取消",()->{
            try(MediaNetworkFixture fixture=new MediaNetworkFixture()){
                for(boolean shorter:new boolean[]{false,true}){
                    AtomicBoolean cancelled=new AtomicBoolean();
                    try(MediaDecoderSource source=IsolatedMediaAdapters.decoderFactory().openSource(fixture.split(shorter),cancelled::get)){
                        try(MediaDecoder decoder=source.open(0,false,cancelled::get)){
                            require(decoder.info().hasVideo&&decoder.info().hasAudio,"分轨没有合并媒体事实");
                            int videos=0,audios=0;long videoEnd=0,audioEnd=0;
                            for(int i=0;i<10000;i++){
                                MediaDecoded item=decoder.read();if(item==null)break;if(item==MediaDecoded.YIELD)continue;
                                if(item.video){videos++;videoEnd=Math.max(videoEnd,item.presentationNanos+item.durationNanos);}else{audios++;audioEnd=Math.max(audioEnd,item.presentationNanos+item.durationNanos);}
                            }
                            require(videos==20&&audios>0,"一轨EOF截断了另一轨");require(shorter?videoEnd>audioEnd:audioEnd>videoEnd,"独立尾部未完整解码");
                            System.out.println("NETWORK_SPLIT shorterAudio="+shorter+" frames="+videos+" audioBlocks="+audios+" videoEnd="+videoEnd+" audioEnd="+audioEnd);
                        }
                        try(MediaDecoder decoder=source.open(500_000_000L,true,cancelled::get)){
                            boolean video=false,audio=false;
                            for(int i=0;i<1000&&(!video||!audio);i++){MediaDecoded item=decoder.read();if(item==null)break;if(item!=MediaDecoded.YIELD&&item.presentationNanos+item.durationNanos>500_000_000L){if(item.video)video=true;else audio=true;}}
                            require(video&&audio,"双输入定位后未取得目标数据");cancelled.set(true);boolean failed=false;try{decoder.read();}catch(java.util.concurrent.CancellationException expected){failed=true;}require(failed,"native读取取消未生效");
                        }
                    }
                }
                try(MediaDecoderSource source=IsolatedMediaAdapters.decoderFactory().openSource(fixture.source("/vod.m3u8"),()->false);MediaDecoder decoder=source.open(0,false,()->false)){
                    require(decoder.read()!=null,"HLS列表/片段未携带请求头");
                }
                require(fixture.requests("/vod.m3u8")==1&&fixture.heads.get()==0,"VOD首列表出现额外预请求");
                require(fixture.accepted.get()>0&&fixture.rejected.get()==0,"真实请求缺失声明的头");
                System.out.println("NETWORK_HEADERS accepted="+fixture.accepted+" rejected="+fixture.rejected);
            }
        });
        require(IsolatedMediaAdapters.resources().activeHandles()==resourcesBefore,"网络回归后native资源未回到起点");
        System.out.println("NETWORK_RESOURCES "+IsolatedMediaAdapters.resources());
    }
    private void liveInput(String path)throws Exception{
        long before=IsolatedMediaAdapters.resources().activeHandles();
        try(MediaLiveFixture fixture=new MediaLiveFixture(true);
                MediaDecoderSource source=IsolatedMediaAdapters.decoderFactory().openSource(MediaSource.of(fixture.uri(path)),()->false)){
            club.heiqi.uilib.ui.media.MediaInfo live;
            try(MediaDecoder decoder=source.open(0,false,()->false)){
                live=decoder.info();
                require(live.durationNanos==-1&&live.seekable!=null,"无后缀HLS未发布真实live窗口");
                require(live.seekable.startNanos==0&&live.seekable.endNanos==4*4_000_000_000L-1&&source.initialPositionNanos()==4_000_000_000L,"首次列表窗口或实际起播片段错误");
                require(fixture.manifests.get()==1&&fixture.heads.get()==0,"初始来源出现额外HEAD/GET");
                MediaDecoded first=video(decoder);
                require(Math.abs(first.presentationNanos-source.initialPositionNanos())<50_000_000L,"首响应和真实PTS锚点不一致");
                fixture.advanceWindow();fixture.end();
                long remaining=source.refreshDeadlineNanos()-System.nanoTime();
                if(remaining>0)java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining+1_000_000L);
                club.heiqi.uilib.ui.media.MediaInfo end=source.refresh(live,()->false);
                require(end.seekable.startNanos==4_000_000_000L&&end.durationNanos==5*4_000_000_000L,"滑动或ENDLIST丢失同源时间线");
                require(source.refreshDeadlineNanos()==Long.MAX_VALUE&&fixture.manifests.get()==2,"正常刷新未结束或重复读取");
            }
            long target=6_000_000_000L;
            try(MediaDecoder next=source.open(target,true,()->false)){
                boolean covered=false;int videos=0,audios=0;
                for(int i=0;i<3000;i++){
                    MediaDecoded frame=next.read();if(frame==null)break;if(frame==MediaDecoded.YIELD)continue;
                    if(frame.video){videos++;covered|=frame.presentationNanos<=target&&frame.presentationNanos+frame.durationNanos>target;}else audios++;
                }
                require(covered&&videos>0&&audios>0&&next.inputState().inputEnded,"同源ENDLIST重开定位或真实EOF丢失");
                require(fixture.manifests.get()==2,"重开cursor重新抓取首列表");
            }
            System.out.println("HLS_INPUT path="+path+" initialGET=1 refreshGET=1 HEAD="+fixture.heads+" sameOriginSeek=true");
        }
        require(IsolatedMediaAdapters.resources().activeHandles()==before,"HLS关闭后native资源未归还");
    }
    private static MediaDecoded video(MediaDecoder decoder)throws Exception{
        for(int i=0;i<300;i++){MediaDecoded frame=decoder.read();if(frame==null)break;if(frame!=MediaDecoded.YIELD&&frame.video)return frame;}
        throw new AssertionError("未读取真实视频帧");
    }
}
