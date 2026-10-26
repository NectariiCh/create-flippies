package com.nectariich.create_flippies.client.design.ui;

import static com.nectariich.create_flippies.client.design.ui.Lang.t;

import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignMaterial;

import dev.nectariich.hypergraphical.api.MeshInfo;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * The model's parts as a list in the workbench's panel, one row each, so the player
 * can say what each part is: a small icon coloured by its role (grey for the body,
 * amber for a moving part, lamp yellow for one that lights up), a visibility box that
 * hides the part in the preview to see behind it (an editor aid, never saved), the
 * name, and toggles: "M" for the parts that move together, for a button "L" for
 * what lights up with the lamp, and for a breaker control switch "I" for the parts of
 * its latching flag (cyan). A part is either moving or the flag, never both, so
 * turning one on turns the other off.
 *
 * Every part with materials has an expander that lists its material elements under
 * it, the faces of the part drawn with one material, whatever the kind: a single
 * body often carries several materials (a clear lens and a black ring moulded as one
 * piece), and the list is where materials are told apart. On a button each element
 * has its own "L" too, so the lamp can light a whole part, one material of it, or
 * both; the part icon shows lamp yellow when the part or any of its elements lights.
 * Which parts are expanded is the editor's business only and never saved.
 * Double-clicking an element opens the material editor on it; an element whose
 * material was edited shows its edited colour inside its chip.
 *
 * On a gauge, "M" picks the needle, and each element has a "D" that prints the
 * gauge's face (its dial) on it in place of its material; only one element has it
 * at a time, so giving it to another takes it off the first.
 *
 * On a screen nothing moves, so there is no "M", and the element's toggle is "S":
 * the submesh that is the screen's glass, again one at a time.
 *
 * One widget per row, so the rows scroll with the rest of the panel like any other
 * widget.
 */
public final class PartsHierarchy {

    public static final int ROW_HEIGHT = 12;

    private static final int BODY = 0xFF8C8C8C, MOVING = 0xFFE0A030, LIGHT = 0xFFFFE070, INDICATOR = 0xFF50C8E0,
            DIAL = 0xFF80D080;

    /** How close together two clicks on one element must be to open it, in milliseconds. */
    private static final long DOUBLE_CLICK_MILLIS = 350;

    /** The element clicked last, and when, for telling a double click. */
    private static String lastClicked;
    private static long lastClickMillis;

    private PartsHierarchy() {}

    /** Adds the header and a row per part (and per element of an expanded part) to the panel. */
    public static void build(Panel panel, WorkbenchHost host) {
        WorkbenchState state = host.state();
        if (state.info == null)
            return;
        panel.label(t("parts.header", state.parts.size()), 0xA0A0A0);
        panel.gap(1);
        for (String part : state.parts) {
            panel.add(new Row(panel.font, panel.y, host, part, null));
            panel.gap(ROW_HEIGHT);
            if (state.expanded.contains(part))
                for (MeshInfo.Element element : state.elementsOf(part)) {
                    panel.add(new Row(panel.font, panel.y, host, part, element));
                    panel.gap(ROW_HEIGHT);
                }
        }
        panel.gap(3);
    }

    /** One row: a part, or one material element of a part. */
    private static final class Row extends AbstractWidget {

        private static final int TOGGLE_W = 11;

        private final Font font;
        private final WorkbenchHost host;
        private final String part;
        private final MeshInfo.Element element;

        Row(Font font, int y, WorkbenchHost host, String part, MeshInfo.Element element) {
            super(Panel.X, y, Panel.WIDTH, ROW_HEIGHT - 1,
                    Component.literal(element == null ? part : element.material()));
            this.font = font;
            this.host = host;
            this.part = part;
            this.element = element;
            boolean button = host.state().kind.isButton();
            boolean gauge = host.state().kind.isGauge();
            boolean screen = host.state().kind.isScreen();
            setTooltip(Tooltip.create(t(element != null
                    ? button ? "parts.element.button.tooltip" : gauge ? "parts.element.gauge.tooltip"
                            : screen ? "parts.element.screen.tooltip" : "parts.element.tooltip"
                    : host.state().kind.isBreaker() ? "parts.row.breaker.tooltip"
                            : button ? "parts.row.button.tooltip" : gauge ? "parts.row.gauge.tooltip"
                                    : screen ? "parts.row.screen.tooltip" : "parts.row.tooltip")));
        }

