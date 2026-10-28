package com.nectariich.create_flippies.client;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.FlippiesScreen;
import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorBlockEntity;
import com.nectariich.create_flippies.content.indicator.IndicatorColor;
import com.nectariich.create_flippies.content.indicator.IndicatorControl;
import com.nectariich.create_flippies.content.indicator.IndicatorKind;
import com.nectariich.create_flippies.content.indicator.IndicatorLegend;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.network.ConfigureIndicatorPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Configuration menu of the indicators: which cap is fitted, what pressing it does,
 * what is written on it, and — for a unit that is a block of its own — a quarter turn
 * in its panel.
 *
 * Opened by a sneaking right click with a screwdriver, which is how every control in
 * the mod is configured; see
 * {@link com.nectariich.create_flippies.foundation.control.ControlConfig}. It used to
 * be the wrench on the block form and a plain right click on the mounted one, and
 * both had to go: the plain click is now the press, and the wrench is Create's.
 *
 * Every change is sent as it is made rather than batched behind Done, because the
 * unit is right there behind a menu this small and the only honest way to choose a
 * cap is by looking at it. What is sent is the whole configuration each time, so a
 * legend typed and then left while a colour is picked is committed with the colour
 * rather than lost.
 *
 * The colour button skips what the action forbids. A black or grey cap belongs to a
 * button and not to a lamp, so the cycle simply does not offer them until the unit is
 * one — and taking a unit back to a plain lamp puts a forbidden cap back on the
 * default lens here as well as on the server, so the button shows what the unit will
 * really be wearing.
 *
 * The quarter turn is worth having even though the cap is round: it is the contacts
 * that turn with it, which decides how the wiring behind the panel runs, and the
 * legend turns with them. The mounted form has no turn to offer — a control's facing
 * and quarter turn are fixed when the screwdriver installs it — so its legend has a
 * turn of its own, which is the one that matters for reading a cap from the side.
 */
public class IndicatorScreen extends FlippiesScreen {

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".indicator_menu.";

    private static final int ROW_HEIGHT = 22;
    private static final int BUTTON_WIDTH = 120;
    private static final int LABEL_OFFSET = -110;

    private final BlockPos pos;
    /** Null when {@link #pos} is the unit itself rather than a host holding one. */
    private final ControlMount mount;
    private final IndicatorKind kind;

    private IndicatorColor color;
    private IndicatorAction action;
    private IndicatorLegend legend;

    private EditBox textBox;
    private EditBox textColourBox;

    public static void open(IndicatorBlockEntity unit) {
        Minecraft.getInstance().setScreen(new IndicatorScreen(unit.getBlockPos(), null, unit.kind(), unit.color(),
                unit.action(), unit.legend()));
    }

