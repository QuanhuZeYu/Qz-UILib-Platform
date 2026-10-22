package club.heiqi.uilib.host.client.gl;

import java.util.ArrayList;
import java.util.List;
import club.heiqi.uilib.host.client.font.context.FontRenderContext;
import club.heiqi.uilib.ui.scene.geometry.SceneMesh;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.paint.PaintCommand;
import club.heiqi.uilib.ui.scene.text.TextLayout;

/** 实际墨迹四角先齐次六面裁剪再除w；没有把负w伪造成正值，也不拿caller范围当framebuffer。 */
final class GlFontVisibility {
    private GlFontVisibility() {}

    static LayoutBox viewport(FontRenderContext context) {
        int[] v = context.projection.viewport();
        return new LayoutBox(
                v[0],
                Math.toIntExact((long) context.sourceFramebufferHeight - v[1] - v[3]),
                v[2],
                v[3]);
    }

    static boolean visible(
            PaintCommand command, TextLayout.Glyph glyph, FontRenderContext context) {
        return visible(command, glyph, context, command.effectiveClip());
    }

    static boolean visible(PaintCommand command, TextLayout.Glyph glyph, FontRenderContext context,
            club.heiqi.uilib.ui.scene.geometry.SceneClipRegion clip) {
        if (!glyph.inkBounds.known) return true;
        double x = (double) command.box.x + glyph.x, y = (double) command.box.y + glyph.y;
        double l = x + glyph.inkBounds.left,
                r = x + glyph.inkBounds.right,
                t = y + glyph.inkBounds.top,
                b = y + glyph.inkBounds.bottom;
        if (r <= l || b <= t) return false;
        if (command.sceneGeometry || !command.transform.identity() || !command.shapeClip.unbounded()) {
            SceneMesh mesh = clip.clip(SceneMesh.rectangle(l, t, r - l, b - t).transformed(command.transform));
            for (int i = 0; i < mesh.vertexCount(); i += 3) {
                List<double[]> triangle = new ArrayList<double[]>(3);
                for (int j = 0; j < 3; j++) triangle.add(context.projection.clipAt(mesh.x(i + j), mesh.y(i + j), 0));
                if (visible(triangle, context)) return true;
            }
            return false;
        }
        // 旧world输入仍走四角齐次裁剪，不新增UI坐标或资源前提。
        List<double[]> polygon = new ArrayList<double[]>();
        polygon.add(context.projection.clipAt(l, t, 0));
        polygon.add(context.projection.clipAt(r, t, 0));
        polygon.add(context.projection.clipAt(r, b, 0));
        polygon.add(context.projection.clipAt(l, b, 0));
        return visible(polygon, context);
    }
    private static final int REJECTED = -1, INSIDE = 0, CLIP_REQUIRED = 1;

    private static boolean visible(List<double[]> polygon, FontRenderContext context) {
        int[] viewport = null, scissor = null;
        double[] limits = null;
        int planes = context.scissorEnabled ? 10 : 6;
        if (context.scissorEnabled) {
            viewport = context.projection.viewport();
            scissor = context.scissor();
            limits = screenLimits(viewport, scissor);
        }
        int classification = classify(polygon, planes, limits);
        if (classification == REJECTED) return false;
        if (classification == CLIP_REQUIRED) polygon = clipped(polygon, planes, limits);
        if (polygon.isEmpty()) return false;
        if (viewport == null) viewport = context.projection.viewport();
        return projectedBoundsVisible(polygon, viewport, scissor);
    }

    private static double[] screenLimits(int[] viewport, int[] scissor) {
        return new double[] {
            2.0 * (scissor[0] - viewport[0]) / viewport[2] - 1,
            2.0 * ((long) scissor[0] + scissor[2] - viewport[0]) / viewport[2] - 1,
            2.0 * (scissor[1] - viewport[1]) / viewport[3] - 1,
            2.0 * ((long) scissor[1] + scissor[3] - viewport[1]) / viewport[3] - 1
        };
    }

    /** 只用原齐次平面的严格外侧判定；边界不加epsilon，非有限值仍交原精确路径。 */
    private static int classify(List<double[]> polygon, int planes, double[] limits) {
        int any = 0, common = (1 << planes) - 1;
        boolean positiveW = true;
        for (double[] point : polygon) {
            for (double value : point) if (!Double.isFinite(value)) return CLIP_REQUIRED;
            positiveW &= point[3] > 0;
            int code = 0;
            for (int plane = 0; plane < planes; plane++) {
                double d = distance(point, plane, limits);
                if (!Double.isFinite(d)) return CLIP_REQUIRED;
                if (d < 0) code |= 1 << plane;
            }
            any |= code;
            common &= code;
        }
        if (common != 0) return REJECTED;
        // 全内仅跳过裁剪；仍须投影bbox检查退化。非正w保留原路径的保守可见语义。
        return any == 0 && positiveW ? INSIDE : CLIP_REQUIRED;
    }

    private static List<double[]> clipped(List<double[]> polygon, int planes, double[] limits) {
        // scissor亦以齐次平面裁多边形，避免旋转quad AABB与小scissor交但实际全在外。
        for (int plane = 0; plane < planes && !polygon.isEmpty(); plane++) {
            List<double[]> out = new ArrayList<double[]>();
            double[] previous = polygon.get(polygon.size() - 1);
            double pd = distance(previous, plane, limits);
            for (double[] current : polygon) {
                double cd = distance(current, plane, limits);
                if ((pd >= 0) != (cd >= 0)) {
                    double f = pd / (pd - cd);
                    double[] q = new double[4];
                    for (int i = 0; i < 4; i++) q[i] = previous[i] + f * (current[i] - previous[i]);
                    out.add(q);
                }
                if (cd >= 0) out.add(current);
                previous = current;
                pd = cd;
            }
            polygon = out;
        }
        return polygon;
    }

    private static boolean projectedBoundsVisible(List<double[]> polygon, int[] v, int[] scissor) {
        double minX = Double.POSITIVE_INFINITY,
                minY = minX,
                maxX = Double.NEGATIVE_INFINITY,
                maxY = maxX;
        for (double[] q : polygon) {
            if (q[3] <= 0) return true;
            double sx = v[0] + (q[0] / q[3] + 1) * v[2] / 2.0,
                    sy = v[1] + (q[1] / q[3] + 1) * v[3] / 2.0;
            minX = Math.min(minX, sx);
            maxX = Math.max(maxX, sx);
            minY = Math.min(minY, sy);
            maxY = Math.max(maxY, sy);
        }
        if (maxX <= minX || maxY <= minY) return false;
        if (scissor != null) {
            return minX < (long) scissor[0] + scissor[2]
                    && maxX > scissor[0]
                    && minY < (long) scissor[1] + scissor[3]
                    && maxY > scissor[1];
        }
        return true;
    }

    private static double distance(double[] p, int plane, double[] limits) {
        if (plane < 6) {
            int axis = plane / 2;
            return p[3] + (plane % 2 == 0 ? p[axis] : -p[axis]);
        }
        int side = plane - 6, axis = side / 2;
        return side % 2 == 0 ? p[axis] - limits[side] * p[3] : limits[side] * p[3] - p[axis];
    }
}
