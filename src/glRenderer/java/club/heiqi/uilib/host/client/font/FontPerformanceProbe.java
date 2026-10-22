package club.heiqi.uilib.host.client.font;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** #81 临时诊断：默认关闭，只由一次性采集器在主线程限时启用。
 * 不持有渲染owner、context、plan、UV或GL资源；报告定位瓶颈后统一移除本类及调用点。
 * 开启时阶段为inclusive；逐glyph仅累加固定数组，不读取时钟。IO和报告统计归外部采集器。
 */
public final class FontPerformanceProbe {
    public static final int MC_DRAW_ENTRY=0, MC_CONTEXT_CAPTURE=1, MC_TEXT_LAYOUT=2,
            MC_SAMPLED_PLAN=3, MC_SAMPLING_MEMO=4, REGISTER_VISIBLE=5, COLLECT_VISIBLE=6,
            OWNER_PREPARE=7, ATLAS_PREPARE=8, PLAN_REPLAY=9, BATCH_FLUSH=10, QUAD_SUBMIT=11,
            VERTEX_UPLOAD=12, GL_DRAW=13, HOST_CAPTURE=14, HOST_SUBMIT=15, HOST_MERGE=16,
            HOST_REPLAY=17, HOST_COMPATIBLE=18, CPU_DRAIN_READY=19, END_PRESENTATION=20,
            HOST_PROGRAM_CAPTURE=21, HOST_MATRIX_CAPTURE=22, HOST_RASTER_CAPTURE=23;
    public static final int MEMO_HIT=0, MEMO_MISS=1, MEMO_EVICT=2, MEMO_RESET=3,
            TICKET_HIT=4, TICKET_MISS=5, TICKET_MISMATCH=6, TICKET_REMEMBER=7, TICKET_FULL=8,
            LOGICAL_LAYOUT_HIT=9, LOGICAL_LAYOUT_MISS=10, SAMPLED_LAYOUT_HIT=11,
            SAMPLED_LAYOUT_MISS=12, VISIBILITY_GLYPHS=13, VISIBILITY_UNKNOWN=14,
            VISIBILITY_SCENE=15, VISIBILITY_NEEDS_CLIP=16, VISIBILITY_REJECTED=17,
            DEMAND_INPUT=18, DEMAND_ADMITTED=19, DEMAND_REJECTED=20,
            ATLAS_LOOKUP_HIT=21, ATLAS_LOOKUP_MISS=22, HOST_RUN_PLANS=23,
            HOST_RUN_COMMANDS=24, HOST_MERGED_RUNS=25, HOST_SINGLE_RUNS=26,
            HOST_ELIGIBLE=27, HOST_INELIGIBLE=28, HOST_COMPATIBLE_HIT=29, HOST_COMPATIBLE_MISS=30;
    public static final int MEMO_SIZE=0, LOGICAL_LAYOUT_SIZE=1, SAMPLED_LAYOUT_SIZE=2,
            PRESENTATION_DEMAND=3, PREPARED_COUNT=4, ATLAS_RESIDENTS=5,
            UPLOAD_REMAINING_BYTES=6, UPLOAD_REMAINING_NANOS=7, UPLOAD_REMAINING_CALLS=8;
    private static final String[] STAGES={"mc.drawEntry","mc.contextCapture","mc.textLayout",
            "mc.sampledPlan","mc.samplingMemo","font.registerVisible","gl.collectVisible",
            "font.ownerPrepare","gl.atlasPrepare","gl.planReplay","gl.batchFlush",
            "gl.quadSubmit","gl.vertexUpload","gl.issueDraw","host.capture","host.submit",
            "host.merge","host.replay","host.compatible","font.cpuDrainReady","font.endPresentation","host.programCapture","host.matrixCapture","host.rasterCapture"};
    private static final String[] COUNTERS={"memo.hit","memo.miss","memo.evict","memo.reset",
            "ticket.hit","ticket.miss","ticket.mismatch","ticket.remember","ticket.full",
            "logicalLayout.hit","logicalLayout.miss","sampledLayout.hit","sampledLayout.miss",
            "visibility.glyphs","visibility.unknown","visibility.scene","visibility.polygonsNeedClip",
            "visibility.rejected","demand.input","demand.admitted","demand.rejected",
            "atlasLookup.hit","atlasLookup.miss","host.runPlans","host.runCommands",
            "host.mergedRuns","host.singleRuns","host.eligible","host.ineligible",
            "host.compatibleHit","host.compatibleMiss"};
    private static final String[] GAUGES={"memo.size","logicalLayout.size","sampledLayout.size",
            "presentation.demand","presentation.activePrepared","atlas.residents",
            "upload.remainingBytes","upload.remainingNanos","upload.remainingCalls"};
    private static volatile Session current;
    private FontPerformanceProbe() {}