    /**
     * Opens the same menu for the unit mounted at {@code mount} in the host at
     * {@code pos}. The starting values come from the control the client already has,
     * not from the packet that asked for the screen, so the boxes show what is really
     * on the panel.
     */
    public static void open(BlockPos pos, ControlMount mount) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null)
            return;
        if (!(minecraft.level.getBlockEntity(pos) instanceof ControlRackHolder host))
            return;
        HostedControl control = host.installedAt(mount);
        if (control instanceof IndicatorControl unit)
            minecraft.setScreen(new IndicatorScreen(pos, mount, unit.kind(), unit.color(), unit.action(),
                    unit.legend()));
    }

    private IndicatorScreen(BlockPos pos, ControlMount mount, IndicatorKind kind, IndicatorColor color,
            IndicatorAction action, IndicatorLegend legend) {
        super(Component.translatable(LANG_PREFIX + "title"));
        this.pos = pos;
        this.mount = mount;
        this.kind = kind;
        this.color = color;
        this.action = action;
        this.legend = legend;
    }

    @Override
    protected void init() {
        super.init();
        int x = width / 2 - 10;

        addRenderableWidget(Button.builder(color.label(), button -> {
            color = color.next(action);
            button.setMessage(color.label());
            send(false);
        }).bounds(x, rowY(0), BUTTON_WIDTH, 20).build());

        addRenderableWidget(Button.builder(action.label(), button -> {
            action = action.next();
            // A cap the new action forbids comes off here as well as on the server, so
            // the colour button above is never showing a cap the unit is not wearing
            color = IndicatorColor.sanitise(color, action);
            button.setMessage(action.label());
            send(false);
        }).bounds(x, rowY(1), BUTTON_WIDTH, 20).build());

        textBox = new EditBox(font, x, rowY(2), BUTTON_WIDTH, 20, Component.empty());
        textBox.setMaxLength(IndicatorLegend.MAX_LENGTH);
        // Kept out of the box as well as out of the packet: filtering only on arrival
        // would leave a player typing a formatting code, seeing it accepted, and
        // watching it vanish
        textBox.setFilter(text -> text.indexOf(ChatFormatting.PREFIX_CODE) < 0);
        textBox.setValue(legend.text());
        textBox.setResponder(text -> legend = legend.withText(text));
        addRenderableWidget(textBox);

        textColourBox = new EditBox(font, x, rowY(3), BUTTON_WIDTH, 20, Component.empty());
        textColourBox.setMaxLength(7);
        textColourBox.setFilter(text -> text.matches("#?[0-9a-fA-F]*"));
        textColourBox.setValue(hex(legend.colour()));
        textColourBox.setResponder(text -> {
            Integer parsed = parseHex(text);
            if (parsed != null)
                legend = legend.withColour(parsed);
        });
        addRenderableWidget(textColourBox);

        addRenderableWidget(Button.builder(turnLabel(), button -> {
            legend = legend.withQuarters(legend.quarters() + 1);
            button.setMessage(turnLabel());
            send(false);
        }).bounds(x, rowY(4), BUTTON_WIDTH, 20).build());

        // A mounted unit cannot be turned in place; nothing but the block form has a
        // block state to turn
        if (mount == null)
            addRenderableWidget(Button.builder(Component.translatable(LANG_PREFIX + "turn"), button -> send(true))
                    .bounds(x, rowY(5), BUTTON_WIDTH, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(x, rowY(rows()), BUTTON_WIDTH, 20).build());

        setInitialFocus(textBox);
    }

    /**
     * Where a row sits. The stack is centred on the screen rather than hung from a
     * fixed top, so the mounted form's shorter menu does not leave a hole above its
     * Done button.
     */
    private int rowY(int row) {
        return height / 2 - ((rows() + 1) * ROW_HEIGHT) / 2 + row * ROW_HEIGHT;
    }

    /** The captioned rows: five always, and the block turn on a unit of its own. */
    private int rows() {
        return mount == null ? 6 : 5;
    }

    /** Whatever is typed goes with every send, so nothing is left behind unsent. */
    private void send(boolean turn) {
        ModPackets.sendToServer(new ConfigureIndicatorPacket(pos, color, action, legend, turn, mount));
    }

    /** The one place a typed box is committed: closing the menu. */
    @Override
    public void onClose() {
        send(false);
        super.onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        drawBackground(graphics, mouseX, mouseY, partialTicks);
        int cx = width / 2;
        int top = rowY(0);
        graphics.drawCenteredString(font, title, cx, top - 28, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable(LANG_PREFIX + "rating",
                kind.rating().label(), kind.size().label()), cx, top - 16, 0x909090);
        label(graphics, "color", cx + LABEL_OFFSET, rowY(0));
        label(graphics, "action", cx + LABEL_OFFSET, rowY(1));
        label(graphics, "text", cx + LABEL_OFFSET, rowY(2));
        label(graphics, "text_color", cx + LABEL_OFFSET, rowY(3));
        label(graphics, "text_turn", cx + LABEL_OFFSET, rowY(4));
        if (mount == null)
            label(graphics, "rotation", cx + LABEL_OFFSET, rowY(5));
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    private void label(GuiGraphics graphics, String key, int x, int y) {
        graphics.drawString(font, Component.translatable(LANG_PREFIX + key), x, y + 6, 0xA0A0A0);
    }

    private Component turnLabel() {
        return Component.translatable(LANG_PREFIX + "text_turn_value", legend.quarters() * 90);
    }

    /** A colour as the box shows it, six digits behind a hash. */
    private static String hex(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    /** What a colour box says, or null if it does not yet say a whole colour. */
    private static Integer parseHex(String text) {
        String cleaned = text.startsWith("#") ? text.substring(1) : text;
        if (cleaned.length() != 6)
            return null;
        try {
            return 0xFF000000 | Integer.parseInt(cleaned, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** So the unit behind the menu keeps lighting while its cap is being chosen. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
