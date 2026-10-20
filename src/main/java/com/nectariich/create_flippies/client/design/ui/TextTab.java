package com.nectariich.create_flippies.client.design.ui;

import static com.nectariich.create_flippies.client.design.ui.Lang.t;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.client.design.DesignCamera;
import com.nectariich.create_flippies.client.design.DesignFonts;
import com.nectariich.create_flippies.content.design_workbench.DesignText;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * The workbench's Text tab. The camera looks straight down at the bench while it is
 * open (see {@link DesignCamera#setTop}), boxes are drawn and moved on the preview
 * itself (see {@link TextBoxEditor}), and this column lists the boxes and edits the
 * selected one: its words, font and face, style, colour, size, alignment, rotation,
 * glow, height above the plate, and whether it rides on the moving parts.
 */
public final class TextTab {

    /** The built-in fonts in the order the font button steps through them. */
    private static final String[] BUILT_INS = { DesignText.DEFAULT_FONT, "minecraft:uniform", "minecraft:alt",
            "minecraft:illageralt" };

    /** The colours offered as swatches, beside the hex box. */
    private static final int[] SWATCHES = { 0xFFFFFF, 0xC0C0C0, 0x404040, 0xFF4040, 0xFF9020, 0xFFE040, 0x40E060,
            0x40E0FF, 0x4080FF, 0xE060FF };

    private TextTab() {}

    public static void build(Panel panel, WorkbenchHost host) {
        WorkbenchState state = host.state();

        panel.button(t("text.reset_view"), 0, 2, t("text.reset_view.tooltip"), b -> DesignCamera.resetView());
        Button delete = panel.button(t("text.delete"), 1, 2, t("text.delete.tooltip"), b -> {
            if (state.selectedText() != null) {
                state.texts.remove(state.selected);
                state.selected = -1;
                host.rebuild();
            }
        });
        delete.active = state.selectedText() != null;
        panel.row();
        panel.label(t(state.info == null ? "text.hint.no_model" : "text.hint"), 0x909090);
        panel.gap(2);

        // The boxes, one button each; the selected one is the one pressed in
        for (int i = 0; i < state.texts.size(); i++) {
            int index = i;
            String words = state.texts.get(i).text;
            Button choice = panel.button(Component.literal((i + 1) + ": ")
                    .append(Component.literal(words.isBlank() ? "-" : words)), 0, 1, b -> {
                        state.selected = index;
                        host.rebuild();
                    });
            choice.active = state.selected != i;
            panel.row();
        }

        TextDraft text = state.selectedText();
        if (text != null)
            selected(panel, host, text);

        PartsHierarchy.build(panel, host);
    }

    private static void selected(Panel panel, WorkbenchHost host, TextDraft text) {
        panel.gap(2);
        panel.label(t("text.selected", host.state().selected + 1), 0xE0E0E0);

        EditBox words = panel.add(new EditBox(panel.font, Panel.X + 1, panel.y + 1, Panel.WIDTH - 2, 18,
                t("text.words")));
        words.setMaxLength(DesignText.MAX_LENGTH);
        words.setValue(text.text);
        words.setTooltip(Tooltip.create(t("text.words")));
        words.setResponder(value -> {
            text.text = value;
            host.changed();
        });
        panel.row();

        // Font family, then the face within a font file family
        List<String> fonts = fontOptions();
        panel.button(t("text.font", fontName(text.font)), 0, 1, t("text.font.tooltip"), b -> {
            int at = fonts.indexOf(familyKey(text.font));
            String next = fonts.get((at + 1) % fonts.size());
            text.font = next;
            host.rebuild();
        });
        panel.row();
        DesignFonts.Face face = DesignFonts.face(text.font);
        DesignFonts.Family family = face == null ? null : DesignFonts.familyOf(face.hash());
        Button faces = panel.button(t("text.face", face == null ? t("text.face.none") : Component.literal(face.style())),
                0, 2, t("text.face.tooltip"), b -> {
                    if (family == null || family.faces().size() < 2)
                        return;
                    int at = family.faces().indexOf(face);
                    text.font = DesignText.TTF_PREFIX + family.faces().get((at + 1) % family.faces().size()).hash();
                    host.rebuild();
                });
        faces.active = family != null && family.faces().size() > 1;
        panel.button(t("text.rescan"), 1, 2, t("text.rescan.tooltip"), b -> {
            DesignFonts.rescan();
            host.rebuild();
        });
        panel.row();
        panel.label(() -> t("text.fonts_folder", DesignFonts.folder().toString()), 0x808080);
        panel.gap(2);

        // Style toggles, each showing its own effect
        style(panel, host, 0, "B", ChatFormatting.BOLD, text.bold, "text.bold", () -> text.bold = !text.bold);
        style(panel, host, 1, "I", ChatFormatting.ITALIC, text.italic, "text.italic", () -> text.italic = !text.italic);
        style(panel, host, 2, "U", ChatFormatting.UNDERLINE, text.underline, "text.underline",
                () -> text.underline = !text.underline);
        style(panel, host, 3, "S", ChatFormatting.STRIKETHROUGH, text.strikethrough, "text.strikethrough",
                () -> text.strikethrough = !text.strikethrough);
        panel.row();

        // Colour as a hex box and swatches, then size
        EditBox hex = panel.add(new EditBox(panel.font, Panel.column(0, 2) + 1, panel.y + 1,
                Panel.columnWidth(2) - 2, 18, t("text.color")));
        hex.setMaxLength(7);
        hex.setFilter(value -> value.matches("#?[0-9a-fA-F]{0,6}"));
        hex.setValue(String.format("#%06X", text.color));
        hex.setTextColor(readable(text.color));
        hex.setTooltip(Tooltip.create(t("text.color")));
        hex.setResponder(value -> {
            String digits = value.startsWith("#") ? value.substring(1) : value;
            if (digits.length() == 6) {
                text.color = Integer.parseInt(digits, 16);
                hex.setTextColor(readable(text.color));
                host.changed();
            }
        });
        panel.number(1, 2, text.size, value -> {
            if (value > 0) {
                text.size = value;
                host.changed();
            }
        }, t("text.size"));
        panel.row();
        int swatch = (Panel.WIDTH - (SWATCHES.length - 1) * 2) / SWATCHES.length;
        for (int i = 0; i < SWATCHES.length; i++) {
            int color = SWATCHES[i];
            panel.add(new Swatch(Panel.X + i * (swatch + 2), panel.y, swatch, 12, color, text.color == color, () -> {
                text.color = color;
                host.rebuild();
            }));
        }
        panel.gap(16);

        // Alignment and rotation
        panel.button(t("text.align." + text.align), 0, 2, t("text.align.tooltip"), b -> {
            text.align = (text.align + 1) % 3;
            b.setMessage(t("text.align." + text.align));
            host.changed();
        });
        panel.button(t("text.rotation", text.quarters * 90), 1, 2, t("text.rotation.tooltip"), b -> {
            text.quarters = (text.quarters + 1) % 4;
            b.setMessage(t("text.rotation", text.quarters * 90));
            host.changed();
        });
        panel.row();

        panel.add(new AbstractSliderButton(Panel.X, panel.y, Panel.WIDTH, 20, glowLabel(text.glow), text.glow) {
            @Override
            protected void updateMessage() {
                setMessage(glowLabel((float) value));
            }

            @Override
            protected void applyValue() {
                text.glow = (float) value;
                host.changed();
            }
        }).setTooltip(Tooltip.create(t("text.glow.tooltip")));
        panel.row();

        panel.number(0, 2, text.y, value -> {
            text.y = Math.max(0, value);
            host.changed();
        }, t("text.height"));
        panel.button(t(text.onMoving ? "text.on_moving.yes" : "text.on_moving.no"), 1, 2, t("text.on_moving.tooltip"),
                b -> {
                    text.onMoving = !text.onMoving;
                    b.setMessage(t(text.onMoving ? "text.on_moving.yes" : "text.on_moving.no"));
                    host.changed();
                });
        panel.row();
        panel.gap(2);
    }

    private static void style(Panel panel, WorkbenchHost host, int column, String letter, ChatFormatting format,
            boolean on, String key, Runnable toggle) {
        Component label = Component.literal(letter).withStyle(format)
                .withStyle(on ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
        panel.button(label, column, 4, t(key), b -> {
            toggle.run();
            host.rebuild();
        });
    }

    /** The colour itself as the hex box's text colour, unless it is too dark to read on the box. */
    private static int readable(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return r * 3 + g * 6 + b < 600 ? 0xFFE0E0E0 : 0xFF000000 | color;
    }

    private static Component glowLabel(float glow) {
        return t("text.glow", Math.round(glow * 100));
    }

    /**
     * Every font the font button steps through: the built-ins, then one entry per
     * font family in the fonts folder (its first face; the face button picks among
     * the rest).
     */
    private static List<String> fontOptions() {
        List<String> options = new ArrayList<>(List.of(BUILT_INS));
        for (DesignFonts.Family family : DesignFonts.families())
            if (!family.faces().isEmpty())
                options.add(DesignText.TTF_PREFIX + family.faces().get(0).hash());
        return options;
    }

    /**
     * The option a font is listed under: itself for a built-in, its family's first
     * face for a font file in the folder, and nothing (so the next press starts from
     * the top) for a file this client does not have in its folder.
     */
    private static String familyKey(String font) {
        if (!font.startsWith(DesignText.TTF_PREFIX))
            return font;
        DesignFonts.Face face = DesignFonts.face(font);
        DesignFonts.Family family = face == null ? null : DesignFonts.familyOf(face.hash());
        return family == null || family.faces().isEmpty() ? "" : DesignText.TTF_PREFIX + family.faces().get(0).hash();
    }

    private static Component fontName(String font) {
        if (!font.startsWith(DesignText.TTF_PREFIX))
            return t("font." + font.substring(font.indexOf(':') + 1));
        DesignFonts.Face face = DesignFonts.face(font);
        DesignFonts.Family family = face == null ? null : DesignFonts.familyOf(face.hash());
        return family == null ? t("font.missing") : Component.literal(family.name());
    }

    /** A colour to click, outlined when it is the text's colour. */
    private static final class Swatch extends AbstractWidget {

        private final int color;
        private final boolean chosen;
        private final Runnable onPick;

        Swatch(int x, int y, int width, int height, int color, boolean chosen, Runnable onPick) {
            super(x, y, width, height, Component.literal(String.format("#%06X", color)));
            this.color = color;
            this.chosen = chosen;
            this.onPick = onPick;
            setTooltip(Tooltip.create(getMessage()));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            int x = getX(), y = getY();
            graphics.fill(x, y, x + width, y + height, chosen ? 0xFFFFFFFF : isHovered() ? 0xFFA0A0A0 : 0xFF000000);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF000000 | color);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            onPick.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }
}
