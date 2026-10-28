package club.heiqi.uilib.host.embedded;

/** 根据宿主提供的Primary键策略转换修饰键事实，命令解释归Core。 */
public final class NativeInputPolicy {
    private final boolean primaryUsesMeta;

    public NativeInputPolicy(boolean primaryUsesMeta) {
        this.primaryUsesMeta = primaryUsesMeta;
    }

    public boolean primary(boolean control, boolean meta) {
        return primaryUsesMeta ? meta : control;
    }

    public boolean extra(boolean control, boolean meta) {
        return primaryUsesMeta ? control : meta;
    }
}
