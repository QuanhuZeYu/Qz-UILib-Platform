package club.heiqi.uilib.host.media.openal;

import club.heiqi.uilib.ui.media.spi.MediaAudioSinkFactory;
import club.heiqi.uilib.ui.media.spi.MediaAudioSink;

import club.heiqi.uilib.ui.media.MediaBudget;


/** 通过显式装配的release LWJGL3/OpenAL Soft打开独立音频设备。 */
public final class OpenAlAudioSinkFactory implements MediaAudioSinkFactory {
    private final String deviceName;
    public OpenAlAudioSinkFactory() { this(null); }
    public OpenAlAudioSinkFactory(String deviceName) { this.deviceName=deviceName; }
    @Override public MediaAudioSink open(MediaBudget budget) throws Exception { return new OpenAlAudioSink(deviceName,budget); }
}
