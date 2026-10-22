package club.heiqi.uilib.host.client.gl;

import club.heiqi.uilib.host.client.font.context.FontProjection;
import club.heiqi.uilib.host.client.font.context.FontRenderContext;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** 数值边界直接执行生产齐次可见性；不创建GL，不以屏幕AABB代替多边形裁剪。 */
public final class HomogeneousVisibilityScenario {
    private static FontRenderContext context(boolean scissor, int[] rect) {
        float[] m = {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        return new FontRenderContext(new FontProjection(m,m,new int[] {0,0,100,100}),
                0,100,false,0x0203,false,scissor,rect,FontRenderContext.MatrixState.ui(m,m),new Object(),1,0);
    }
    private static List<double[]> rect(double l, double t, double r, double b, double z, double w) {
        return Arrays.asList(new double[] {l,t,z,w},new double[] {r,t,z,w},
                new double[] {r,b,z,w},new double[] {l,b,z,w});
    }
    private static void check(Method visible, FontRenderContext context, boolean expected,
            String name, List<double[]> polygon) throws Exception {
        boolean actual = (Boolean) visible.invoke(null,polygon,context);
        if (actual != expected) throw new AssertionError(name + " expected=" + expected + " actual=" + actual);
    }
    public static void verify() throws Exception {
        Method visible = GlFontVisibility.class.getDeclaredMethod("visible",List.class,FontRenderContext.class);
        visible.setAccessible(true);
        FontRenderContext full = context(false,new int[] {0,0,100,100});
        check(visible,full,true,"完全内部",rect(-.5,-.5,.5,.5,0,1));
        check(visible,full,false,"共同外平面",rect(-3,-.5,-2,.5,0,1));
        check(visible,full,true,"部分交叠",rect(-2,-.5,.5,.5,0,1));
        check(visible,full,false,"投影退化",rect(.25,-.5,.25,.5,0,1));
        check(visible,full,false,"裁面零面积",rect(1,-.5,2,.5,0,1));
        check(visible,full,true,"极小正w",rect(-5e-13,-5e-13,5e-13,5e-13,0,1e-12));
        check(visible,full,false,"完全behind",rect(-.5,-.5,.5,.5,0,-1));
        check(visible,full,true,"零w保守语义",rect(0,0,0,0,0,0));
        check(visible,full,true,"跨near面",Arrays.asList(new double[] {-.5,-.5,-2,1},
                new double[] {.5,-.5,-2,1},new double[] {.5,.5,0,1},new double[] {-.5,.5,0,1}));
        check(visible,full,false,"无共同外码仍完全在角外",Arrays.asList(
                new double[] {.9,3,0,1},new double[] {3,.9,0,1},new double[] {3,3,0,1}));
        FontRenderContext center = context(true,new int[] {45,45,10,10});
        check(visible,center,true,"scissor内部",rect(-.05,-.05,.05,.05,0,1));
        check(visible,center,false,"scissor共同外平面",rect(-.5,-.5,-.2,-.2,0,1));
        check(visible,center,true,"scissor部分交叠",rect(-.2,-.05,.05,.05,0,1));
        List<double[]> diamond = Arrays.asList(new double[] {-.5,0,0,1},new double[] {0,-.5,0,1},
                new double[] {.5,0,0,1},new double[] {0,.5,0,1});
        check(visible,context(true,new int[] {70,70,5,5}),false,"旋转墨迹AABB相交但多边形不交",diamond);
        check(visible,context(true,new int[] {45,45,0,10}),false,"空scissor",diamond);
        System.out.println("HOMOGENEOUS_VISIBILITY_PASS inside outside partial degenerate near tinyPositiveW zeroW rotatedScissor emptyScissor");
    }
}
