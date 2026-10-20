package club.heiqi.uilib.host.desktop;

import static org.lwjgl.glfw.GLFW.*;
import static club.heiqi.uilib.host.desktop.DesktopTestInput.*;
import static club.heiqi.uilib.host.desktop.DesktopTestFrames.finitePresentations;
import java.nio.file.*;
import java.util.*;
import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.ui.scene.control.SceneDragHandle;
import club.heiqi.uilib.ui.scene.layout.*;

/** 重设计真实绘制与输入矩阵；完整配置文件流程由原设置验收维护。 */
public final class DesktopSettingsWorkspaceVerification extends TestApplication {
    private Path output;
    public static void main(String[] args)throws Exception {new DesktopSettingsWorkspaceVerification().run(args);}
    protected void setup()throws Exception {
        String previous=System.getProperty("uilib.desktop.visible");System.setProperty("uilib.desktop.visible","false");
        onClose(()->{if(previous==null)System.clearProperty("uilib.desktop.visible");else System.setProperty("uilib.desktop.visible",previous);});
        output=Paths.get(arguments()[0]);Files.createDirectories(output);
    }
    protected void runTests()throws Exception {
        scenario("宽中窄低高度双主题的构图与操作可达",this::layouts);
        scenario("重复长来源与失焦取消沿正式排序输入",this::longSources);
    }
    private void layouts()throws Exception {
        try(DesktopWindow w=DesktopWindow.openSettings(output.resolve("layout.yaml"))) {
            DesktopTestInput input=new DesktopTestInput(w);
            glfwSetWindowSize(w.handle(),800,400);finitePresentations(w);hidden(w);
            DesktopSettingsVerification.screenshot(w,output.resolve("workspace-initial-800x400.png"));
            require(visibleText(w,"设置")&&visibleText(w,"浅色外观"),"低窗首次进入缺少标题或首个主题控件");
            int[][] sizes={{2560,1440},{1440,1000},{1024,768},{480,900},{360,640},{800,400},{640,360},{480,300}};
            for(boolean dark:new boolean[]{false,true}) {
                glfwSetWindowSize(w.handle(),1440,1000);finitePresentations(w);if(dark)input.click("深色模式");
                for(int[] size:sizes) {
                    glfwSetWindowSize(w.handle(),size[0],size[1]);finitePresentations(w);hidden(w);
                    for(String action:new String[]{"放弃编辑","保存到文件","应用全部更改"})fullyVisible(w,action);
                    String dimensions=size[0]+"x"+size[1]+(dark?"-dark.png":"-light.png");
                    DesktopSettingsVerification.screenshot(w,output.resolve("workspace-"+dimensions));
                    System.out.println("WORKSPACE_LAYOUT width="+w.width()+" height="+w.height()+" dark="+dark+" hidden=true");
                    input.click("管理字体来源");finitePresentations(w);fullyVisible(w,"完成字体编辑");fullyVisible(w,"+ 添加来源");
                    require(!handles(w).isEmpty(),"字体列表无可见行："+dimensions);
                    DesktopSettingsVerification.screenshot(w,output.resolve("fonts-"+dimensions));
                    input.click("+ 添加来源");finitePresentations(w);fullyVisible(w,"添加到末尾");
                    DesktopSettingsVerification.screenshot(w,output.resolve("add-"+dimensions));
                    if(size[1]<=400){
                        // 低窗沿正式滚动显露来源选项，真实选择与浏览证明可操作，不要求固定首屏行数。
                        input.click("本地字体文件");finitePresentations(w);
                        input.click("浏览字体文件");finitePresentations(w);escape(w,input);
                        DesktopSettingsVerification.screenshot(w,output.resolve("add-file-"+dimensions));
                    }
                    escape(w,input);input.click("完成字体编辑");finitePresentations(w);
                }
            }
        }
    }
    private void longSources()throws Exception {
        Path file=output.resolve("long-sources.yaml");
        try(DesktopWindow w=DesktopWindow.openSettings(file)) {
            glfwSetWindowSize(w.handle(),1280,800);finitePresentations(w);hidden(w);DesktopTestInput input=new DesktopTestInput(w);
            List<String> original=DesktopSettingsVerification.current(w),sources=new ArrayList<>();
            for(int i=0;i<120;i++)sources.add(original.get(i%original.size()));
            w.domain.execute(()->w.configuration.set(w.fontCandidates,sources));finitePresentations(w);
            byte[] before=Files.exists(file)?Files.readAllBytes(file):new byte[0];input.click("管理字体来源");finitePresentations(w);
            List<SceneGeometryView.Entry> entries=handles(w);require(entries.size()>2,"长列表无可操作视口");
            DesktopSettingsVerification.screenshot(w,output.resolve("fonts-120-sources.png"));
            LayoutBox a=hit(entries.get(0)),b=hit(entries.get(2));input.point(a.x+a.width/2.0,a.y+a.height/2.0);
            input.mouse.invoke(w.handle(),GLFW_MOUSE_BUTTON_LEFT,GLFW_PRESS,0);input.point(b.x+b.width/2.0,b.y+b.height/2.0);w.frame();
            input.focus.invoke(w.handle(),false);w.frame();input.mouse.invoke(w.handle(),GLFW_MOUSE_BUTTON_LEFT,GLFW_RELEASE,0);w.frame();
            DesktopSettingsVerification.requireNoFontDragOwner(w);finitePresentations(w);
            require(DesktopSettingsVerification.current(w).equals(sources),"失焦取消改写生效顺序");
            // 模拟用户回到窗口后才继续键盘关闭；失焦时宿主正确拒绝键盘输入。
            input.focus.invoke(w.handle(),true);w.frame();input.click("↓");finitePresentations(w);
            require(DesktopSettingsVerification.current(w).equals(sources),"行动作提前应用");
            LayoutBox point=hit(handles(w).get(0));input.point(point.x+point.width/2.0,point.y+point.height/2.0);
            String lastOrdinal=String.format(Locale.ROOT,"%02d  ",sources.size());int scrolls=0;
            // 大滚轮量不等于实际内容末端；持续发送真实滚轮，按当前可见几何结束。
            while(!sourceVisible(w,lastOrdinal)&&scrolls<sources.size()){
                input.scroll.invoke(w.handle(),0,-12);w.frame();finitePresentations(w);scrolls++;
            }
            DesktopSettingsVerification.screenshot(w,output.resolve("fonts-120-end.png"));
            require(sourceVisible(w,lastOrdinal),"120行末端不可滚达，真实滚轮次数="+scrolls);
            escape(w,input);input.click("应用全部更改");finitePresentations(w);List<String> expected=new ArrayList<>(sources);Collections.swap(expected,0,1);
            require(DesktopSettingsVerification.current(w).equals(expected),"取消或重复来源身份使单次下移改错顺序");
            require(Arrays.equals(before,Files.exists(file)?Files.readAllBytes(file):new byte[0]),"编辑或应用隐式保存");
            System.out.println("WORKSPACE_LONG_SOURCES count="+sources.size()+" focusCancel=true savedUnchanged=true");
        }
    }
    private static void escape(DesktopWindow w,DesktopTestInput input) {
        input.key.invoke(w.handle(),GLFW_KEY_ESCAPE,0,GLFW_PRESS,0);input.key.invoke(w.handle(),GLFW_KEY_ESCAPE,0,GLFW_RELEASE,0);finitePresentations(w);
    }
    private static List<SceneGeometryView.Entry> handles(DesktopWindow w) {
        List<SceneGeometryView.Entry> values=new ArrayList<>();w.domain.execute(()->{
            for(SceneGeometryView.Entry e:geometry(w).ordered())if(e.node.control() instanceof SceneDragHandle
                    &&e.node.runtime()!=null&&e.node.runtime().interactive(e.node)&&hit(e).height>0&&hit(e).width>0)values.add(e);
        });return values;
    }
    private static boolean sourceVisible(DesktopWindow w,String ordinal) {
        for(SceneGeometryView.Entry e:geometry(w).ordered())
            if(e.text!=null&&e.text.logicalText.startsWith(ordinal)&&hit(e).height>0&&hit(e).width>0)return true;
        return false;
    }
    private static boolean visibleText(DesktopWindow w,String text) {
        for(SceneGeometryView.Entry e:geometry(w).ordered())if(e.text!=null&&text.equals(e.text.logicalText)&&hit(e).height>0&&hit(e).width>0)return true;
        return false;
    }
    private static void fullyVisible(DesktopWindow w,String text) {
        for(SceneGeometryView.Entry e:geometry(w).ordered())if(e.text!=null&&text.equals(e.text.originalText)&&e.node.clickAction()!=null) {
            LayoutBox v=hit(e);require(v.width==e.box.width&&v.height==e.box.height&&v.width>0&&v.height>0,
                    "操作被裁切："+text+" box="+e.box.x+","+e.box.y+","+e.box.width+","+e.box.height+" hit="+v.width+","+v.height);return;
        }throw new AssertionError("缺少操作："+text);
    }
    private static void hidden(DesktopWindow w) {require(glfwGetWindowAttrib(w.handle(),GLFW_VISIBLE)==GLFW_FALSE,"验收窗口意外可见");}
}
