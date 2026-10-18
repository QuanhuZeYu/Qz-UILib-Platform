package club.heiqi.uilib.host.embedded;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import club.heiqi.uilib.ui.scene.host.ScenePageSession;
import club.heiqi.uilib.ui.scene.host.SceneEditInputAdapter;
import club.heiqi.uilib.ui.scene.host.SceneEscapeInputAdapter;
import club.heiqi.uilib.ui.scene.host.SceneKeyInputAdapter;
import club.heiqi.uilib.ui.scene.host.SceneKeyPressInputAdapter;
import club.heiqi.uilib.ui.scene.host.SceneTabInputAdapter;
import club.heiqi.uilib.ui.scene.input.SceneEditEvent;
import club.heiqi.uilib.ui.scene.input.SceneKey;
import club.heiqi.uilib.ui.scene.input.SceneKeyEvent;
import club.heiqi.uilib.ui.scene.input.SceneNavigationEvent;
import club.heiqi.uilib.ui.scene.input.ScenePointerEvent;

/** 版本输入只提供语义键、物理段和历史耗尽事实；焦点、范围、手势仍归正式session。 */
public final class EmbeddedSceneInputs {
    public enum Key { OTHER, TAB, ENTER, KEYPAD_ENTER, SPACE, ESCAPE, LEFT_SHIFT, RIGHT_SHIFT,
        LEFT, RIGHT, UP, DOWN, HOME, END, PAGE_UP, PAGE_DOWN, BACKSPACE, DELETE, A, C, X, V, Z, Y,
        B, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, W,
        F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
        DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,
        SLASH, COMMA, PERIOD, MINUS, EQUAL, SEMICOLON, APOSTROPHE, LEFT_BRACKET, RIGHT_BRACKET, BACKSLASH, GRAVE, INSERT }
    private final ScenePageSession page;
    private final SceneEditEvent.Clipboard clipboard;
    private final SceneEditInputAdapter edits = new SceneEditInputAdapter();
    private final SceneKeyInputAdapter keys;
    private final Map<Integer, SceneKey> physicalKeys = new HashMap<>();
    private final SceneTabInputAdapter tab;
    private final SceneEscapeInputAdapter escape;
    private final SceneKeyPressInputAdapter enter, keypadEnter, space;
    private boolean active, pointerSuspended = true, control, shift, alt, extra, repeat;
    private char pendingHigh;
    private Object pendingOwner;

