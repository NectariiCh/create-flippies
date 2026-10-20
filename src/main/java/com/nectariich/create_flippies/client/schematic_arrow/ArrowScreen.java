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
 * the arrow's colour, shown in the swatch with the sides as they will be drawn. The SVG is the same one
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

        hexBox = new EditBox(font, right, top + 5 * ROW_HEIGHT, COLUMN_WIDTH - SWATCH - 4, 20,
                Component.translatable(LANG_PREFIX + "hex"));
        hexBox.setMaxLength(7);
        hexBox.setFilter(text -> text.matches("#?[0-9a-fA-F]*"));
        hexBox.setValue(hexText());
        hexBox.setResponder(text -> {
            Integer parsed = ArrowStyle.parseHex(text);
            hexBox.setTextColor(parsed != null || text.isEmpty() ? VALID_TEXT : INVALID_TEXT);
            if (parsed != null) {
                settings = settings.withStyle(settings.style().withColour(parsed));
                refresh();
            }
        });
        hexBox.setTooltip(Tooltip.create(Component.translatable(LANG_PREFIX + "hex.tooltip")));
        addRenderableWidget(hexBox);

        importButton = button(left, top + 6 * ROW_HEIGHT, this::importPicture);
        backgroundButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            transparent = !transparent;
            refresh();
        }).bounds(right, top + 6 * ROW_HEIGHT, COLUMN_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable(PAINT_PREFIX + "paint_background.tooltip"))).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> applyAndClose())
                .bounds(width / 2 - 60, top + 8 * ROW_HEIGHT + 4, 120, 20).build());
        refresh();
    }

    /** Where the first row sits, low enough for the title and high enough for the button below the rows. */
    private int top() {
        return height / 2 - 100;
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
    static String pixels(int units) {
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
        super.render(graphics, mouseX, mouseY, partialTicks);
        renderSwatch(graphics, hexBox.getX() + hexBox.getWidth() + 4, hexBox.getY());
        graphics.drawCenteredString(font,
                Component.translatable(LANG_PREFIX + "hint." + settings.action().key()), cx,
                top + 7 * ROW_HEIGHT + 2, 0xA0A0A0);
        if (importStatus != null)
            graphics.drawCenteredString(font, importStatus, cx, top + 7 * ROW_HEIGHT + 13, 0xA0A0A0);
    }

    /**
     * The arrow's colour as it will be drawn: the top, with a band along the bottom in
     * the sides' colour. An imported drawing's own colours have no one colour to show,
     * so the swatch is left empty for them.
     */
    private void renderSwatch(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + SWATCH, y + SWATCH, 0xFFA0A0A0);
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
