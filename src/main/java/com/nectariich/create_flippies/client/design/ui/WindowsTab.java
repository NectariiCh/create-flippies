package com.nectariich.create_flippies.client.design.ui;

import static com.nectariich.create_flippies.client.design.ui.Lang.t;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.design_workbench.annunciator.AnnunciatorCsv;
import com.nectariich.create_flippies.content.design_workbench.annunciator.AnnunciatorDraft;
import com.nectariich.create_flippies.content.design_workbench.annunciator.AnnunciatorRack;
import com.nectariich.create_flippies.content.design_workbench.annunciator.LensColor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The rack mode's Windows tab: the CSV import and export, a map of the rack's
 * windows to pick one from, and the picked window's legend, colour and id.
 *
 * The map is drawn in the lit colours, since a plain window is grey on the bench
 * until it lights and the colour would otherwise be invisible while designing.
 */
public final class WindowsTab {

    /** The largest a window is drawn on the map, in GUI pixels. */
    private static final int MAX_CELL = 16;

    private WindowsTab() {}

    public static void build(Panel panel, WorkbenchHost host) {
        WorkbenchState state = host.state();
        AnnunciatorDraft rack = state.rack;
        rack.ensureTiles();

        panel.button(t("csv.import"), 0, 3, t("csv.import.tooltip"), b -> host.browse());
        panel.button(t("csv.paste"), 1, 3, t("csv.paste.tooltip"), b -> importTexts(host,
                List.of(Minecraft.getInstance().keyboardHandler.getClipboard())));
        panel.button(t("csv.copy"), 2, 3, t("csv.copy.tooltip"), b -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(AnnunciatorCsv.export(rack.build()));
            host.setStatus(t("csv.copied"));
        });
        panel.row();
        panel.button(t(rack.resizeToCsv ? "csv.resize.on" : "csv.resize.off"), 0, 1, t("csv.resize.tooltip"),
                b -> {
                    rack.resizeToCsv = !rack.resizeToCsv;
                    b.setMessage(t(rack.resizeToCsv ? "csv.resize.on" : "csv.resize.off"));
                });
        panel.row();
        panel.label(host::status, 0xC0C0C0);
        panel.gap(3);

        // The map, as big as fits the panel, windows kept to their shape
        AnnunciatorRack built = rack.build();
        float aspect = built.error() == null ? built.cellHeight() / built.cellWidth() : 1;
        int cell = Math.max(4, Math.min(MAX_CELL, (Panel.WIDTH - 1) / rack.cellsX));
        int cellHeight = Math.max(4, Math.min(MAX_CELL * 2, Math.round(cell * aspect)));
        if (state.selectedWindow < 0 || state.selectedWindow >= rack.cellsX * rack.cellsY)
            state.selectedWindow = 0;
        panel.add(new Grid(Panel.X, panel.y, cell, cellHeight, host));
        panel.gap(cellHeight * rack.cellsY + 1 + Panel.GAP);

        int row = state.selectedWindow / rack.cellsX, column = state.selectedWindow % rack.cellsX;
        AnnunciatorDraft.TileDraft tile = rack.tile(row, column);
        panel.label(t("window.selected", row + 1, column + 1), 0xE0E0E0);

        panel.label(t("window.legend"), 0xA0A0A0);
        EditBox legend = panel.add(new EditBox(panel.font, Panel.X + 1, panel.y + 1, Panel.WIDTH - 2, 18,
                t("window.legend")));
        legend.setMaxLength(AnnunciatorRack.MAX_LEGEND_LENGTH);
        legend.setValue(tile.legend.replace("\n", "|"));
        legend.setTooltip(Tooltip.create(t("window.legend.tooltip")));
        legend.setEditable(!tile.blank);
        legend.setResponder(value -> {
            String next = AnnunciatorCsv.normaliseLegend(value);
            // An id that was only ever the legend's slug keeps following the legend
            if (tile.id.isEmpty() || tile.id.equals(AnnunciatorRack.slugFor(tile.legend)))
                tile.id = AnnunciatorRack.slugFor(next);
            tile.legend = next;
            host.changed();
        });
        panel.row();

        panel.button(colourLabel(tile.color), 0, 2, t("window.colour.tooltip"), b -> {
            tile.color = tile.color.next();
            host.rebuild();
        });
        panel.button(t(tile.blank ? "window.blank.on" : "window.blank.off"), 1, 2, t("window.blank.tooltip"), b -> {
            tile.blank = !tile.blank;
            if (!tile.blank && tile.id.isEmpty())
                tile.id = AnnunciatorRack.slugFor(tile.legend);
            host.rebuild();
        });
        panel.row();

