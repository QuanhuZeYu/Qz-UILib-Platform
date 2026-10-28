package club.heiqi.uilib.host.client.font.context;

/** 选择有界光栅em采样档位，布局沿原advance；策略修改须更换调用者revision。 */
public final class RasterPolicy {
    public static final RasterPolicy DEFAULT =
            new RasterPolicy(8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512);
    private final int[] sizes;

    public RasterPolicy(int... sizes) {
        if (sizes == null || sizes.length == 0 || sizes.length > 256)
            throw new IllegalArgumentException("光栅档位必须有界且非空");
        this.sizes = sizes.clone();
        int previous = 0;
        for (int size : this.sizes) {
            if (size <= previous || size > 4096)
                throw new IllegalArgumentException("光栅档位须严格递增且位于1..4096");
            previous = size;
        }
    }

    /** 向上选档并封顶，使摄像机运动产生的raster key集合保持有界。 */
    public int rasterEm(double desiredPixels) {
        if (!Double.isFinite(desiredPixels) || desiredPixels <= 0)
            throw new IllegalArgumentException("无效像素em");
        for (int size : sizes) if (size >= desiredPixels) return size;
        return sizes[sizes.length - 1];
    }

    public int[] sizes() {
        return sizes.clone();
    }
}