        private WorkbenchState state() {
            return host.state();
        }

        private String key() {
            return ControlDesign.elementKey(part, element.material());
        }

        // Where things sit across the row
        private int expanderX() {
            return getX();
        }

        private int iconX() {
            return getX() + (element == null ? 10 : 22);
        }

        private int checkX() {
            return getX() + 21;
        }

        private int nameX() {
            return getX() + 33;
        }

        private int lightX() {
            return getX() + width - TOGGLE_W;
        }

        private int movingX() {
            return showsLight() || showsIndicator() ? lightX() - TOGGLE_W - 2 : getX() + width - TOGGLE_W;
        }

        /** A breaker's "I" toggle takes the place a button's "L" has, at the right end of the row. */
        private boolean showsIndicator() {
            return element == null && state().kind.isBreaker();
        }

        /** A gauge's "D", on each element, which prints the face on it, in the place a button's "L" has. */
        private boolean showsDial() {
            return element != null && (state().kind.isGauge() || state().kind.isScreen());
        }

        /** Nothing on a screen moves, so its parts have no "M". */
        private boolean showsMoving() {
            return element == null && !state().kind.isScreen();
        }

        /** Only a button lights up, by whole part or by element. */
        private boolean showsLight() {
            return state().kind.isButton();
        }

        private boolean hasExpander() {
            return element == null && !state().elementsOf(part).isEmpty();
        }

        /** What this row's "L" names in the design's lights: the part, or the element's key. */
        private String lightKey() {
            return element == null ? part : key();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            WorkbenchState state = state();
            int x = getX(), y = getY();
            if (isHovered())
                graphics.fill(x - 2, y, x + width + 2, y + height, 0x30FFFFFF);

            if (hasExpander())
                graphics.drawString(font, state.expanded.contains(part) ? "▾" : "▸", expanderX() + 1, y + 2,
                        0xC0C0C0, false);

            boolean moving = element == null && state.moving.contains(part);
            // The icon shows whether anything of the part lights; the toggle only what this row names
            boolean light = element == null ? state.lightsUp(part) : state.lights.contains(key());
            boolean lightOn = showsLight() && state.lights.contains(lightKey());
            boolean indicator = showsIndicator() && state.indicatorParts.contains(part);
            drawIcon(graphics, iconX(), y + 1, moving, light, indicator, element != null);
            DesignMaterial edited = element == null ? null : state.materials.get(key());
            if (edited != null)
                graphics.fill(iconX() + 3, y + 4, iconX() + 6, y + 7, 0xFF000000 | edited.color());

            if (element == null) {
                boolean visible = !state.hidden.contains(part);
                int cx = checkX(), cy = y + 1;
                graphics.fill(cx, cy, cx + 9, cy + 9, 0xFFA0A0A0);
                graphics.fill(cx + 1, cy + 1, cx + 8, cy + 8, 0xFF202020);
                if (visible)
                    graphics.fill(cx + 2, cy + 2, cx + 7, cy + 7, 0xFFE0E0E0);
            }

            int right = (showsLight() || showsIndicator() || showsDial() ? lightX() : getX() + width)
                    - (element == null ? TOGGLE_W + 4 : 2);
            String name = getMessage().getString();
            int nameColor = element == null && state.hidden.contains(part) ? 0x707070 : 0xE0E0E0;
            if (font.width(name) > right - nameX())
                name = font.plainSubstrByWidth(name, right - nameX() - font.width("..")) + "..";
            graphics.drawString(font, name, nameX(), y + 2, nameColor, false);

            if (showsMoving())
                drawToggle(graphics, movingX(), y, "M", moving, MOVING);
            if (showsLight())
                drawToggle(graphics, lightX(), y, "L", lightOn, LIGHT);
            if (showsIndicator())
                drawToggle(graphics, lightX(), y, "I", indicator, INDICATOR);
            if (showsDial())
                drawToggle(graphics, lightX(), y, state.kind.isScreen() ? "S" : "D",
                        state.isFace(part, element.material()), DIAL);
        }

