package com.nectariich.create_flippies.client;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.label.PanelLabel;
import com.nectariich.create_flippies.content.label.PanelLabelControl;
import com.nectariich.create_flippies.content.label.PanelLabelPrint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.SpannedControlType;
import com.nectariich.create_flippies.network.ConfigurePanelLabelPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Editor for one mounted panel label: the legend it prints, the two colours it
 * prints it in, and how it is set — face, weight, slant, height and which way round.
 *
 * How big the strip itself is does not appear as a control here, only as a reading.
 * The strip is the rectangle the player drew when they put the label on, and it is
 * that label's footprint: changing it would be a change of hitbox and of everything
 * that was checked against it when it went on, which is a new label rather than an
 * edit to this one. What this screen decides is only what goes on the strip.
 *
 * The turn is here rather than beside the strip for the same reason. Turning the
 * legend does not turn the strip — the rectangle stays exactly where it was drawn,
 * and what changes is which of its two sides the lettering runs along.
 *
 * Opened from {@code OpenPanelLabelPacket} rather than from the click itself, because
 * a host block only hands a right-click to its controls on the server. The label is
 * found again here by its mount, from the host block entity the client already has —
 * so the boxes start from what is really on the panel and not from what a packet
 * claimed was.
 *
 * The section sign is kept out of the box as well as out of the packet. Filtering
 * only on arrival would leave a player typing a formatting code, seeing it accepted,
 * and watching it vanish.
 */
public class PanelLabelScreen extends Screen {

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".panel_label_menu.";

    private static final int ROW_HEIGHT = 22;
    private static final int BOX_WIDTH = 120;
    private static final int SMALL_BOX = 58;
    private static final int TOGGLE_WIDTH = 28;

    /** What is left of a row once the two style toggles have had their share. */
    private static final int TURN_WIDTH = BOX_WIDTH - 2 * (TOGGLE_WIDTH + 4);

    private final BlockPos pos;
    private final ControlMount mount;
    private final String initialText;
    private final int initialColour;
    private final int initialPlateColour;
    private final int stripWidth;
    private final int stripHeight;

    private EditBox textBox;
    private EditBox colourBox;
    private EditBox plateColourBox;
    private EditBox sizeBox;
    private PanelLabelPrint print;

