package club.heiqi.uilib.host.embedded;

/** 累积宿主已标准化的滚轮格数；整格按零方向取整，保留正反输入相抵后的余量。 */
public final class ScrollAccumulator {
    private double remainder;

    public int add(double standardSteps) {
        remainder += standardSteps;
        int whole = (int) remainder;
        remainder -= whole;
        return whole;
    }

    /** 输入联系结束时丢弃旧余量，新联系不能继承旧页的半格。 */
    public void reset() { remainder = 0; }
}