    /** 关闭路径不读时钟，也不分配对象。token仅在同次会话中有效。 */
    public static long begin(int stage) {
        Session s=current;
        if(s==null||!s.recording||s.thread!=Thread.currentThread())return 0;
        long now=System.nanoTime();
        if(now-s.deadline>=0){s.stop("duration",now);return 0;}
        return now;
    }
    public static void end(int stage,long token) {
        if(token==0)return;
        Session s=current;
        if(s==null||!s.recording||s.thread!=Thread.currentThread()||token-s.started<0)return;
        long elapsed=System.nanoTime()-token;
        if(elapsed<0)throw new IllegalStateException("诊断时钟倒退");
        s.pendingNanos[stage]+=elapsed;
        s.pendingCalls[stage]++;
    }
    public static void count(int counter,long delta) {
        Session s=current;
        if(s!=null&&s.recording&&s.thread==Thread.currentThread())s.pendingCounters[counter]+=delta;
    }
    public static void observe(int gauge,long value) {
        Session s=current;
        if(s==null||!s.recording||s.thread!=Thread.currentThread())return;
        if(!s.gaugeSeen[gauge]||value<s.gaugeMin[gauge])s.gaugeMin[gauge]=value;
        if(!s.gaugeSeen[gauge]||value>s.gaugeMax[gauge])s.gaugeMax[gauge]=value;
        s.gaugeLast[gauge]=value;
        s.gaugeSeen[gauge]=true;
    }

    public static void start(int frameLimit,long durationNanos) {
        if(current!=null)throw new IllegalStateException("先finish上一份临时采集");
        if(frameLimit<1||frameLimit>16384||durationNanos<1000000||durationNanos>120000000000L)
            throw new IllegalArgumentException("临时采集仅允许1..16384帧、1ms..120s");
        current=new Session(frameLimit,durationNanos);
    }

    /** 只由MC原beforePresent或显式验收调用；首个边界丢弃启动时的半帧。 */
    public static void frame() {
        Session s=current;
        if(s==null||!s.recording||s.thread!=Thread.currentThread())return;
        long now=System.nanoTime();
        if(now-s.deadline>=0){s.stop("duration",now);return;}
        long cpu=s.cpuNanos(),allocated=s.allocatedBytes();
        if(s.boundary) {
            int n=s.frames;
            s.wall[n]=now-s.previousNano;
            s.cpu[n]=cpu>=0&&s.previousCpu>=0?cpu-s.previousCpu:-1;
            s.allocated[n]=allocated>=0&&s.previousAllocated>=0?allocated-s.previousAllocated:-1;
            for(int i=0;i<STAGES.length;i++) {
                s.stageNanos[i][n]=s.pendingNanos[i];
                s.calls[i]+=s.pendingCalls[i];
            }
            for(int i=0;i<COUNTERS.length;i++)s.counters[i]+=s.pendingCounters[i];
            s.frames++;
        }
        s.clearPending();
        s.boundary=true;
        s.previousNano=now;s.previousCpu=cpu;s.previousAllocated=allocated;
        if(s.frames==s.limit)s.stop("frameLimit",now);
    }

