package com.nectariich.create_flippies.client.schematic_arrow;

import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.decal.ClientDecals;
import com.nectariich.create_flippies.client.design.DesignImporter;
import com.nectariich.create_flippies.content.decal.DecalSelection;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowHead;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowSettings;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowStyle;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;
import com.nectariich.create_flippies.network.ConfigureArrowPacket;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.SelectDecalPacket;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The schematic arrow menu, opened from the screwdriver menu: what a click does, how
 * points snap, and how the next arrow looks; and the SVG a stamped arrow is raised
 * from.
 *
 * A screen of its own rather than more rows of the screwdriver menu, because an
 * arrow has a dozen settings and every other mode has two or three. Built like that
 * menu: vanilla cycling buttons, one packet when it closes. The colour is a palette
 * button and a hex box beside it, the way the gauge and label menus take a colour:
 * a palette pick fills the box, and anything typed there that reads as a colour is
 * the arrow's colour, shown in the swatch with the sides as they will be drawn. The
 * swatch opens a colour picker; the button beside it puts the menu aside to take the
 * colour of an arrow already on a panel (see {@link ArrowColourPick}); and the row
 * under them is the player's own saved colours (see {@link ArrowPalette}). The SVG is the same one
 * the paint mode uses, imported the same way and kept in the same place on the tool,
 * so a drawing can be painted flat and raised as an arrow without importing it twice.
 */
public class ArrowScreen extends Screen {

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".screwdriver.arrow.";
    private static final String PAINT_PREFIX = "gui." + CreateFlippies.MOD_ID + ".screwdriver.";

    private static final int ROW_HEIGHT = 22;
    private static final int COLUMN_WIDTH = 150;
    private static final int GAP = 8;
    private static final int SWATCH = 20;
    private static final int PALETTE_SWATCH = 18;
    private static final int PALETTE_STEP = 20;
    private static final int VALID_TEXT = 0xE0E0E0;
    private static final int INVALID_TEXT = 0xFF5555;

    /** The colours an arrow comes in, the first being an imported drawing's own. */
    private static final int[] COLOURS = { ArrowStyle.OWN_COLOURS, 0xFFF0F0F0, 0xFF1A1A1A, 0xFF8A8A8A, 0xFFD03A3A,
            0xFFE8862A, 0xFFF2D13A, 0xFF3FA34D, 0xFF3366CC, 0xFF8A4FBF };

    private static final String[] COLOUR_NAMES = { "own", "white", "black", "grey", "red", "orange", "yellow",
            "green", "blue", "purple" };

    private final InteractionHand hand;
    private ArrowSettings settings;
    private final int decalTint;
    private boolean transparent;
    private final boolean initialTransparent;
    private String artName;
    private boolean importing;
    private Component importStatus;

    private Button actionButton;
    private Button snapButton;
    private Button widthButton;
    private Button heightButton;
    private Button startButton;
    private Button endButton;
    private Button joinButton;
    private Button headLengthButton;
    private Button headWidthButton;
    private Button headStrokeButton;
    private Button colourButton;
    private EditBox hexBox;
    private Button pickButton;
    private Button saveColourButton;
    private ArrowColourPicker picker;
    /** Set while the picker fills the hex box, so the box does not hand the colour back to it. */
    private boolean picking;
    private Button sidesButton;
    private Button importButton;
    private Button backgroundButton;

