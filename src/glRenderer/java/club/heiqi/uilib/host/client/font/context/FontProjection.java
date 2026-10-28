package club.heiqi.uilib.host.client.font.context;

import club.heiqi.uilib.ui.scene.text.RasterSampling;

/** 持有列主序P/MV与GL viewport副本，保留世界文字的clip w和depth。 */
public final class FontProjection {
    private final float[] projection, modelView, mvp;
    private final int[] viewport;

    public FontProjection(float[] projection, float[] modelView, int[] viewport) {
        this.projection = matrix(projection);
        this.modelView = matrix(modelView);
        if (viewport == null || viewport.length != 4 || viewport[2] <= 0 || viewport[3] <= 0)
            throw new IllegalArgumentException("无效viewport");
        this.viewport = viewport.clone();
        mvp = multiply(this.projection, this.modelView);
    }

    private static float[] matrix(float[] value) {
        if (value == null || value.length != 16) throw new IllegalArgumentException("矩阵必须16元素");
        float[] copy = value.clone();
        for (float v : copy) if (!Float.isFinite(v)) throw new IllegalArgumentException("非有限矩阵");
        return copy;
    }

    private static float[] multiply(float[] a, float[] b) {
        float[] result = new float[16];
        for (int c = 0; c < 4; c++)
            for (int r = 0; r < 4; r++) {
                double sum = 0;
                for (int k = 0; k < 4; k++) sum += (double) a[k * 4 + r] * b[c * 4 + k];
                result[c * 4 + r] = (float) sum;
                if (!Float.isFinite(result[c * 4 + r])) throw new IllegalArgumentException("MVP溢出");
            }
        return result;
    }

    boolean sameState(FontProjection other) {
        return this == other || other != null
                && FontRenderContext.sameFloats(projection, other.projection)
                && FontRenderContext.sameFloats(modelView, other.modelView)
                && java.util.Arrays.equals(viewport, other.viewport);
    }

    public float[] projection() {
        return projection.clone();
    }

    public float[] modelView() {
        return modelView.clone();
    }

    public float[] mvp() {
        return mvp.clone();
    }

    public int[] viewport() {
        return viewport.clone();
    }

    public double[] clipAt(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("非有限anchor");
        double[] q = new double[4];
        fillClip(x, y, z, q);
        return q;
    }

    /** 将已校验锚点投影到本次command持有的缓冲。 */
    void fillClip(double x, double y, double z, double[] target) {
        for (int r = 0; r < 4; r++)
            target[r] = mvp[r] * x + mvp[4 + r] * y + mvp[8 + r] * z + mvp[12 + r];
    }

    boolean parallelXY() {
        return mvp[3] == 0 && mvp[7] == 0;
    }

    /** local x/y平面的屏幕像素微分，largest singular value覆盖旋转/shear/非等比。
     * anchor在eye后或w接近0时拒绝采样；裁剪由caller/backend另管。 */
    public double pixelsPerUnitAt(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("非有限anchor");
        double qx = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12];
        double qy = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13];
        double w = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15];
        return densityClip(qx, qy, w, true);
    }

    /** 已投影角点复用同一Jacobian；无效结果以NaN交采样策略区分behind与顶档。 */
    double densityClip(double qx, double qy, double w) {
        return densityClip(qx, qy, w, false);
    }

    private double densityClip(double qx, double qy, double w, boolean strict) {
        if (!Double.isFinite(w) || w <= 1e-9)
            return invalidDensity(strict, "anchor不在可采样的正clip-w区域");
        // 固定乘除与hypot顺序，保持临界采样值所属档位。
        double a = (mvp[0] - qx / w * mvp[3]) / w * viewport[2] / 2.0;
        double b = (mvp[4] - qx / w * mvp[7]) / w * viewport[2] / 2.0;
        double c = (mvp[1] - qy / w * mvp[3]) / w * viewport[3] / 2.0;
        double d = (mvp[5] - qy / w * mvp[7]) / w * viewport[3] / 2.0;
        double scale =
                Math.max(Math.max(Math.abs(a), Math.abs(b)), Math.max(Math.abs(c), Math.abs(d)));
        if (!Double.isFinite(scale) || scale == 0) return invalidDensity(strict, "投影平面退化");
        a /= scale;
        b /= scale;
        c /= scale;
        d /= scale;
        double xx = a * a + c * c, xy = a * b + c * d, yy = b * b + d * d;
        double sigma = scale * Math.sqrt((xx + yy + Math.hypot(xx - yy, 2 * xy)) / 2);
        if (!Double.isFinite(sigma) || sigma <= 0) return invalidDensity(strict, "投影采样溢出");
        return sigma;
    }

    private static double invalidDensity(boolean strict, String message) {
        if (strict) throw new IllegalArgumentException(message);
        return Double.NaN;
    }

    /** actualRasterEm记录policy档位，Atlas另行裁决容量；capped表示采样需求受限。 */
    public static final class SamplingPlan {
        public final RasterSampling sampling;
        public final double requestedRasterEm;
        public final int actualRasterEm;
        public final boolean capped;

        private SamplingPlan(double requested, int actual, double logicalEm, long revision) {
            requestedRasterEm = requested;
            actualRasterEm = actual;
            capped = actual < requested;
            sampling = new RasterSampling((float) (actual / logicalEm), revision);
        }
    }

    public SamplingPlan samplingPlanAt(
            double x, double y, double z, double logicalEm, RasterPolicy policy, long revision) {
        if (!Double.isFinite(logicalEm) || logicalEm <= 0 || policy == null)
            throw new IllegalArgumentException("无效逻辑em或policy");
        double desired = pixelsPerUnitAt(x, y, z) * logicalEm;
        return new SamplingPlan(desired, policy.rasterEm(desired), logicalEm, revision);
    }

    public RasterSampling samplingAt(
            double x, double y, double logicalEm, RasterPolicy policy, long revision) {
        return samplingAt(x, y, 0, logicalEm, policy, revision);
    }

    public RasterSampling samplingAt(
            double x, double y, double z, double logicalEm, RasterPolicy policy, long revision) {
        if (!Double.isFinite(logicalEm) || logicalEm <= 0 || policy == null)
            throw new IllegalArgumentException("无效逻辑em或policy");
        return samplingPlanAt(x, y, z, logicalEm, policy, revision).sampling;
    }
}
