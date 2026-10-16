package com.nectariich.create_flippies.client;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.display.AlphanumDisplay;
import com.nectariich.create_flippies.content.display.AlphanumDisplayBlockEntity;
import com.nectariich.create_flippies.content.display.AlphanumDisplayControl;
import com.nectariich.create_flippies.content.display.DisplayForm;
import com.nectariich.create_flippies.content.display.DisplayPrint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.network.ConfigureAlphanumDisplayPacket;
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
 * Configuration menu of the alphanumeric displays: how the line they are sent is
 * set — face, weight, slant, character height, which way round it reads and what
 * colour it glows — and, for a display that is a block of its own, a quarter turn in
 * its panel.
 *
 * What is <em>on</em> the display is not here and is not editable. A display shows
 * what a link sends it; a box that let a player type a line would be a second source
 * of truth that the next link refresh silently overwrote, and the goggles already
 * show the whole of what was sent. That is the one thing this menu does not share
 * with the panel label's, which it otherwise follows closely.
 *
 * The character height is a number in pixels rather than a set of names, for the
 * reason {@link com.nectariich.create_flippies.content.label.PanelLabelPrint} gives
 * about labels: the useful heights depend on the glass, and the same three names
 * would mean something different on every form. Leaving the box empty asks for
 * fitting, which shrinks the line until all of it is on the glass — useful for a
 * caption, and deliberately not the default, because a readout whose characters
 * resized as its value did would be unreadable. See {@link DisplayPrint}.
 *
 * Opened by a right click with a screwdriver, which is how every control in the mod
 * is configured; see
 * {@link com.nectariich.create_flippies.foundation.control.ControlConfig}. Every
 * change is sent as it is made rather than batched behind Done, because the display
 * is right there behind a menu this small and the only honest way to choose a
 * character height on a screen a pixel and a half across is by looking at it.
 */
public class AlphanumDisplayScreen extends Screen {

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".alphanum_display_menu.";

    private static final int ROW_HEIGHT = 22;
    private static final int BOX_WIDTH = 120;
    private static final int SMALL_BOX = 58;
    private static final int TOGGLE_WIDTH = 28;
    private static final int LABEL_OFFSET = -110;

    /** What is left of a row once the two style toggles have had their share. */
    private static final int TURN_WIDTH = BOX_WIDTH - 2 * (TOGGLE_WIDTH + 4);

    private final BlockPos pos;
    /** Null when {@link #pos} is the display itself rather than a host holding one. */
    private final ControlMount mount;
    private final DisplayForm form;

    private DisplayPrint print;
    private EditBox colourBox;
    private EditBox sizeBox;

    public static void open(AlphanumDisplayBlockEntity display) {
        Minecraft.getInstance()
                .setScreen(new AlphanumDisplayScreen(display.getBlockPos(), null, display.getForm(),
                        display.print()));
    }

