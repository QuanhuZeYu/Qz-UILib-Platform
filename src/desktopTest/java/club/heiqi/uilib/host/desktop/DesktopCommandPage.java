package club.heiqi.uilib.host.desktop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneButton;
import club.heiqi.uilib.ui.scene.control.SceneCommand;
import club.heiqi.uilib.ui.scene.control.SceneLabel;
import club.heiqi.uilib.ui.scene.control.SceneLayer;
import club.heiqi.uilib.ui.scene.control.SceneTextArea;
import club.heiqi.uilib.ui.scene.edit.SceneEditState;
import club.heiqi.uilib.ui.scene.host.ScenePage;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.input.SceneKey;
import club.heiqi.uilib.ui.scene.input.SceneShortcut;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;

/** 中性键真实宿主验收fixture；编辑、命令、范围与绘制全部使用正式控件和管线。 */
final class DesktopCommandPage implements ScenePage {
    Signal<Boolean> enabled, readOnly, modal;
    Signal<Integer> actions, activations, pageEdits, modalActions, escapes, submissions;
    SceneEditState editing;
    SceneNode root, button, second, field, modalButton;
    final List<Integer> activationSnapshots = new ArrayList<>();

    @Override public void build(ScenePageContext ctx) {
        enabled = ctx.domain().signal(true); readOnly = ctx.domain().signal(false); modal = ctx.domain().signal(false);
        actions = ctx.domain().signal(0); activations = ctx.domain().signal(0); pageEdits = ctx.domain().signal(0);
        modalActions = ctx.domain().signal(0); escapes = ctx.domain().signal(0); submissions = ctx.domain().signal(0);
        editing = new SceneEditState(ctx.domain(), "第一行 alpha\n第二行 beta\n第三行 gamma", SceneEditState.Mode.MULTI_LINE);
        SceneCommand action = SceneCommand.of(enabled, () -> {
            activationSnapshots.add(activations.get()); actions.set(actions.get() + 1);
        });
        SceneCommand pageEdit = SceneCommand.of(() -> true, () -> pageEdits.set(pageEdits.get() + 1));
        SceneCommand closeModal = SceneCommand.of(() -> true, () -> {
            modalActions.set(modalActions.get() + 1); modal.set(false);
        });
        button = SceneButton.create(ctx, () -> "普通按钮 · " + activations.get(), ctx.theme(),
                () -> activations.set(activations.get() + 1));
        second = SceneButton.create(ctx, () -> "打开命令模态", ctx.theme(), () -> modal.set(true));
        field = SceneTextArea.create(ctx, editing, ctx.theme(), readOnly, submission -> {
            submissions.set(submissions.get() + 1); editing.accept(submission, submission.text);
        }).size(Size.fill(1), Size.fixed(150));
        modalButton = SceneButton.create(ctx, () -> "关闭当前模态（Ctrl+V）", ctx.theme(), () -> modal.set(false));
        SceneNode layer = SceneLayer.create(ctx,
                () -> new SceneLayer.Policy(0, SceneLayer.Participation.INTERACTIVE),
                (node, source, viewport, measured) -> new LayoutBox(60, 70, measured.width, measured.height),
                Collections.emptyList());
        layer.size(Size.fixed(420), Size.fixed(160)).padding(20).gap(12).modal(true).initialFocus(modalButton)
                .bind(NodeBinding.value(ctx.theme(), (n, t) -> n.background(t.panel)))
                .bind(NodeBinding.value(modal, SceneNode::visible))
                .bind(NodeBinding.shortcuts(SceneShortcut.Scope.MODAL,
                        SceneShortcut.primary(SceneKey.V, closeModal), SceneShortcut.primary(SceneKey.S, closeModal)))
                .onEscape(() -> { escapes.set(escapes.get() + 1); modal.set(false); return true; })
                .add(SceneLabel.create(ctx, () -> "独立模态命令范围", ctx.theme())).add(modalButton);
        root = new SceneNode().size(Size.fill(1), Size.fill(1)).padding(24).gap(14).initialFocus(button).restoreFocus(field)
                .bind(NodeBinding.value(ctx.theme(), (n, t) -> n.background(t.panel)))
                .bind(NodeBinding.shortcuts(SceneShortcut.Scope.PAGE,
                        SceneShortcut.primary(SceneKey.S, action), SceneShortcut.primary(SceneKey.F, action),
                        SceneShortcut.primary(SceneKey.C, pageEdit), SceneShortcut.primary(SceneKey.X, pageEdit),
                        SceneShortcut.primary(SceneKey.V, pageEdit), SceneShortcut.primary(SceneKey.Z, pageEdit)))
                .onEscape(() -> { escapes.set(escapes.get() + 1); return true; })
                .add(SceneLabel.create(ctx, () -> "中性命令键 · 正式桌面输入", ctx.theme()))
                .add(button).add(second).add(field).add(layer);
        ctx.mountOwned(root);
    }
}