    public static void open(InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null)
            return;
        minecraft.setScreen(new ArrowScreen(hand, minecraft.player.getItemInHand(hand)));
    }

    private ArrowScreen(InteractionHand hand, ItemStack stack) {
        super(Component.translatable(LANG_PREFIX + "title"));
        this.hand = hand;
        this.settings = ArrowSettings.read(stack);
        DecalSelection decal = DecalSelection.read(stack);
        this.decalTint = decal.tint();
        this.transparent = decal.transparent();
        this.initialTransparent = decal.transparent();
        this.artName = decal.hasArt() ? decal.name() : null;
    }

    @Override
    protected void init() {
        super.init();
        int left = width / 2 - COLUMN_WIDTH - GAP / 2;
        int right = width / 2 + GAP / 2;
        int top = top();
        int third = (2 * COLUMN_WIDTH + GAP - 2 * GAP / 2) / 3;

        actionButton = button(left, top, () -> settings = settings.withAction(settings.action().next()));
        snapButton = button(right, top, () -> settings = settings.withSnap(settings.snap().next()));
        widthButton = button(left, top + ROW_HEIGHT, () -> settings = settings.withStyle(
                settings.style().withWidth(ArrowStyle.cycle(ArrowStyle.WIDTHS, settings.style().width()))));
        heightButton = button(right, top + ROW_HEIGHT, () -> settings = settings.withStyle(
                settings.style().withHeight(ArrowStyle.cycle(ArrowStyle.HEIGHTS, settings.style().height()))));
        startButton = button(left, top + 2 * ROW_HEIGHT, () -> settings = settings.withStyle(
                settings.style().withHeads(settings.style().start().next(), settings.style().end())));
        endButton = button(right, top + 2 * ROW_HEIGHT, () -> settings = settings.withStyle(
                settings.style().withHeads(settings.style().start(), settings.style().end().next())));
        headLengthButton = button(left, top + 3 * ROW_HEIGHT, third, () -> settings = settings.withStyle(
                settings.style().withHeadLength(ArrowStyle.cycle(ArrowStyle.HEAD_LENGTHS,
                        settings.style().headLength()))));
        headLengthButton.setTooltip(Tooltip.create(Component.translatable(LANG_PREFIX + "head_length.tooltip")));
        headWidthButton = button(left + third + GAP / 2, top + 3 * ROW_HEIGHT, third,
                () -> settings = settings.withStyle(settings.style().withHeadWidth(
                        ArrowStyle.cycle(ArrowStyle.HEAD_WIDTHS, settings.style().headWidth()))));
        headWidthButton.setTooltip(Tooltip.create(Component.translatable(LANG_PREFIX + "head_width.tooltip")));
        headStrokeButton = button(left + 2 * (third + GAP / 2), top + 3 * ROW_HEIGHT, third,
                () -> settings = settings.withStyle(settings.style().withHeadStroke(
                        ArrowStyle.cycle(ArrowStyle.HEAD_STROKES, settings.style().headStroke()))));
        headStrokeButton.setTooltip(Tooltip.create(Component.translatable(LANG_PREFIX + "head_stroke.tooltip")));
        joinButton = button(left, top + 4 * ROW_HEIGHT, () -> settings = settings.withStyle(
                settings.style().withJoin(settings.style().join().next())));
        sidesButton = button(right, top + 4 * ROW_HEIGHT, () -> settings = settings.withStyle(
                settings.style().withShadedSides(!settings.style().shadedSides())));
        colourButton = button(left, top + 5 * ROW_HEIGHT, () -> {
            settings = settings.withStyle(
                    settings.style().withColour(COLOURS[(colourIndex() + 1) % COLOURS.length]));
            hexBox.setValue(hexText());
        });

        picker = null;
        hexBox = new EditBox(font, right, top + 5 * ROW_HEIGHT, COLUMN_WIDTH - SWATCH - 4 - 20 - 4, 20,
                Component.translatable(LANG_PREFIX + "hex"));
        hexBox.setMaxLength(7);
        hexBox.setFilter(text -> text.matches("#?[0-9a-fA-F]*"));
        hexBox.setValue(hexText());
        hexBox.setResponder(text -> {
            Integer parsed = ArrowStyle.parseHex(text);
            hexBox.setTextColor(parsed != null || text.isEmpty() ? VALID_TEXT : INVALID_TEXT);
            if (parsed != null) {
                settings = settings.withStyle(settings.style().withColour(parsed));
                if (picker != null && !picking)
                    picker.sync(parsed);
                refresh();
            }
        });
        hexBox.setTooltip(Tooltip.create(Component.translatable(LANG_PREFIX + "hex.tooltip")));
        addRenderableWidget(hexBox);
        pickButton = addRenderableWidget(Button.builder(Component.literal("⌖"), button -> ArrowColourPick.start(this))
                .bounds(swatchX() - 4 - 20, top + 5 * ROW_HEIGHT, 20, 20)
                .tooltip(Tooltip.create(Component.translatable(LANG_PREFIX + "pick.tooltip"))).build());

        saveColourButton = addRenderableWidget(Button.builder(Component.literal("+"), button -> {
            ArrowPalette.add(settings.style().colour());
            refresh();
        }).bounds(left, top + 6 * ROW_HEIGHT, 20, 20)
                .tooltip(Tooltip.create(Component.translatable(LANG_PREFIX + "palette.save.tooltip"))).build());

        importButton = button(left, top + 7 * ROW_HEIGHT, this::importPicture);
        backgroundButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            transparent = !transparent;
            refresh();
        }).bounds(right, top + 7 * ROW_HEIGHT, COLUMN_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable(PAINT_PREFIX + "paint_background.tooltip"))).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> applyAndClose())
                .bounds(width / 2 - 60, top + 9 * ROW_HEIGHT + 4, 120, 20).build());
        refresh();
    }

    /** Where the first row sits, low enough for the title and high enough for the button below the rows. */
    private int top() {
        return height / 2 - 110;
    }

    private int swatchX() {
        return width / 2 + GAP / 2 + COLUMN_WIDTH - SWATCH;
    }

    private int swatchY() {
        return top() + 5 * ROW_HEIGHT;
    }

    private int paletteX(int index) {
        return width / 2 - COLUMN_WIDTH - GAP / 2 + 24 + index * PALETTE_STEP;
    }

    private int paletteY() {
        return top() + 6 * ROW_HEIGHT + 1;
    }

    /** The saved colour under the mouse, or minus one. */
    private int paletteAt(double mouseX, double mouseY) {
        int count = ArrowPalette.colours().size();
        for (int i = 0; i < count; i++)
            if (inside(mouseX, mouseY, paletteX(i), paletteY(), PALETTE_SWATCH, PALETTE_SWATCH))
                return i;
        return -1;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    InteractionHand hand() {
        return hand;
    }

    /** A colour from the picker, the palette or another arrow: into the settings and the hex box. */
    void takeColour(int colour) {
        settings = settings.withStyle(settings.style().withColour(colour));
        if (hexBox != null) {
            picking = true;
            hexBox.setValue(hexText());
            picking = false;
            refresh();
        }
    }

    private void togglePicker() {
        if (picker != null) {
            picker = null;
            return;
        }
        int x = Math.max(0, swatchX() + SWATCH - ArrowColourPicker.WIDTH);
        int y = swatchY() + SWATCH + 2;
        if (y + ArrowColourPicker.HEIGHT > height)
            y = swatchY() - 2 - ArrowColourPicker.HEIGHT;
        int colour = settings.style().colour();
        picker = new ArrowColourPicker(x, y, colour == ArrowStyle.OWN_COLOURS ? 0xFFFFFFFF : colour,
                this::takeColour);
    }

    private Button button(int x, int y, Runnable press) {
        return button(x, y, COLUMN_WIDTH, press);
    }

    private Button button(int x, int y, int buttonWidth, Runnable press) {
        return addRenderableWidget(Button.builder(Component.empty(), button -> {
            press.run();
            refresh();
        }).bounds(x, y, buttonWidth, 20).build());
    }

    /** The colour as the hex box shows it: empty for an imported drawing's own colours. */
    private String hexText() {
        int colour = settings.style().colour();
        return colour == ArrowStyle.OWN_COLOURS ? "" : ArrowStyle.hex(colour);
    }

    private void refresh() {
        ArrowStyle style = settings.style();
        actionButton.setMessage(choice("action", settings.action().key()));
        snapButton.setMessage(choice("snap", settings.snap().key()));
        widthButton.setMessage(Component.translatable(LANG_PREFIX + "width", pixels(style.width())));
        heightButton.setMessage(Component.translatable(LANG_PREFIX + "height", pixels(style.height())));
        startButton.setMessage(Component.translatable(LANG_PREFIX + "start",
                Component.translatable(LANG_PREFIX + "head." + style.start().key())));
        endButton.setMessage(Component.translatable(LANG_PREFIX + "end",
                Component.translatable(LANG_PREFIX + "head." + style.end().key())));
        boolean headed = style.start() != ArrowHead.NONE || style.end() != ArrowHead.NONE;
        headLengthButton.active = headed;
        headLengthButton.setMessage(Component.translatable(LANG_PREFIX + "head_length", headSize(style.headLength())));
        headWidthButton.active = headed;
        headWidthButton.setMessage(Component.translatable(LANG_PREFIX + "head_width", headSize(style.headWidth())));
        headStrokeButton.active = style.start() == ArrowHead.CHEVRON || style.end() == ArrowHead.CHEVRON;
        headStrokeButton.setMessage(Component.translatable(LANG_PREFIX + "head_stroke", headSize(style.headStroke())));
        joinButton.setMessage(choice("join", style.join().key()));
        colourButton.setMessage(colourLabel());
        saveColourButton.active = style.colour() != ArrowStyle.OWN_COLOURS && !ArrowPalette.contains(style.colour());
        sidesButton.setMessage(Component.translatable(LANG_PREFIX + (style.shadedSides() ? "sides.shaded"
                : "sides.same")));
        importButton.active = !importing;
        importButton.setMessage(importing ? Component.translatable(PAINT_PREFIX + "paint_importing")
                : artName == null ? Component.translatable(PAINT_PREFIX + "paint_import")
                        : Component.translatable(PAINT_PREFIX + "paint_replace", artName));
        backgroundButton.setMessage(Component.translatable(PAINT_PREFIX + (transparent
                ? "paint_background.transparent" : "paint_background.kept")));
    }

    private Component choice(String setting, String value) {
        return Component.translatable(LANG_PREFIX + setting,
                Component.translatable(LANG_PREFIX + setting + "." + value));
    }

    /** A head measurement as its button shows it: Auto, or pixels. */
    private static Component headSize(int units) {
        return units == ArrowStyle.AUTO ? Component.translatable(LANG_PREFIX + "head_size.auto")
                : Component.translatable(LANG_PREFIX + "head_size.pixels", pixels(units));
    }

    private Component colourLabel() {
        int index = colourIndex();
        if (index < 0)
            return Component.translatable(LANG_PREFIX + "colour", Component.literal("■ ")
                    .withStyle(style -> style.withColor(settings.style().colour() & 0xFFFFFF))
                    .append(Component.translatable(LANG_PREFIX + "colour.custom")));
        Component name = Component.translatable(PAINT_PREFIX + "paint_colour." + COLOUR_NAMES[index]);
        if (COLOURS[index] == ArrowStyle.OWN_COLOURS)
            return Component.translatable(LANG_PREFIX + "colour", name);
        return Component.translatable(LANG_PREFIX + "colour", Component.literal("■ ")
                .withStyle(style -> style.withColor(COLOURS[index] & 0xFFFFFF)).append(name));
    }

    /** Where the colour is in the palette, or minus one for one typed as hex; the next pick after that is the first. */
    private int colourIndex() {
        for (int i = 0; i < COLOURS.length; i++)
            if (COLOURS[i] == settings.style().colour())
                return i;
        return -1;
    }

    /** A length in offset units as pixels, with no more decimals than it needs. */
    static String pixels(double units) {
        String text = String.format(Locale.ROOT, "%.2f", units / (double) PanelPlane.UNITS_PER_PIXEL);
        text = text.replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** Imports an SVG onto the tool, the way the paint mode does; it lands even if the menu is closed first. */
    private void importPicture() {
        if (importing)
            return;
        DesignImporter.browseSvg(paths -> {
            if (paths.isEmpty())
                return;
            importing = true;
            importStatus = Component.translatable(PAINT_PREFIX + "paint_importing");
            refreshIfOpen();
            ClientDecals.importFile(paths.get(0), hand, decalTint, transparent, imported -> {
                importing = false;
                artName = imported.name();
                Component status = Component.translatable(PAINT_PREFIX + "paint_imported", imported.triangles());
                if (!imported.warnings().isEmpty())
                    status = status.copy().append(": ").append(String.join("; ", imported.warnings()));
                importStatus = status;
                refreshIfOpen();
            }, error -> {
                importing = false;
                importStatus = Component.translatable(PAINT_PREFIX + "paint_failed", error)
                        .withStyle(ChatFormatting.RED);
                refreshIfOpen();
            });
        });
    }

    private void refreshIfOpen() {
        if (Minecraft.getInstance().screen == this)
            refresh();
    }

    private void applyAndClose() {
        ModPackets.CHANNEL.sendToServer(new ConfigureArrowPacket(hand, settings));
        if (transparent != initialTransparent)
            ModPackets.CHANNEL.sendToServer(new SelectDecalPacket(hand, "", decalTint, transparent));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        int cx = width / 2;
        int top = top();
        graphics.drawCenteredString(font, title, cx, top - 16, 0xFFFFFF);
        // Nothing under the picker is hovered, so no tooltip shows through it
        boolean overPicker = picker != null && picker.contains(mouseX, mouseY);
        int hoverX = overPicker ? -1 : mouseX, hoverY = overPicker ? -1 : mouseY;
        super.render(graphics, hoverX, hoverY, partialTicks);
        renderSwatch(graphics, swatchX(), swatchY(), inside(hoverX, hoverY, swatchX(), swatchY(), SWATCH, SWATCH));
        renderPalette(graphics, hoverX, hoverY);
        graphics.drawCenteredString(font,
                Component.translatable(LANG_PREFIX + "hint." + settings.action().key()), cx,
                top + 8 * ROW_HEIGHT + 2, 0xA0A0A0);
        if (importStatus != null)
            graphics.drawCenteredString(font, importStatus, cx, top + 8 * ROW_HEIGHT + 13, 0xA0A0A0);
        if (picker != null)
            picker.render(graphics);
    }

    /**
     * The saved colours, the current one framed in white, and the slots still free as
     * dim frames, so the row reads as a palette before anything is in it.
     */
    private void renderPalette(GuiGraphics graphics, int mouseX, int mouseY) {
        java.util.List<Integer> colours = ArrowPalette.colours();
        int y = paletteY();
        int hovered = paletteAt(mouseX, mouseY);
        for (int i = 0; i < ArrowPalette.MAX; i++) {
            int x = paletteX(i);
            if (i >= colours.size()) {
                graphics.renderOutline(x, y, PALETTE_SWATCH, PALETTE_SWATCH, 0xFF404448);
                continue;
            }
            int colour = colours.get(i);
            boolean current = colour == settings.style().colour();
            graphics.fill(x, y, x + PALETTE_SWATCH, y + PALETTE_SWATCH,
                    current || i == hovered ? 0xFFFFFFFF : 0xFFA0A0A0);
            graphics.fill(x + 1, y + 1, x + PALETTE_SWATCH - 1, y + PALETTE_SWATCH - 1, 0xFF000000);
            graphics.fill(x + 2, y + 2, x + PALETTE_SWATCH - 2, y + PALETTE_SWATCH - 2, colour);
        }
        if (hovered >= 0)
            setTooltipForNextRenderPass(Component.translatable(LANG_PREFIX + "palette.swatch",
                    ArrowStyle.hex(colours.get(hovered))));
        else if (inside(mouseX, mouseY, swatchX(), swatchY(), SWATCH, SWATCH))
            setTooltipForNextRenderPass(Component.translatable(LANG_PREFIX + "swatch.tooltip"));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (picker != null) {
            if (picker.mouseClicked(mouseX, mouseY, button))
                return true;
            // A click off the picker closes it; on the swatch that is all it does
            picker = null;
            if (inside(mouseX, mouseY, swatchX(), swatchY(), SWATCH, SWATCH))
                return true;
        } else if (button == 0 && inside(mouseX, mouseY, swatchX(), swatchY(), SWATCH, SWATCH)) {
            togglePicker();
            return true;
        }
        int saved = paletteAt(mouseX, mouseY);
        if (saved >= 0) {
            if (button == 0)
                takeColour(ArrowPalette.colours().get(saved));
            else if (button == 1) {
                ArrowPalette.remove(saved);
                refresh();
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (picker != null && picker.mouseDragged(mouseX, mouseY))
            return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (picker != null && picker.mouseReleased())
            return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * The arrow's colour as it will be drawn: the top, with a band along the bottom in
     * the sides' colour. An imported drawing's own colours have no one colour to show,
     * so the swatch is left empty for them. Framed in white while hovered or while
     * the picker it opens is up, being a button.
     */
    private void renderSwatch(GuiGraphics graphics, int x, int y, boolean hovered) {
        graphics.fill(x, y, x + SWATCH, y + SWATCH, hovered || picker != null ? 0xFFFFFFFF : 0xFFA0A0A0);
        graphics.fill(x + 1, y + 1, x + SWATCH - 1, y + SWATCH - 1, 0xFF000000);
        ArrowStyle style = settings.style();
        if (style.colour() == ArrowStyle.OWN_COLOURS)
            return;
        int split = y + SWATCH - 6;
        graphics.fill(x + 2, y + 2, x + SWATCH - 2, split, style.colour());
        graphics.fill(x + 2, split, x + SWATCH - 2, y + SWATCH - 2, style.sideColour(style.colour()));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && picker != null) { // Esc closes the picker before the menu
            picker = null;
            return true;
        }
        if (keyCode == 257 || keyCode == 335) { // Enter / numpad enter, from the hex box too
            applyAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
