package com.nectariich.create_flippies.client.design.ui;

import static com.nectariich.create_flippies.client.design.ui.Lang.t;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.nectariich.create_flippies.content.design_workbench.gauge.GaugeDial;
import com.nectariich.create_flippies.content.design_workbench.gauge.GaugeDraft;
import com.nectariich.create_flippies.content.procedural_gauge.GaugeFaceDefinition;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The gauge mode's Dial tab: what the gauge measures, and the settings of the face
 * generator its kind uses, the rotary dial's or the edgewise scale's: the range,
 * ticks, labels, name and units, the edgewise layout, the colours and the colour
 * bands. The face it prints becomes the material of the submesh picked with "D" in
 * the parts list or in the material editor, and the rows at the bottom say which
 * one that is and how the face is fitted to it: the UV rectangle it fills, and its
 * offset, scale, rotation and mirroring within that rectangle. A rotary dial's sweep is set in the
 * Motion tab, with the needle it moves.
 */
public final class DialTab {

    private DialTab() {}

    public static void build(Panel panel, WorkbenchHost host) {
        WorkbenchState state = host.state();
        GaugeDraft gauge = state.gauge;

        panel.button(t("gauge.quantity", gauge.quantity.label()), 0, 1, t("gauge.quantity.tooltip"), b -> {
            gauge.quantity = gauge.quantity.next();
            b.setMessage(t("gauge.quantity", gauge.quantity.label()));
            host.changed();
        });
        panel.row();

        panel.label(t("gauge.range"), 0xA0A0A0);
        panel.number(0, 3, gauge.min, any(host, value -> gauge.min = value), t("gauge.min"));
        panel.number(1, 3, gauge.max, any(host, value -> gauge.max = value), t("gauge.max"));
        panel.button(t("gauge.auto"), 2, 3, t("gauge.auto.tooltip"), b -> {
            gauge.autoTicks();
            host.rebuild();
        });
        panel.row();

        panel.label(t("gauge.ticks"), 0xA0A0A0);
        panel.number(0, 2, gauge.majorTick, positive(host, value -> gauge.majorTick = value), t("gauge.major"));
        panel.number(1, 2, gauge.minorTicks, whole(host, 0, 20, value -> gauge.minorTicks = value), t("gauge.minor"));
        panel.row();

        panel.label(t("gauge.labels"), 0xA0A0A0);
        panel.number(0, 2, gauge.decimals, whole(host, 0, 3, value -> gauge.decimals = value), t("gauge.decimals"));
        panel.number(1, 2, gauge.multiplier, value -> {
            if (value != 0) {
                gauge.multiplier = value;
                host.changed();
            }
        }, t("gauge.multiplier"));
        panel.row();

        panel.label(t("gauge.text"), 0xA0A0A0);
        text(panel, host, 0, gauge.name, value -> gauge.name = value, t("gauge.name_box"));
        text(panel, host, 1, gauge.units, value -> gauge.units = value, t("gauge.units"));
        panel.row();

        if (gauge.edgewise) {
            panel.label(t("gauge.layout"), 0xA0A0A0);
            toggle(panel, host, 0, () -> t(gauge.flipTicks ? "gauge.ticks.right" : "gauge.ticks.left"),
                    t("gauge.ticks.tooltip"), () -> gauge.flipTicks = !gauge.flipTicks);
            toggle(panel, host, 1, () -> t(gauge.nameOnSide ? "gauge.name.side" : "gauge.name.top"),
                    t("gauge.name.tooltip"), () -> gauge.nameOnSide = !gauge.nameOnSide);
            panel.row();
        }

        panel.label(t("gauge.colours"), 0xA0A0A0);
        RackTab.hex(panel, host, 0, gauge.faceColor, value -> gauge.faceColor = value, t("gauge.colour.face"));
        RackTab.hex(panel, host, 1, gauge.markingColor, value -> gauge.markingColor = value,
                t("gauge.colour.marking"));
        panel.row();

        panel.label(t("gauge.bands"), 0xA0A0A0);
        for (GaugeDraft.Band band : gauge.bands) {
            panel.number(0, 3, band.from, any(host, value -> band.from = value), t("gauge.band.from"));
            panel.number(1, 3, band.to, any(host, value -> band.to = value), t("gauge.band.to"));
            RackTab.hex(panel, host, 2, band.color, value -> band.color = value, t("gauge.band.colour"));
            panel.row();
        }
        Button add = panel.button(t("gauge.band.add"), 0, 2, t("gauge.band.add.tooltip"), b -> {
            // A new band covers the top fifth of the scale, in red, the usual danger zone
            float from = gauge.max - (gauge.max - gauge.min) / 5;
            gauge.bands.add(new GaugeDraft.Band(from, gauge.max, 0xA01010));
            host.rebuild();
        });
        add.active = gauge.bands.size() < GaugeFaceDefinition.MAX_BANDS;
        Button remove = panel.button(t("gauge.band.remove"), 1, 2, b -> {
            gauge.bands.remove(gauge.bands.size() - 1);
            host.rebuild();
        });
        remove.active = !gauge.bands.isEmpty();
        panel.row();

        // Where the face is printed, and how it is fitted there: the same rows the
        // screen tab places its viewport with
        PlacementSection.build(panel, host, gauge, "gauge");

        panel.label(() -> {
            GaugeDial built = gauge.build();
            return built.error() == null ? null : t(built.error()).withStyle(s -> s.withColor(0xFF8060));
        }, 0xA0A0A0);
        panel.label(host::status, 0xC0C0C0);
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

    /** A box for a short line of text in column {@code index} of two. */
    private static void text(Panel panel, WorkbenchHost host, int index, String value, Consumer<String> setter,
            Component label) {
        EditBox box = panel.add(new EditBox(panel.font, Panel.column(index, 2) + 1, panel.y + 1,
                Panel.columnWidth(2) - 2, 18, label));
        box.setMaxLength(24);
        box.setValue(value);
        box.setTooltip(Tooltip.create(label));
        box.setResponder(text -> {
            setter.accept(text);
            host.changed();
        });
    }

    private static NumberBox.Setter positive(WorkbenchHost host, NumberBox.Setter setter) {
        return value -> {
            if (value > 0) {
                setter.set(value);
                host.changed();
            }
        };
    }

    static NumberBox.Setter any(WorkbenchHost host, NumberBox.Setter setter) {
        return value -> {
            setter.set(value);
            host.changed();
        };
    }

    /** A setter for a whole number from {@code min} to {@code max}, ignoring anything else typed. */
    private static NumberBox.Setter whole(WorkbenchHost host, int min, int max, java.util.function.IntConsumer setter) {
        return value -> {
            int rounded = Math.round(value);
            if (rounded == value && rounded >= min && rounded <= max) {
                setter.accept(rounded);
                host.changed();
            }
        };
    }
}
