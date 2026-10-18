package club.heiqi.uilib.ui.scene.integration;

import club.heiqi.uilib.testing.TestApplication;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneImage;
import club.heiqi.uilib.ui.scene.host.ScenePageSession;
import club.heiqi.uilib.ui.scene.image.ImageContent;
import club.heiqi.uilib.ui.scene.image.ImageOptions;
import club.heiqi.uilib.ui.scene.image.ImageSource;
import club.heiqi.uilib.ui.scene.image.ImageState;
import club.heiqi.uilib.ui.scene.image.SharedImageService;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.PaintCommand;
import club.heiqi.uilib.ui.scene.text.FontStyle;
import club.heiqi.uilib.ui.scene.text.StyledText;
import club.heiqi.uilib.ui.scene.text.TextContent;
import club.heiqi.uilib.ui.scene.text.TextInput;
import club.heiqi.uilib.ui.scene.text.TextStyleOverride;
import club.heiqi.uilib.ui.scene.text.TextStyleOverrideSpan;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** 共同 ItemImageFacts 的真实字体、覆盖层与输入验收；具体宿主可供给自己的提示转换入口。 */
public final class ItemImageFactsScenario extends TestApplication {
    private final ReactiveDomain domain = ReactiveDomain.direct();
    private final BiFunction<String, int[], TextContent> tooltipFactory;

    public ItemImageFactsScenario() {
        this(ItemImageFactsScenario::styledTooltip);
    }

    public ItemImageFactsScenario(BiFunction<String, int[], TextContent> tooltipFactory) {
        this.tooltipFactory = Objects.requireNonNull(tooltipFactory, "tooltipFactory");
    }

    // 中性 fixture 直接声明属性；Minecraft 转换由所属仓以同一场景验证。
    private static TextContent styledTooltip(String original, int[] palette) {
        String text = "钻石 提示\n正文重复正文重复正文重复正文";
        int end = text.indexOf('\n');
        TextStyleOverride title = TextStyleOverride.builder().rgb(palette[10]).weight(700)
                .posture(FontStyle.Posture.ITALIC).underline(true).strike(true).build();
        return TextContent.styled(new StyledText(TextInput.string(text), original,
                Arrays.asList(new TextStyleOverrideSpan(0, end, title),
                        new TextStyleOverrideSpan(end, text.length(), TextStyleOverride.INHERIT)),
                TextStyleOverride.INHERIT, Collections.emptyList()));
    }

    public static void main(String[] args) throws Exception {
        new ItemImageFactsScenario().run(args);
    }

