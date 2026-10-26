package com.nectariich.create_flippies.client.design.ui;

import static com.nectariich.create_flippies.client.design.ui.Lang.t;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.design_workbench.PlacementDraft;
import com.nectariich.create_flippies.content.design_workbench.SurfacePlacement;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The rows that say which submesh something is placed on by UV, and how it is fitted
 * there: the gauge's printed face in the Dial tab, the screen's viewport in the Screen
 * tab. Which submesh it is (given with the parts list's toggle or in the material
 * editor), Fit to its UVs and Whole texture, the UV rectangle, then the offset, scale,
 * rotation and mirroring within that rectangle, and Reset.
 *
 * Both tabs show the same rows over the same numbers ({@link PlacementDraft}); only
 * the words differ, each tab's under its own key prefix ({@code gauge.} for the dial,
 * {@code screen.} for the screen), so a tooltip can say "dial" or "screen".
 */
public final class PlacementSection {

    private PlacementSection() {}

    /**
     * @param prefix the key prefix under the workbench's strings the rows' words are read from
     */
    public static void build(Panel panel, WorkbenchHost host, PlacementDraft draft, String prefix) {
        WorkbenchState state = host.state();

        panel.gap(2);
        panel.label(t(prefix + ".printed"), 0xE0E0E0);
        panel.label(() -> placed(state, draft)
                ? t(prefix + ".printed.on", draft.face().replace("#", " / "))
                : t(prefix + ".error.face").withStyle(s -> s.withColor(0xFF8060)), 0xA0A0A0);
        boolean placed = placed(state, draft);
        Button fit = panel.button(t(prefix + ".uv.fit"), 0, 2, t(prefix + ".uv.fit.tooltip"), b -> {
            String face = draft.face();
            int split = face.indexOf('#');
            state.assignFace(face.substring(0, split), face.substring(split + 1));
            host.rebuild();
        });
        fit.active = placed && state.blob != null;
        Button whole = panel.button(t(prefix + ".uv.whole"), 1, 2, t(prefix + ".uv.whole.tooltip"), b -> {
            draft.setUv(null);
            host.rebuild();
        });
        whole.active = placed;
        panel.row();
        panel.label(() -> {
            float[] uv = draft.heldUv();
            return t(prefix + ".uv", NumberBox.format(uv[0]), NumberBox.format(uv[1]), NumberBox.format(uv[2]),
                    NumberBox.format(uv[3]));
        }, 0xA0A0A0);

        float[] placement = draft.placement();
        panel.label(t(prefix + ".offset"), 0xA0A0A0);
        panel.number(0, 2, placement[0], DialTab.any(host, value -> placement[0] = value), t(prefix + ".offset.x"));
        panel.number(1, 2, placement[1], DialTab.any(host, value -> placement[1] = value), t(prefix + ".offset.y"));
        panel.row();
        panel.label(t(prefix + ".scale"), 0xA0A0A0);
        panel.number(0, 2, placement[2], scale(host, value -> placement[2] = value), t(prefix + ".scale.x"));
        panel.number(1, 2, placement[3], scale(host, value -> placement[3] = value), t(prefix + ".scale.y"));
        panel.row();
        panel.label(t(prefix + ".rotation"), 0xA0A0A0);
        panel.number(0, 2, placement[4], value -> {
            if (value >= -360 && value <= 360) {
                placement[4] = value;
                host.changed();
            }
        }, t(prefix + ".rotation.box"));
        panel.row();
        panel.label(t(prefix + ".flip"), 0xA0A0A0);
        toggle(panel, host, 0, () -> t(draft.flipX() ? prefix + ".flip.x.on" : prefix + ".flip.x.off"),
                t(prefix + ".flip.x.tooltip"), () -> draft.setFlipX(!draft.flipX()));
        toggle(panel, host, 1, () -> t(draft.flipY() ? prefix + ".flip.y.on" : prefix + ".flip.y.off"),
                t(prefix + ".flip.y.tooltip"), () -> draft.setFlipY(!draft.flipY()));
        panel.button(t(prefix + ".placement.reset"), 2, 3, t(prefix + ".placement.reset.tooltip"), b -> {
            draft.resetPlacement();
            host.rebuild();
        });
        panel.row();
    }

    /** Whether the draft is on a submesh this model has. */
    private static boolean placed(WorkbenchState state, PlacementDraft draft) {
        return !draft.face().isEmpty() && state.elementKeys().contains(draft.face());
    }

    /** A button in column {@code index} of three whose label follows what pressing it changed. */
    private static void toggle(Panel panel, WorkbenchHost host, int index, Supplier<Component> label,
            Component tooltip, Runnable press) {
        panel.button(label.get(), index, 3, tooltip, b -> {
            press.run();
            b.setMessage(label.get());
            host.changed();
        });
    }

    /** A setter for a placement scale, ignoring anything too small for a saved design to keep. */
    private static NumberBox.Setter scale(WorkbenchHost host, NumberBox.Setter setter) {
        return value -> {
            if (value >= SurfacePlacement.MIN_SCALE) {
                setter.set(value);
                host.changed();
            }
        };
    }
}