        panel.label(t("window.id"), 0xA0A0A0);
        EditBox id = panel.add(new EditBox(panel.font, Panel.X + 1, panel.y + 1, Panel.WIDTH - 2, 18,
                t("window.id")));
        id.setMaxLength(AnnunciatorRack.MAX_ID);
        id.setValue(tile.id);
        id.setTooltip(Tooltip.create(t("window.id.tooltip")));
        id.setEditable(!tile.blank);
        id.setResponder(value -> {
            tile.id = value.trim();
            host.changed();
        });
        panel.row();
    }

    /**
     * Imports CSV text into the rack. Every file is read before any is applied, so
     * one that cannot be read leaves the rack as it was, with the reason on the
     * status line.
     */
    public static void importTexts(WorkbenchHost host, List<String> texts) {
        try {
            List<Component> messages = AnnunciatorCsv.importFiles(host.state().rack, texts);
            MutableComponent status = Component.empty();
            for (int i = 0; i < messages.size(); i++)
                status.append(i == 0 ? Component.empty() : Component.literal("; ")).append(messages.get(i));
            host.setStatus(status);
        } catch (AnnunciatorCsv.CsvException e) {
            host.setStatus(t("csv.failed", e.reason()));
        }
        host.rebuild();
    }

    private static Component colourLabel(LensColor color) {
        return t("window.colour", t("colour." + color.wireName()));
    }

    /** The windows as a clickable map: lit colours, blanked cells dark and crossed, the picked one outlined. */
    private static final class Grid extends AbstractWidget {

        private final int cell, cellHeight;
        private final WorkbenchHost host;

        Grid(int x, int y, int cell, int cellHeight, WorkbenchHost host) {
            super(x, y, cell * host.state().rack.cellsX + 1, cellHeight * host.state().rack.cellsY + 1,
                    t("window.map"));
            this.cell = cell;
            this.cellHeight = cellHeight;
            this.host = host;
        }

        private AnnunciatorDraft rack() {
            return host.state().rack;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            AnnunciatorDraft rack = rack();
            int x = getX(), y = getY();
            graphics.fill(x, y, x + width, y + height, 0xFF000000 | rack.frameColor);
            int hovered = cellAt(mouseX, mouseY);
            for (int row = 0; row < rack.cellsY; row++)
                for (int column = 0; column < rack.cellsX; column++) {
                    AnnunciatorDraft.TileDraft tile = rack.tile(row, column);
                    int left = x + column * cell + 1, top = y + row * cellHeight + 1;
                    int right = left + cell - 1, bottom = top + cellHeight - 1;
                    if (tile.blank) {
                        graphics.fill(left, top, right, bottom, 0xFF202022);
                    } else {
                        // A spare window, with no legend, is drawn dimmer
                        int lit = tile.color.litRgb();
                        int color = tile.legend.isBlank() ? (lit >> 1) & 0x7F7F7F : lit;
                        graphics.fill(left, top, right, bottom, 0xFF000000 | color);
                        if (tile.color.keepsColorUnlit())
                            graphics.fill(left, bottom - 2, right, bottom, 0xFF000000 | (lit >> 2) & 0x3F3F3F);
                    }
                    int index = row * rack.cellsX + column;
                    if (index == host.state().selectedWindow)
                        graphics.renderOutline(left - 1, top - 1, cell + 1, cellHeight + 1, 0xFFFFFFFF);
                    else if (index == hovered)
                        graphics.renderOutline(left - 1, top - 1, cell + 1, cellHeight + 1, 0xFFA0A0A0);
                }
            if (hovered >= 0)
                graphics.renderComponentTooltip(Minecraft.getInstance().font, tooltip(hovered), mouseX, mouseY);
        }

        private List<Component> tooltip(int index) {
            AnnunciatorDraft rack = rack();
            AnnunciatorDraft.TileDraft tile = rack.tiles()[index];
            List<Component> lines = new ArrayList<>();
            lines.add(t("window.selected", index / rack.cellsX + 1, index % rack.cellsX + 1));
            if (tile.blank) {
                lines.add(t("window.blank.on"));
                return lines;
            }
            for (String line : tile.legend.split("\n"))
                if (!line.isBlank())
                    lines.add(Component.literal(line).withStyle(s -> s.withColor(tile.color.litRgb())));
            lines.add(t("colour." + tile.color.wireName()).withStyle(s -> s.withColor(0x808080)));
            if (!tile.id.isEmpty())
                lines.add(Component.literal(tile.id).withStyle(s -> s.withColor(0x808080)));
            return lines;
        }

        private int cellAt(double mouseX, double mouseY) {
            AnnunciatorDraft rack = rack();
            int column = (int) Math.floor((mouseX - getX() - 1) / cell);
            int row = (int) Math.floor((mouseY - getY() - 1) / cellHeight);
            if (!visible || column < 0 || row < 0 || column >= rack.cellsX || row >= rack.cellsY)
                return -1;
            return row * rack.cellsX + column;
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            int index = cellAt(mouseX, mouseY);
            if (index >= 0 && index != host.state().selectedWindow) {
                host.state().selectedWindow = index;
                host.rebuild();
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }
}