    @Override
    protected void runTests() throws Exception {
        scenario(
                "宿主facts覆盖层被动提示同代clip及底层click保持",
                () -> {
                    club.heiqi.uilib.ui.scene.text.font.FontTextService actualText =
                            own(
                                    new club.heiqi.uilib.ui.scene.text.font.FontTextService(
                                            club.heiqi.uilib.host.font.IsolatedFontEngine
                                                    .shared()));
                    AtomicInteger tooltipLayouts = new AtomicInteger();
                    AtomicInteger quantityLayouts = new AtomicInteger();
                    club.heiqi.uilib.ui.scene.text.SceneTextMeasurer measured =
                            new club.heiqi.uilib.ui.scene.text.SceneTextMeasurer() {
                                @Override
                                public club.heiqi.uilib.ui.scene.text.TextLayout layout(
                                        String value,
                                        club.heiqi.uilib.ui.scene.text.TextStyle style,
                                        int width) {
                                    return actualText.layout(value, style, width);
                                }

                                @Override
                                public club.heiqi.uilib.ui.scene.text.TextLayout layout(
                                        String value,
                                        club.heiqi.uilib.ui.scene.text.TextStyle style,
                                        int width,
                                        club.heiqi.uilib.ui.scene.text.TextPolicy policy) {
                                    if ("32".equals(value)) quantityLayouts.incrementAndGet();
                                    return actualText.layout(value, style, width, policy);
                                }

                                @Override
                                public club.heiqi.uilib.ui.scene.text.TextLayout layout(
                                        club.heiqi.uilib.ui.scene.text.AttributedText value,
                                        float width,
                                        club.heiqi.uilib.ui.scene.text.TextPolicy policy) {
                                    tooltipLayouts.incrementAndGet();
                                    return actualText.layout(value, width, policy);
                                }
                            };
                    int[] palette = new int[16];
                    java.util.Arrays.fill(palette, 0xffffff);
                    palette[10] = 0x55ff55;
                    final club.heiqi.uilib.ui.scene.image.ItemImageFacts[] facts = {
                        new club.heiqi.uilib.ui.scene.image.ItemImageFacts(
                                "32",
                                tooltipFactory.apply(
                                                "§a§l§o§n§m钻石 提示§r\n正文重复正文重复正文重复正文", palette),
                                true,
                                .5,
                                0xff80ff00)
                    };
                    palette[10] = 0;
                    SharedImageService s =
                            own(
                                    new SharedImageService(
                                            input -> {
                                                final club.heiqi.uilib.ui.scene.image.ItemImageFacts
                                                        prepared = facts[0];
                                                return () ->
                                                        new ImageContent(16, 16) {
                                                            @Override
                                                            public club.heiqi.uilib.ui.scene.image
                                                                            .ItemImageFacts
                                                                    itemFacts() {
                                                                return prepared;
                                                            }
                                                        };
                                            },
                                            SharedImageService.Budget.defaults()));
                    SoftwareSceneScenario.SoftwareHost host =
                            new SoftwareSceneScenario.SoftwareHost();
                    host.width = 80;
                    host.height = 100;
                    SceneNode[] image = {null}, under = {null}, pane = {null};
                    Signal<ImageOptions>[] opts = new Signal[1];
                    Signal<club.heiqi.uilib.ui.scene.control.SceneTheme>[] theme = new Signal[1];
                    Signal<Boolean>[] enabled = new Signal[1];
                    Signal<Integer>[] clicks = new Signal[1];
                    ImageSource source = ImageSource.resource("host:facts");
                    ScenePageSession page =
                            ScenePageSession.open(
                                    domain,
                                    host,
                                    measured,
                                    c -> {
                                        opts[0] =
                                                domain.signal(
                                                        ImageOptions.defaults()
                                                                .size(
                                                                        Size.fixed(60),
                                                                        Size.fixed(40)));
                                        theme[0] =
                                                domain.signal(
                                                        club.heiqi.uilib.ui.scene.control.SceneTheme
                                                                .dark());
                                        enabled[0] = domain.signal(true);
                                        clicks[0] = domain.signal(0);
                                        under[0] =
                                                new SceneNode()
                                                        .size(Size.fixed(80), Size.fixed(80))
                                                        .onClick(
                                                                () ->
                                                                        clicks[0].set(
                                                                                clicks[0].get()
                                                                                        + 1));
                                        image[0] =
                                                SceneImage.themed(
                                                        s,
                                                        domain.signal(source),
                                                        opts[0],
                                                        theme[0]);
                                        pane[0] =
                                                new SceneNode()
                                                        .size(Size.fixed(40), Size.fixed(30))
                                                        .clip(true)
                                                        .bind(
                                                                club.heiqi.uilib.ui.scene.runtime
                                                                        .NodeBinding.value(
                                                                        enabled[0],
                                                                        (node, value) ->
                                                                                node.enabled(
                                                                                        value)))
                                                        .add(image[0]);
                                        c.mount(under[0]);
                                        c.mount(pane[0]);
                                    });
                    onClose(page::close);
                    for (int i = 0; i < 40; i++) {
                        page.frame();
                        s.drain();
                        Thread.sleep(5);
                    }
                    page.pointer(club.heiqi.uilib.ui.scene.input.ScenePointerEvent.move(10, 10));
                    page.frame();
                    domain.execute(
                            () -> {
                                require(image[0].clickAction() == null, "被动提示建立dummyclick");
                                require(
                                        page.runtime()
                                                        .pointerRouter()
                                                        .passiveHovered(
                                                                page.pipeline().current().geometry)
                                                == under[0],
                                        "默认物品option应全关");
                                opts[0].set(
                                        opts[0].get()
                                                .quantity(true)
                                                .durability(true)
                                                .tooltip(true));
                            });
                    page.frame();
                    domain.execute(
                            () -> {
                                require(
                                        page.runtime()
                                                                .pointerRouter()
                                                                .hovered(
                                                                        page.pipeline()
                                                                                .current()
                                                                                .geometry)
                                                        == under[0]
                                                && page.runtime()
                                                                .pointerRouter()
                                                                .passiveHovered(
                                                                        page.pipeline()
                                                                                .current()
                                                                                .geometry)
                                                        == image[0],
                                        "passive与click命中未分离");
                                club.heiqi.uilib.ui.scene.paint.PaintPlan plan =
                                        page.pipeline().current().paint;
                                PaintCommand command =
                                        plan.commands().stream()
                                                .filter(cmd -> cmd.kind == PaintCommand.Kind.IMAGE)
                                                .findFirst()
                                                .get();
                                require(command.readyOverlay != null, "数量耐久缺少成功覆盖层");
                                boolean count = false, bar = false, tip = false, formatted = false;
                                for (PaintCommand cmd : command.readyOverlay.commands()) {
                                    count |= cmd.text != null && cmd.text.logicalText.equals("32");
                                    bar |=
                                            cmd.kind == PaintCommand.Kind.RECT
                                                    && cmd.color == 0xff80ff00;
                                }
                                require(count && bar, "数量耐久未进入成功覆盖层");
                                for (PaintCommand cmd : plan.commands()) {
                                    require(
                                            cmd.text == null || !cmd.text.logicalText.equals("32"),
                                            "数量绕过成功分支无条件叠画");
                                    if (cmd.text != null
                                            && cmd.text.logicalText.contains("钻石 提示")) {
                                        tip = true;
                                        require(
                                                cmd.clip != null
                                                        && cmd.clip.x >= 0
                                                        && cmd.clip.y >= 0
                                                        && cmd.clip.x + cmd.clip.width <= 80
                                                        && cmd.clip.y + cmd.clip.height <= 100,
                                                "tooltip未在viewport裁剪");
                                        require(!cmd.text.logicalText.contains("§"), "原版§未正式解析");
                                        for (club.heiqi.uilib.ui.scene.text.TextLayout.Line line :
                                                cmd.text.lines())
                                            for (club.heiqi.uilib.ui.scene.text.TextLayout.Glyph
                                                    glyph : line.glyphs())
                                                formatted |=
                                                        glyph.resolvedStyle != null
                                                                && glyph.resolvedStyle
                                                                                .fontStyle
                                                                                .weight
                                                                        == 700
                                                                && glyph.resolvedStyle
                                                                                .fontStyle
                                                                                .posture
                                                                        == club.heiqi.uilib.ui.scene
                                                                                .text.FontStyle
                                                                                .Posture.ITALIC
                                                                && glyph.resolvedStyle
                                                                        .effects
                                                                        .underline
                                                                && glyph.resolvedStyle
                                                                        .effects
                                                                        .strike
                                                                && glyph.resolvedStyle.color
                                                                        == 0xff55ff55;
                                    }
                                }
                                require(tip && formatted, "提示缺失或palette快照/bold属性丢失");
                                for (club.heiqi.uilib.ui.scene.paint.PaintPlan fallback :
                                        new club.heiqi.uilib.ui.scene.paint.PaintPlan[] {
                                            command.pendingPaint, command.failedPaint
                                        })
                                    if (fallback != null)
                                        for (PaintCommand cmd : fallback.commands())
                                            require(
                                                    (cmd.text == null
                                                                    || !cmd.text.logicalText.equals(
                                                                            "32"))
                                                            && !(cmd.kind == PaintCommand.Kind.RECT
                                                                    && cmd.color == 0xff80ff00),
                                                    "数量耐久泄漏到未成功分支");
                            });
                    // 同来源/字体代际先缩成零盒再恢复：空求解不能命中旧成功key并留下null数量。
                    ImageOptions[] previousOptions = {null};
                    domain.execute(
                            () -> {
                                previousOptions[0] = opts[0].get();
                                opts[0].set(previousOptions[0].size(Size.fixed(0), Size.fixed(0)));
                            });
                    page.frame();
                    domain.execute(
                            () ->
                                    require(
                                            page.pipeline()
                                                                    .current()
                                                                    .geometry
                                                                    .get(image[0])
                                                                    .box
                                                                    .width
                                                            == 0
                                                    && page.pipeline()
                                                                    .current()
                                                                    .geometry
                                                                    .get(image[0])
                                                                    .box
                                                                    .height
                                                            == 0,
                                            "数量恢复反例没有进入真实零盒"));
                    domain.execute(() -> opts[0].set(previousOptions[0]));
                    page.frame();
                    domain.execute(
                            () ->
                                    require(
                                            page.pipeline().current().paint.commands().stream()
                                                    .filter(
                                                            cmd ->
                                                                    cmd.kind
                                                                            == PaintCommand.Kind
                                                                                    .IMAGE)
                                                    .anyMatch(
                                                            cmd ->
                                                                    cmd
                                                                            .readyOverlay
                                                                            .commands()
                                                                            .stream()
                                                                            .anyMatch(
                                                                                    overlay ->
                                                                                            overlay.text
                                                                                                            != null
                                                                                                    && "32"
                                                                                                            .equals(
                                                                                                                    overlay.text
                                                                                                                            .logicalText))),
                                            "零盒恢复后旧cache key丢失数量"));
                    int oldQuantityLayouts = quantityLayouts.get();
                    int oldLayouts = tooltipLayouts.get();
                    page.pointer(club.heiqi.uilib.ui.scene.input.ScenePointerEvent.move(12, 10));
                    page.frame();
                    require(tooltipLayouts.get() == oldLayouts, "hover重复解析/排版同一tooltip");
                    require(quantityLayouts.get() == oldQuantityLayouts, "hover重复排版同一数量");
                    domain.execute(
                            () ->
                                    theme[0].set(
                                            theme[0].get()
                                                    .withTextStyle(
                                                            new club.heiqi.uilib.ui.scene.text
                                                                    .TextStyle(
                                                                    "default", 24, 0xff224466))));
                    page.frame();
                    require(tooltipLayouts.get() == oldLayouts + 1, "当前主题字号未使tooltip派生失效");
                    domain.execute(
                            () -> {
                                boolean scaled = false;
                                for (PaintCommand cmd : page.pipeline().current().paint.commands())
                                    if (cmd.text != null
                                            && cmd.text.logicalText.contains("钻石 提示")) {
                                        for (club.heiqi.uilib.ui.scene.text.TextLayout.Line line :
                                                cmd.text.lines())
                                            for (club.heiqi.uilib.ui.scene.text.TextLayout.Glyph
                                                    glyph : line.glyphs())
                                                scaled |=
                                                        glyph.size == 24
                                                                && glyph.resolvedStyle.color
                                                                        == 0xff55ff55;
                                    }
                                require(scaled, "tooltip冻结旧字号或丢失转换颜色");
                            });
                    actualText.reload(
                            club.heiqi.uilib.ui.scene.text.font.FontTextService.defaultSources(
                                    getClass().getClassLoader(), 2));
                    host.metrics++;
                    page.frame();
                    require(tooltipLayouts.get() == oldLayouts + 2, "同主题字体资源代际未重排tooltip");
                    host.width = 100;
                    page.frame();
                    require(tooltipLayouts.get() == oldLayouts + 3, "viewport变宽未重排tooltip");
                    host.width = 80;
                    page.frame();
                    SceneNode[] focusBefore = {null};
                    domain.execute(
                            () -> focusBefore[0] = page.runtime().focusRouter().focused().get());
                    page.pointer(club.heiqi.uilib.ui.scene.input.ScenePointerEvent.down(0, 10, 10));
                    page.pointer(club.heiqi.uilib.ui.scene.input.ScenePointerEvent.up(0, 10, 10));
                    page.frame();
                    domain.execute(
                            () ->
                                    require(
                                            clicks[0].get() == 1
                                                    && page.runtime().focusRouter().focused().get()
                                                            == focusBefore[0],
                                            "提示抢走底层click/focus"));
                    page.pointer(club.heiqi.uilib.ui.scene.input.ScenePointerEvent.move(50, 10));
                    page.frame();
                    domain.execute(
                            () ->
                                    require(
                                            page.runtime()
                                                            .pointerRouter()
                                                            .passiveHovered(
                                                                    page.pipeline()
                                                                            .current()
                                                                            .geometry)
                                                    == under[0],
                                            "clip外旧passive命中"));
                    page.pointer(club.heiqi.uilib.ui.scene.input.ScenePointerEvent.move(10, 10));
                    domain.execute(() -> enabled[0].set(false));
                    page.frame();
                    domain.execute(
                            () ->
                                    require(
                                            page.runtime()
                                                            .pointerRouter()
                                                            .passiveHovered(
                                                                    page.pipeline()
                                                                            .current()
                                                                            .geometry)
                                                    == under[0],
                                            "disabled祖先仍显示tip"));
                    domain.execute(
                            () -> {
                                enabled[0].set(true);
                                facts[0] =
                                        new club.heiqi.uilib.ui.scene.image.ItemImageFacts(
                                                "7", "新语言提示", false, 1, 0xff00ff00);
                                theme[0].set(club.heiqi.uilib.ui.scene.control.SceneTheme.light());
                            });
                    s.reload(source);
                    for (int i = 0; i < 40; i++) {
                        page.frame();
                        s.drain();
                        Thread.sleep(5);
                    }
                    domain.execute(
                            () -> {
                                boolean fresh = false, old = false;
                                for (PaintCommand cmd :
                                        page.pipeline().current().paint.resourceCommands())
                                    if (cmd.text != null) {
                                        fresh |= cmd.text.logicalText.equals("新语言提示");
                                        old |= cmd.text.logicalText.contains("钻石 提示");
                                    }
                                require(fresh && !old, "来源正规reload未更新tooltip事实");
                            });
                    long[] identity = {0};
                    domain.execute(
                            () -> identity[0] = SceneImage.state(image[0]).get().content.identity);
                    s.presentation(identity[0], false, "item-render", "host failure");
                    page.frame();
                    domain.execute(
                            () -> {
                                ImageState state = SceneImage.state(image[0]).get();
                                require(
                                        state.status == ImageState.Status.FAILED
                                                && state.stage.equals("item-render")
                                                && state.source == source,
                                        "宿主绘制失败未局部归属source/stage");
                                require(!page.ended(), "物品失败关整页");
                            });
                });
    }
}
