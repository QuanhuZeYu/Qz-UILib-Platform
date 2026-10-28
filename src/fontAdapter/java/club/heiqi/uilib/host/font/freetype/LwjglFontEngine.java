package club.heiqi.uilib.host.font.freetype;

import club.heiqi.uilib.ui.scene.text.font.nativefont.FontEngine;
import club.heiqi.uilib.ui.scene.text.font.nativefont.FontSession;

/** openSession在调用线程初始化native并打开FT/HB作用域。 */
public final class LwjglFontEngine implements FontEngine {
    @Override
    public FontSession openSession() {
        return new NativeFontService();
    }
}
