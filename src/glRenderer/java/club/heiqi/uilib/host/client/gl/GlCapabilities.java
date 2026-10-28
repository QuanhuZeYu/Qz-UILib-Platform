package club.heiqi.uilib.host.client.gl;

/** 记录实际driver能力；contextIdentity标识不透明的宿主上下文身份。 */
public final class GlCapabilities {
    public final Object contextIdentity;
    public final boolean openGL32, samplerObjects;

    public GlCapabilities(Object identity, boolean openGL32, boolean samplers) {
        if (identity == null) throw new NullPointerException("identity");
        contextIdentity = identity;
        this.openGL32 = openGL32;
        samplerObjects = samplers;
    }
}
