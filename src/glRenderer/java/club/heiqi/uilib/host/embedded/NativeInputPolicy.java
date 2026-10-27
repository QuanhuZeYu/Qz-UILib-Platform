package club.heiqi.uilib.host.embedded;

/** 宿主提供命令修饰键事实；共享转换不读取操作系统，也不解释 Core 命令。 */
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
