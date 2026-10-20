package com.nectariich.create_flippies.client.design.ui;

import static com.nectariich.create_flippies.client.design.ui.Lang.t;

import java.util.List;
import java.util.function.IntConsumer;

import com.nectariich.create_flippies.content.design_workbench.DesignText;
import com.nectariich.create_flippies.content.design_workbench.annunciator.AnnunciatorDraft;
import com.nectariich.create_flippies.content.design_workbench.annunciator.AnnunciatorRack;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The rack mode's Rack tab: how big the rack is and how many windows it holds, the
 * frame around them, and how the frame, lenses and legends look. Every change
 * regenerates the model on the bench.
 */
public final class RackTab {

    /** The fonts a legend can be printed in, in the order the button steps through them. */
    private static final List<String> FONTS = List.of(DesignText.DEFAULT_FONT, "minecraft:uniform", "minecraft:alt",
            "minecraft:illageralt");

    private RackTab() {}

    public static void build(Panel panel, WorkbenchHost host) {
        AnnunciatorDraft rack = host.state().rack;

        panel.label(t("rack.cells"), 0xA0A0A0);
        panel.number(0, 2, rack.cellsX, value -> setCells(host, value, rack.cellsY), t("rack.cells.x"));
        panel.number(1, 2, rack.cellsY, value -> setCells(host, rack.cellsX, value), t("rack.cells.y"));
        panel.row();

        panel.label(t("rack.size"), 0xA0A0A0);
        panel.number(0, 2, rack.width, positive(host, value -> rack.width = value), t("rack.width"));
        panel.number(1, 2, rack.height, positive(host, value -> rack.height = value), t("rack.height"));
        panel.row();

        panel.label(t("rack.depth"), 0xA0A0A0);
        panel.number(0, 2, rack.depth, positive(host, value -> rack.depth = value), t("rack.depth.box"));
        panel.number(1, 2, rack.recess, any(host, value -> rack.recess = value), t("rack.recess"));
        panel.row();

        panel.label(t("rack.frame"), 0xA0A0A0);
        panel.number(0, 2, rack.bezel, any(host, value -> rack.bezel = value), t("rack.bezel"));
        panel.number(1, 2, rack.rib, any(host, value -> rack.rib = value), t("rack.rib"));
        panel.row();

        panel.label(t("rack.legend"), 0xA0A0A0);
        panel.number(0, 2, rack.legendSize, positive(host, value -> rack.legendSize = value), t("rack.legend.size"));
        panel.number(1, 2, rack.legendPadding, any(host, value -> rack.legendPadding = value),
                t("rack.legend.padding"));
        panel.row();
        panel.button(t("rack.font", fontLabel(rack.font)), 0, 2, t("rack.font.tooltip"), b -> {
            rack.font = FONTS.get((Math.max(0, FONTS.indexOf(rack.font)) + 1) % FONTS.size());
            b.setMessage(t("rack.font", fontLabel(rack.font)));
            host.changed();
        });
        panel.number(1, 2, rack.unlitTint, positive(host, value -> rack.unlitTint = value), t("rack.tint"));
        panel.row();

        panel.label(t("rack.colours"), 0xA0A0A0);
        hex(panel, host, 0, rack.frameColor, value -> rack.frameColor = value, t("rack.colour.frame"));
        hex(panel, host, 1, rack.lensColor, value -> rack.lensColor = value, t("rack.colour.lens"));
        hex(panel, host, 2, rack.legendColor, value -> rack.legendColor = value, t("rack.colour.legend"));
        panel.row();

        panel.label(() -> {
            AnnunciatorRack built = rack.build();
            if (built.error() != null)
                return t(built.error()).withStyle(s -> s.withColor(0xFF8060));
            return t("rack.summary", NumberBox.format(built.width), NumberBox.format(built.height),
                    NumberBox.format(built.depth), NumberBox.format(built.cellWidth()),
                    NumberBox.format(built.cellHeight()));
        }, 0xA0A0A0);
        panel.label(host::status, 0xC0C0C0);
        panel.gap(3);

        panel.button(t("rack.reset"), 0, 1, t("rack.reset.tooltip"), b -> {
            // Keeps the windows: only the layout and the style go back
            AnnunciatorDraft defaults = AnnunciatorDraft.of(AnnunciatorRack.defaults());
            rack.width = defaults.width;
            rack.height = defaults.height;
            rack.depth = defaults.depth;
            rack.bezel = defaults.bezel;
            rack.rib = defaults.rib;
            rack.recess = defaults.recess;
            rack.frameColor = defaults.frameColor;
            rack.lensColor = defaults.lensColor;
            rack.legendColor = defaults.legendColor;
            rack.unlitTint = defaults.unlitTint;
            rack.legendSize = defaults.legendSize;
            rack.legendPadding = defaults.legendPadding;
            rack.font = defaults.font;
            host.rebuild();
        });
        panel.row();
    }

    private static void setCells(WorkbenchHost host, float across, float down) {
        int x = Math.round(across), y = Math.round(down);
        if (x < 1 || y < 1 || x > AnnunciatorRack.MAX_CELLS || y > AnnunciatorRack.MAX_CELLS)
            return;
        AnnunciatorDraft rack = host.state().rack;
        rack.cellsX = x;
        rack.cellsY = y;
        rack.ensureTiles();
        host.changed();
    }

    /** A setter that takes positive values and re-poses the preview. */
    private static NumberBox.Setter positive(WorkbenchHost host, NumberBox.Setter setter) {
        return value -> {
            if (value > 0) {
                setter.set(value);
                host.changed();
            }
        };
    }

    /** A setter that takes zero or more and re-poses the preview. */
    private static NumberBox.Setter any(WorkbenchHost host, NumberBox.Setter setter) {
        return value -> {
            if (value >= 0) {
                setter.set(value);
                host.changed();
            }
        };
    }

    /** A box for a colour as #RRGGBB in column {@code index} of three, taken once all six digits are in. */
    static void hex(Panel panel, WorkbenchHost host, int index, int color, IntConsumer setter,
            Component label) {
        EditBox box = panel.add(new EditBox(panel.font, Panel.column(index, 3) + 1, panel.y + 1,
                Panel.columnWidth(3) - 2, 18, label));
        box.setMaxLength(7);
        box.setFilter(value -> value.matches("#?[0-9a-fA-F]{0,6}"));
        box.setValue(String.format("#%06X", color));
        box.setTextColor(0xFF000000 | readable(color));
        box.setTooltip(Tooltip.create(label));
        box.setResponder(value -> {
            String digits = value.startsWith("#") ? value.substring(1) : value;
            if (digits.length() == 6) {
                int parsed = Integer.parseInt(digits, 16);
                setter.accept(parsed);
                box.setTextColor(0xFF000000 | readable(parsed));
                host.changed();
            }
        });
    }

    /** The colour itself, or light grey when it would be too dark to read on the box. */
    private static int readable(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return r * 3 + g * 6 + b < 600 ? 0xE0E0E0 : color;
    }

    private static Component fontLabel(String font) {
        return t("font." + font.substring(font.indexOf(':') + 1));
    }
}