    /**
     * Opens the same menu for the display mounted at {@code mount} in the host at
     * {@code pos}. The starting values come from the control the client already has,
     * not from the packet that asked for the screen, so the boxes show what is really
     * on the panel.
     */
    public static void open(BlockPos pos, ControlMount mount) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null)
            return;
        AlphanumDisplayControl display = AlphanumDisplay.at(minecraft.level.getBlockEntity(pos), mount);
        if (display == null)
            return;
        minecraft.setScreen(new AlphanumDisplayScreen(pos, mount, display.form(), display.print()));
    }

    private AlphanumDisplayScreen(BlockPos pos, ControlMount mount, DisplayForm form, DisplayPrint print) {
        super(Component.translatable(LANG_PREFIX + "title"));
        this.pos = pos;
        this.mount = mount;
        this.form = form;
        this.print = print;
    }

    @Override
    protected void init() {
        super.init();
        int x = width / 2 - 10;
        int top = top();

        // Height in pixels rather than a set of names, and empty for fitting; see the
        // class comment for why fitting is offered but is not where a display starts
        sizeBox = new EditBox(font, x, top, SMALL_BOX, 20, Component.empty());
        sizeBox.setMaxLength(5);
        sizeBox.setFilter(text -> text.matches("[0-9]*\\.?[0-9]*"));
        sizeBox.setValue(print.fitted() ? "" : trimZeros(DisplayPrint.pixels(print.size())));
        sizeBox.setResponder(text -> {
            print = print.withSize(parseSize(text));
            send(false);
        });
        addRenderableWidget(sizeBox);

        colourBox = new EditBox(font, x, top + ROW_HEIGHT, BOX_WIDTH, 20, Component.empty());
        colourBox.setMaxLength(7);
        colourBox.setFilter(text -> text.matches("#?[0-9a-fA-F]*"));
        colourBox.setValue(hex(print.colour()));
        colourBox.setResponder(text -> {
            Integer parsed = parseHex(text);
            if (parsed == null)
                return;
            print = print.withColour(parsed);
            send(false);
        });
        addRenderableWidget(colourBox);

        // One button cycling the faces rather than a list: there are two of them, and
        // the display behind the screen says what each looks like as soon as it is picked
        addRenderableWidget(Button.builder(print.font().label(), button -> {
            print = print.withFont(print.font().next());
            button.setMessage(print.font().label());
            send(false);
        }).bounds(x, top + 2 * ROW_HEIGHT, BOX_WIDTH, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("bold", print.bold()), button -> {
            print = print.withBold(!print.bold());
            button.setMessage(toggleLabel("bold", print.bold()));
            send(false);
        }).bounds(x, top + 3 * ROW_HEIGHT, TOGGLE_WIDTH, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("italic", print.italic()), button -> {
            print = print.withItalic(!print.italic());
            button.setMessage(toggleLabel("italic", print.italic()));
            send(false);
        }).bounds(x + TOGGLE_WIDTH + 4, top + 3 * ROW_HEIGHT, TOGGLE_WIDTH, 20).build());

        addRenderableWidget(Button.builder(turnLabel(), button -> {
            print = print.withQuarters(print.quarters() + 1);
            button.setMessage(turnLabel());
            send(false);
        }).bounds(x + 2 * (TOGGLE_WIDTH + 4), top + 3 * ROW_HEIGHT, TURN_WIDTH, 20).build());

        // A mounted display has no turn to offer: a control's facing and quarter turn
        // are its mount, fixed when the screwdriver installed it. Turning the lettering
        // is the button above, and is the one that matters for reading a screen sideways
        if (mount == null)
            addRenderableWidget(Button.builder(Component.translatable(LANG_PREFIX + "rotate"),
                    button -> send(true))
                    .bounds(x, top + 4 * ROW_HEIGHT, BOX_WIDTH, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 60, top + 5 * ROW_HEIGHT + 14, 120, 20).build());

        setInitialFocus(sizeBox);
    }

    private void send(boolean turn) {
        ModPackets.CHANNEL.sendToServer(new ConfigureAlphanumDisplayPacket(pos, print, turn, mount));
    }

    private Component toggleLabel(String key, boolean on) {
        Component name = Component.translatable(LANG_PREFIX + key);
        return on ? name.copy().withStyle(ChatFormatting.GREEN) : name;
    }

    private Component turnLabel() {
        return Component.translatable(LANG_PREFIX + "turn", print.quarters() * 90);
    }

    /** What the box says, as a height: fitted when it is empty or unreadable. */
    private static int parseSize(String text) {
        if (text.isEmpty() || text.equals("."))
            return DisplayPrint.FIT;
        try {
            double pixels = Double.parseDouble(text);
            return pixels <= 0 ? DisplayPrint.FIT : DisplayPrint.sizeFromPixels(pixels);
        } catch (NumberFormatException e) {
            return DisplayPrint.FIT;
        }
    }

    /** Tenth-pixel heights read as 0.3 and whole ones as 2, not 2.0. */
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

    private int top() {
        return height / 2 - 70;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        int cx = width / 2;
        int top = top();
        graphics.drawCenteredString(font, title, cx, top - 20, 0xFFFFFF);
        label(graphics, "size", cx + LABEL_OFFSET, top);
        label(graphics, "color", cx + LABEL_OFFSET, top + ROW_HEIGHT);
        label(graphics, "font", cx + LABEL_OFFSET, top + 2 * ROW_HEIGHT);
        label(graphics, "style", cx + LABEL_OFFSET, top + 3 * ROW_HEIGHT);
        graphics.drawString(font, Component.translatable(LANG_PREFIX + (print.fitted() ? "size_fit" : "size_pixels")),
                cx - 10 + SMALL_BOX + 6, top + 6, 0xA0A0A0);
        // The glass is the model's and cannot be edited here, so it is stated rather
        // than offered — along with how many characters it will hold at this height,
        // which is the number that actually decides whether a line survives
        graphics.drawCenteredString(font, Component.translatable(LANG_PREFIX + "glass",
                trimZeros(form.width()), print.columns(form)), cx, top + 5 * ROW_HEIGHT - 4, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    private void label(GuiGraphics graphics, String key, int x, int y) {
        graphics.drawString(font, Component.translatable(LANG_PREFIX + key), x, y + 6, 0xA0A0A0);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter / numpad enter
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