    public static void open(BlockPos pos, ControlMount mount) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null)
            return;
        PanelLabelControl label = PanelLabel.at(minecraft.level.getBlockEntity(pos), mount);
        if (label == null)
            return;
        minecraft.setScreen(new PanelLabelScreen(pos, mount, label));
    }

    private PanelLabelScreen(BlockPos pos, ControlMount mount, PanelLabelControl label) {
        super(Component.translatable(LANG_PREFIX + "title"));
        this.pos = pos;
        this.mount = mount;
        this.initialText = label.text();
        this.initialColour = label.colour();
        this.initialPlateColour = label.plateColour();
        this.print = label.print();
        this.stripWidth = label.width();
        this.stripHeight = label.height();
    }

    @Override
    protected void init() {
        super.init();
        int cx = width / 2;
        int top = top();

        textBox = new EditBox(font, cx - 20, top, BOX_WIDTH, 20, Component.empty());
        textBox.setMaxLength(PanelLabel.MAX_LENGTH);
        textBox.setFilter(text -> text.indexOf(ChatFormatting.PREFIX_CODE) < 0);
        textBox.setValue(initialText);
        addRenderableWidget(textBox);

        colourBox = colourBox(cx - 20, top + ROW_HEIGHT, initialColour);
        plateColourBox = colourBox(cx - 20, top + 2 * ROW_HEIGHT, initialPlateColour);

        // One button cycling the faces rather than a list: there are four of them,
        // and the preview says what each one looks like as soon as it is picked
        addRenderableWidget(Button.builder(print.font().label(), button -> {
            print = print.withFont(print.font().next());
            button.setMessage(print.font().label());
        }).bounds(cx - 20, top + 3 * ROW_HEIGHT, BOX_WIDTH, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("bold", print.bold()), button -> {
            print = print.withBold(!print.bold());
            button.setMessage(toggleLabel("bold", print.bold()));
        }).bounds(cx - 20, top + 4 * ROW_HEIGHT, TOGGLE_WIDTH, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("italic", print.italic()), button -> {
            print = print.withItalic(!print.italic());
            button.setMessage(toggleLabel("italic", print.italic()));
        }).bounds(cx - 20 + TOGGLE_WIDTH + 4, top + 4 * ROW_HEIGHT, TOGGLE_WIDTH, 20).build());

        // A button rather than four: they are a cycle, the preview behind the screen
        // shows each one the moment it is picked, and there is no order to get wrong
        addRenderableWidget(Button.builder(turnLabel(), button -> {
            print = print.withQuarters(print.quarters() + 1);
            button.setMessage(turnLabel());
        }).bounds(cx - 20 + 2 * (TOGGLE_WIDTH + 4), top + 4 * ROW_HEIGHT, TURN_WIDTH, 20).build());

        // Height in pixels rather than a set of names, and empty for the fitted
        // default: the sizes worth having depend on the strip this label was drawn
        // to, so there is no useful list to name
        sizeBox = new EditBox(font, cx - 20, top + 5 * ROW_HEIGHT, SMALL_BOX, 20, Component.empty());
        sizeBox.setMaxLength(5);
        sizeBox.setFilter(text -> text.matches("[0-9]*\\.?[0-9]*"));
        sizeBox.setValue(print.fitted() ? "" : trimZeros(PanelLabelPrint.pixels(print.size())));
        sizeBox.setResponder(text -> print = print.withSize(parseSize(text)));
        addRenderableWidget(sizeBox);

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> applyAndClose())
                .bounds(cx - 60, top + 6 * ROW_HEIGHT + 18, 120, 20).build());

        setInitialFocus(textBox);
    }

    /** One of the two colour fields, which differ in nothing but where they land. */
    private EditBox colourBox(int x, int y, int initial) {
        EditBox box = new EditBox(font, x, y, BOX_WIDTH, 20, Component.empty());
        box.setMaxLength(7);
        box.setFilter(text -> text.matches("#?[0-9a-fA-F]*"));
        box.setValue(hex(initial));
        addRenderableWidget(box);
        return box;
    }

    private void applyAndClose() {
        print = print.withSize(parseSize(sizeBox.getValue()));
        ModPackets.CHANNEL.sendToServer(new ConfigurePanelLabelPacket(pos, mount, textBox.getValue(),
                colour(colourBox.getValue(), initialColour), colour(plateColourBox.getValue(), initialPlateColour),
                print));
        onClose();
    }

    /** What a colour box says, or what the label already had if it says nothing readable. */
    private static int colour(String text, int fallback) {
        Integer parsed = parseHex(text);
        return parsed != null ? parsed : fallback;
    }

    private Component toggleLabel(String key, boolean on) {
        Component name = Component.translatable(LANG_PREFIX + key);
        return on ? name.copy().withStyle(ChatFormatting.GREEN) : name;
    }

    private Component turnLabel() {
        return Component.translatable(LANG_PREFIX + "turn", print.quarters() * 90);
    }

    /** What the box says, as a size: fitted when it is empty or unreadable. */
    private static int parseSize(String text) {
        if (text.isEmpty() || text.equals("."))
            return PanelLabelPrint.FIT;
        try {
            double pixels = Double.parseDouble(text);
            return pixels <= 0 ? PanelLabelPrint.FIT : PanelLabelPrint.sizeFromPixels(pixels);
        } catch (NumberFormatException e) {
            return PanelLabelPrint.FIT;
        }
    }

    /** Half-pixel sizes read as 1.5 and whole ones as 2, not 2.0. */
    private static String trimZeros(double pixels) {
        return pixels == Math.floor(pixels) ? String.valueOf((int) pixels) : String.valueOf(pixels);
    }

    private static Integer parseHex(String text) {
        String cleaned = text.startsWith("#") ? text.substring(1) : text;
        if (cleaned.length() != 6)
            return null;
        try {
            return Integer.parseInt(cleaned, 16) | 0xFF000000;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String hex(int argb) {
        return String.format("%06X", argb & 0xFFFFFF);
    }

    /** Where the first row sits, with the reading and the button below the rows. */
    private int top() {
        return height / 2 - 80;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        int cx = width / 2;
        int top = top();
        graphics.drawCenteredString(font, title, cx, top - 20, 0xFFFFFF);
        label(graphics, "text", cx - 100, top);
        label(graphics, "color", cx - 100, top + ROW_HEIGHT);
        label(graphics, "plate_color", cx - 100, top + 2 * ROW_HEIGHT);
        label(graphics, "font", cx - 100, top + 3 * ROW_HEIGHT);
        label(graphics, "style", cx - 100, top + 4 * ROW_HEIGHT);
        label(graphics, "size", cx - 100, top + 5 * ROW_HEIGHT);
        graphics.drawString(font, Component.translatable(LANG_PREFIX + (print.fitted() ? "size_fit" : "size_pixels")),
                cx - 20 + SMALL_BOX + 6, top + 5 * ROW_HEIGHT + 6, 0xA0A0A0);
        // The strip is what the label was drawn to and cannot be edited here, so it
        // is stated rather than offered. In pixels, because that is the unit the
        // height above is typed in — the half a player can now draw shows as a half
        graphics.drawCenteredString(font, Component.translatable(LANG_PREFIX + "strip",
                trimZeros(SpannedControlType.pixels(stripWidth)), trimZeros(SpannedControlType.pixels(stripHeight))),
                cx, top + 6 * ROW_HEIGHT, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    private void label(GuiGraphics graphics, String key, int x, int y) {
        graphics.drawString(font, Component.translatable(LANG_PREFIX + key), x, y + 6, 0xA0A0A0);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter / numpad enter
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
