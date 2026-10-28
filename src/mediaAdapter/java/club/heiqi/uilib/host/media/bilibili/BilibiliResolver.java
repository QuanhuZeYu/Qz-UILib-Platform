package club.heiqi.uilib.host.media.bilibili;

import club.heiqi.uilib.ui.media.MediaResolver;
import club.heiqi.uilib.ui.media.spi.MediaCancellation;
import java.io.IOException;

/** 识别平台内容并选择点播或直播解析流程，返回中性媒体来源。 */
public final class BilibiliResolver implements MediaResolver {
    @Override public boolean supports(String address) {
        try { return BilibiliAddress.parse(address).allowed(); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    @Override public Result resolve(Request request, MediaCancellation cancellation) throws Exception {
        cancellation.check();
        BilibiliAddress address = BilibiliAddress.parse(request.address);
        if (!address.allowed()) throw new IOException("请选择Bilibili视频或直播间地址");
        address = BilibiliHttp.expand(address, cancellation);
        return address.live() ? BilibiliLive.resolve(address, request, cancellation)
                : BilibiliVideo.resolve(address, request, cancellation);
    }
}