    /** 先禁用采集，返回退役数组；外部线程可裁切/序列化，主线程不做文件IO。 */
    public static Map<String,Object> finish() {
        Session s=current;
        if(s==null)throw new IllegalStateException("没有临时采集");
        if(s.thread!=Thread.currentThread())throw new IllegalStateException("须在采集主线程finish");
        s.stop("finished",System.nanoTime());
        current=null;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("format",1);result.put("thread",s.thread.getName());
        result.put("startedNano",s.started);result.put("stoppedNano",s.stopped);
        result.put("stopReason",s.reason);result.put("durationLimitNanos",s.duration);
        result.put("frameLimit",s.limit);result.put("frameCount",s.frames);
        result.put("firstPartialFrameExcluded",true);
        result.put("trailingPartialFrameExcluded",true);
        result.put("trailingPartialNanos",s.boundary?s.stopped-s.previousNano:0);
        result.put("inclusiveStages",true);result.put("includesProbeOverhead",true);
        result.put("stageNames",STAGES.clone());result.put("counterNames",COUNTERS.clone());
        result.put("gaugeNames",GAUGES.clone());result.put("stageCalls",s.calls);
        result.put("counters",s.counters);result.put("gaugeMin",s.gaugeMin);
        result.put("gaugeMax",s.gaugeMax);result.put("gaugeLast",s.gaugeLast);
        result.put("gaugeSeen",s.gaugeSeen);result.put("wallNanos",s.wall);
        result.put("cpuNanos",s.cpu);result.put("allocatedBytes",s.allocated);
        result.put("stageNanos",s.stageNanos);
        return result;
    }

    public static Map<String,Object> status() {
        Session s=current;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("installed",true);result.put("recording",s!=null&&s.recording);
        result.put("pendingSnapshot",s!=null);result.put("frameCount",s==null?0:s.frames);
        result.put("stopReason",s==null?"inactive":s.reason);
        return result;
    }

    private static final class Session {
        final Thread thread=Thread.currentThread();
        final long started=System.nanoTime(),duration,deadline;
        final int limit;
        final ThreadMXBean threads=ManagementFactory.getThreadMXBean();
        final Method allocationReader;
        final long[] wall,cpu,allocated;
        final long[][] stageNanos;
        final long[] pendingNanos=new long[STAGES.length],pendingCalls=new long[STAGES.length],
                pendingCounters=new long[COUNTERS.length],calls=new long[STAGES.length],
                counters=new long[COUNTERS.length],gaugeMin=new long[GAUGES.length],
                gaugeMax=new long[GAUGES.length],gaugeLast=new long[GAUGES.length];
        final boolean[] gaugeSeen=new boolean[GAUGES.length];
        volatile boolean recording=true;
        boolean boundary;
        int frames;
        long previousNano,previousCpu,previousAllocated,stopped;
        String reason="recording";
        Session(int limit,long duration) {
            this.limit=limit;this.duration=duration;deadline=started+duration;
            wall=new long[limit];cpu=new long[limit];allocated=new long[limit];
            stageNanos=new long[STAGES.length][limit];
            Method reader=null;
            try {
                Class<?> type=Class.forName("com.sun.management.ThreadMXBean");
                if(type.isInstance(threads)&&(Boolean)type.getMethod("isThreadAllocatedMemoryEnabled").invoke(threads))
                    reader=type.getMethod("getThreadAllocatedBytes",long.class);
            } catch(ReflectiveOperationException unavailable) {
                // 不为诊断启用管理器或更改JVM开关；缺项在报告中保留为不可用。
            }
            allocationReader=reader;
        }
        long cpuNanos(){return threads.isThreadCpuTimeSupported()&&threads.isThreadCpuTimeEnabled()
                ?threads.getThreadCpuTime(thread.getId()):-1;}
        long allocatedBytes(){
            if(allocationReader==null)return -1;
            try{return ((Number)allocationReader.invoke(threads,thread.getId())).longValue();}
            catch(ReflectiveOperationException unavailable){return -1;}
        }
        void clearPending(){
            java.util.Arrays.fill(pendingNanos,0);java.util.Arrays.fill(pendingCalls,0);
            java.util.Arrays.fill(pendingCounters,0);
        }
        void stop(String reason,long now){
            if(!recording)return;
            recording=false;this.reason=reason;stopped=now;
        }
    }
}