    public EmbeddedSceneInputs(ScenePageSession page, SceneEditEvent.Clipboard clipboard, Runnable closeRequest) {
        this.page = java.util.Objects.requireNonNull(page, "page");
        this.clipboard = java.util.Objects.requireNonNull(clipboard, "clipboard");
        keys = new SceneKeyInputAdapter(event -> page.key(event,
                event.key == SceneKey.ESCAPE && event.phase == SceneKeyEvent.Phase.DOWN ? closeRequest : null));
        tab = new SceneTabInputAdapter(direction -> keys.key(new SceneKeyEvent(SceneKey.TAB, control,
                direction == SceneNavigationEvent.PREVIOUS, alt, extra, repeat, clipboard)));
        escape = new SceneEscapeInputAdapter(() -> keys.key(event(SceneKey.ESCAPE)));
        enter = new SceneKeyPressInputAdapter(() -> keys.key(event(SceneKey.ENTER)));
        keypadEnter = new SceneKeyPressInputAdapter(() -> keys.key(event(SceneKey.KEYPAD_ENTER)));
        space = new SceneKeyPressInputAdapter(() -> keys.key(event(SceneKey.SPACE)));
    }
    private SceneKeyEvent event(SceneKey key) {
        return new SceneKeyEvent(key, control, shift, alt, extra, repeat, clipboard);
    }
    public void availability(boolean available) {
        if (active && !available) { page.focusLost(); pointerSuspended = true; pendingHigh = 0; }
        active = available;
        edits.availability(available); keys.availability(available); tab.availability(available); escape.availability(available);
        enter.availability(available); keypadEnter.availability(available); space.availability(available);
    }
    /** 消费者完成本批历史后调用，不能用采集前的未来物理状态越过旧尾部。 */
    public void historyDrained(Set<Integer> physical, Set<Key> special, boolean buttonsReleased) {
        edits.historyDrained(physical);
        // 旧消费者的special可只含导航键；已见过的编辑键仍按其原物理码确认释放。
        physicalKeys.keySet().retainAll(physical);
        Set<SceneKey> named = EnumSet.noneOf(SceneKey.class);
        named.addAll(physicalKeys.values());
        for (Key key : special) { SceneKey value = named(key); if (value != null) named.add(value); }
        keys.historyDrained(named);
        tab.historyDrained(special.contains(Key.TAB), special.contains(Key.LEFT_SHIFT), special.contains(Key.RIGHT_SHIFT));
        escape.historyDrained(special.contains(Key.ESCAPE));
        enter.historyDrained(special.contains(Key.ENTER));
        keypadEnter.historyDrained(special.contains(Key.KEYPAD_ENTER));
        space.historyDrained(special.contains(Key.SPACE));
        if (active && pointerSuspended && buttonsReleased) { page.pointer(ScenePointerEvent.allButtonsReleased()); pointerSuspended = false; }
    }
    public void pointer(ScenePointerEvent event) {
        pendingHigh = 0;
        if (active && !pointerSuspended || event.kind == ScenePointerEvent.Kind.UP) page.pointer(event);
    }
    public void key(int physical, Key key, boolean down, boolean repeat, boolean control, boolean shift) {
        key(physical, key, down, repeat, control, shift, false, false);
    }
    public void key(int physical, Key key, boolean down, boolean repeat, boolean control, boolean shift,
            boolean alt, boolean extra) {
        this.control = control; this.shift = shift; this.alt = alt; this.extra = extra; this.repeat = repeat;
        edits.key(physical, down, repeat);
        SceneKey named = named(key);
        if (named != null) {
            if (down) physicalKeys.put(physical, named); else physicalKeys.remove(physical);
            // 一次文本的UTF-16高/低代理可能各带同一键DOWN，只有命令边界才截断待配对字符。
            if (down && (control || alt || extra || !textKey(named))) pendingHigh = 0;
        }
        switch (key) {
            case LEFT_SHIFT: tab.shift(true, down); return;
            case RIGHT_SHIFT: tab.shift(false, down); return;
            case TAB: tab.tab(down, repeat); release(named, down); return;
            case ENTER: enter.key(down, repeat); release(named, down); return;
            case KEYPAD_ENTER: keypadEnter.key(down, repeat); release(named, down); return;
            case ESCAPE: escape.key(down, repeat); release(named, down); return;
            case SPACE: space.key(down, repeat); release(named, down); return;
            default: break;
        }
        if (named == null) return;
        if (down) keys.key(event(named)); else release(named, false);
    }
    private static boolean textKey(SceneKey key) {
        if (key.letter() || key.name().startsWith("DIGIT_")) return true;
        switch (key) {
            case SPACE: case SLASH: case COMMA: case PERIOD: case MINUS: case EQUAL:
            case SEMICOLON: case APOSTROPHE: case LEFT_BRACKET: case RIGHT_BRACKET: case BACKSLASH: case GRAVE:
                return true;
            default: return false;
        }
    }
    private void release(SceneKey key, boolean down) {
        if (!down) keys.key(event(key).withPhase(SceneKeyEvent.Phase.UP));
    }
    private static SceneKey named(Key key) {
        switch (key) {
            case OTHER: case LEFT_SHIFT: case RIGHT_SHIFT: return null;
            case KEYPAD_ENTER: return SceneKey.KEYPAD_ENTER;
            default: return SceneKey.valueOf(key.name());
        }
    }
    /** 字符事件与按键事件分开；UTF-16宿主可逐单元递交，代理对只留在同一编辑目标。 */
    public void character(char character) {
        if (!edits.available() || character < 32 || control) return;
        Object[] current = {null};
        page.runtime().domain().execute(() -> current[0] = page.runtime().focusRouter().focused().get());
        if (pendingOwner != current[0]) { pendingHigh = 0; pendingOwner = current[0]; }
        if (Character.isHighSurrogate(character)) { pendingHigh = character; return; }
        if (Character.isLowSurrogate(character)) {
            if (pendingHigh != 0) { page.edit(SceneEditEvent.text(new String(new char[] {pendingHigh, character}))); pendingHigh = 0; }
        } else { pendingHigh = 0; page.edit(SceneEditEvent.text(String.valueOf(character))); }
    }
    /** 原生整段文本入口复用相同物理段资格，组字身份由宿主桥继续递交核心。 */
    public boolean acceptsNativeText() { return edits.available() && !control; }
    public void text(String text) { for (int i = 0; i < text.length(); i++) character(text.charAt(i)); }
    public void composition(String text, boolean active) {
        pendingHigh = 0;
        if (edits.available()) page.edit(SceneEditEvent.composition(text, active));
    }
}