        /**
         * A little block for a part, a flat chip for an element, in the role's
         * colour: grey body, amber moving, cyan a breaker's flag, lamp yellow lit (a
         * moving part that also lights is amber with a lit centre).
         */
        private static void drawIcon(GuiGraphics graphics, int x, int y, boolean moving, boolean light,
                boolean indicator, boolean element) {
            int base = moving ? MOVING : indicator ? INDICATOR : light ? LIGHT : BODY;
            if (element) {
                graphics.fill(x + 1, y + 1, x + 8, y + 8, 0xFF000000);
                graphics.fill(x + 2, y + 2, x + 7, y + 7, base);
                return;
            }
            graphics.fill(x, y, x + 9, y + 9, darker(base));
            graphics.fill(x, y, x + 8, y + 8, base);
            graphics.fill(x, y, x + 8, y + 1, lighter(base));
            graphics.fill(x, y, x + 1, y + 8, lighter(base));
            if (moving && light)
                graphics.fill(x + 2, y + 2, x + 6, y + 6, LIGHT);
        }

        private void drawToggle(GuiGraphics graphics, int x, int y, String letter, boolean on, int color) {
            graphics.fill(x, y, x + TOGGLE_W, y + 10, on ? color : 0xFF505050);
            graphics.fill(x + 1, y + 1, x + TOGGLE_W - 1, y + 9, on ? color : 0xFF1C1C1C);
            graphics.drawString(font, letter, x + (TOGGLE_W - font.width(letter)) / 2 + 1, y + 1,
                    on ? 0x202020 : 0x909090, false);
        }

        private static int darker(int argb) {
            return 0xFF000000 | ((argb >> 1) & 0x7F7F7F);
        }

        private static int lighter(int argb) {
            int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
            return 0xFF000000 | (Math.min(255, r + 50) << 16) | (Math.min(255, g + 50) << 8) | Math.min(255, b + 50);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            WorkbenchState state = state();
            if (showsLight() && mouseX >= lightX()) {
                toggle(state.lights, lightKey());
                host.changed();
                return;
            }
            if (showsDial() && mouseX >= lightX()) {
                if (state.isFace(part, element.material()))
                    state.clearFace();
                else
                    state.assignFace(part, element.material());
                host.rebuild();
                return;
            }
            if (showsIndicator() && mouseX >= lightX()) {
                // The flag and the handle never share a part
                if (state.indicatorParts.contains(part)) {
                    state.indicatorParts.remove(part);
                } else {
                    state.indicatorParts.add(part);
                    state.moving.remove(part);
                }
                host.changed();
                return;
            }
            if (element != null) {
                long now = Util.getMillis();
                if (key().equals(lastClicked) && now - lastClickMillis <= DOUBLE_CLICK_MILLIS) {
                    lastClicked = null;
                    host.editMaterial(part, element.material());
                } else {
                    lastClicked = key();
                    lastClickMillis = now;
                }
                return;
            }
            if (showsMoving() && mouseX >= movingX() && mouseX < movingX() + TOGGLE_W) {
                toggle(state.moving, part);
                if (state.moving.contains(part))
                    state.indicatorParts.remove(part);
                host.changed();
            } else if (mouseX >= checkX() - 1 && mouseX < checkX() + 10) {
                toggle(state.hidden, part);
                host.changed();
            } else if (hasExpander() && mouseX < iconX()) {
                toggle(state.expanded, part);
                host.rebuild();
            } else if (hasExpander()) {
                // A click on the name opens and closes it too, as tree views do
                toggle(state.expanded, part);
                host.rebuild();
            }
        }

        private static <T> void toggle(java.util.Set<T> set, T value) {
            if (!set.remove(value))
                set.add(value);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }
}
