package com.nectariich.create_flippies.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.Checkboxes;
import com.nectariich.create_flippies.compat.FlippiesScreen;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchAppearance;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlockEntity;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchControl;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchSpring;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.network.ConfigureRotarySwitchPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Wrench menu of the rotary switches: what is printed on the front, the paint on
 * the body, the handle fitted to it, which detents spring back to rest, and, for a
 * switch that is a block of its own, a quarter turn in its panel.
 *
 * The springs are checkboxes rather than another cycling button, one per detent that
 * can have one: a single "Spring return" on a two-position switch, and one for each
 * side of a three-position switch's centre, named by the way the handle is turned to
 * get there. Each is independent of the other, which a cycling button would hide.
 *
 * Cycling buttons and a Done, rather than the boxes-and-Apply the gauge menu
 * needs: every choice here is one of a handful of named alternatives, so there is
 * nothing to type and nothing to parse. Only the choices this switch really has
 * are put up, so the menu is as long as the switch is dressable.
 *
 * Each click is sent as it is made instead of batched behind Done. The switch is
 * right there behind a menu this small, so a paint job or a handle can be chosen by
 * looking at it — which is the only honest way to choose one — and Done is then
 * just a way out. The screen holds its own copy of the appearance and never reads
 * back: the server sanitises what arrives and the sanitising can only reject a
 * marking this screen was never offering.
 *
 * The mounted form differs in two things. Its switch is named by the host's
 * position and a mount rather than by a block position, and it has no turn to
 * offer: a control's facing and quarter turn are fixed when the screwdriver
 * installs it.
 */
public class RotarySwitchScreen extends FlippiesScreen {

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".rotary_switch_menu.";

    private static final int ROW_HEIGHT = 24;
    private static final int BUTTON_WIDTH = 120;
    private static final int LABEL_OFFSET = -110;

    private final BlockPos pos;
    /** Null when {@link #pos} is the switch itself rather than a host holding one. */
    private final ControlMount mount;
    private final RotarySwitchKind kind;

    private RotarySwitchAppearance appearance;

    /** The spring mask as this screen has it; sent whole with every change. */
    private int springs;

    public static void open(RotarySwitchBlockEntity rotary) {
        Minecraft.getInstance().setScreen(new RotarySwitchScreen(rotary.getBlockPos(), null, rotary.kind(),
                rotary.appearance(), rotary.springs()));
    }

    /**
     * Opens the same menu for the switch mounted at {@code mount} in the host at
     * {@code pos}. The starting values come from the control the client already
     * has, not from the packet that asked for the screen, so the buttons show what
     * is really on the panel.
     */
    public static void open(BlockPos pos, ControlMount mount) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null)
            return;
        if (!(minecraft.level.getBlockEntity(pos) instanceof ControlRackHolder host))
            return;
        HostedControl control = host.installedAt(mount);
        if (control instanceof RotarySwitchControl rotary)
            minecraft.setScreen(new RotarySwitchScreen(pos, mount, rotary.kind(), rotary.appearance(),
                    rotary.springs()));
    }

    private RotarySwitchScreen(BlockPos pos, ControlMount mount, RotarySwitchKind kind,
            RotarySwitchAppearance appearance, int springs) {
        super(Component.translatable(LANG_PREFIX + "title"));
        this.pos = pos;
        this.mount = mount;
        this.kind = kind;
        this.appearance = appearance;
        this.springs = RotarySwitchSpring.sanitise(kind, springs);
    }

    /**
     * Which rows this switch actually has, worked out at open time and kept so that
     * {@link #render} can caption them. A null caption is a row that labels itself.
     *
     * A choice with one alternative is not offered at all. Not every switch has
     * every part authored in more than one flavour — the large switch has one front,
     * one paint and one handle — and a button that cannot change anything is worse
     * than a switch that plainly has nothing to change: it invites a click, sends a
     * packet, and leaves the panel exactly as it was.
     */
    private final List<String> rows = new ArrayList<>();

    @Override
    protected void init() {
        super.init();
        rows.clear();
        int x = width / 2 - 10;

        if (kind.markings().size() > 1)
            cycle(x, "marking", this::markingLabel, () -> appearance.withMarking(appearance.marking().next()));
        if (kind.size().styles().size() > 1) {
            cycle(x, "paint", this::paintLabel, () -> appearance.nextPaint());
            cycle(x, "handle", this::handleLabel, () -> appearance.nextHandle());
        }

        // The checkbox carries its own label, so the row is left uncaptioned
        for (int position : RotarySwitchSpring.springable(kind)) {
            boolean checked = RotarySwitchSpring.springsFrom(kind, springs, position);
            addRenderableWidget(Checkboxes.create(x, rowY(rows.size()), BUTTON_WIDTH, 20,
                    RotarySwitchSpring.label(kind, position), checked, selected -> {
                        springs = RotarySwitchSpring.with(kind, springs, position, selected);
                        send(false);
                    }));
            rows.add(null);
        }

        // A mounted switch cannot be turned in place; nothing but the block form has
        // a block state to turn
        if (mount == null) {
            addRenderableWidget(Button.builder(Component.translatable(LANG_PREFIX + "turn"), button -> send(true))
                    .bounds(x, rowY(rows.size()), BUTTON_WIDTH, 20).build());
            rows.add("rotation");
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(x, rowY(rows.size()), BUTTON_WIDTH, 20).build());
    }

    /**
     * One button that steps its choice on and says so, on the next free row. The
     * label is recomputed from the appearance rather than remembered, so a button
     * always reads what the switch is now wearing.
     */
    private void cycle(int x, String caption, Supplier<Component> label, Supplier<RotarySwitchAppearance> stepped) {
        addRenderableWidget(Button.builder(label.get(), button -> {
            appearance = stepped.get();
            button.setMessage(label.get());
            send(false);
        }).bounds(x, rowY(rows.size()), BUTTON_WIDTH, 20).build());
        rows.add(caption);
    }

    /**
     * Where a row sits. The whole stack is centred on the screen rather than hung
     * from a fixed top, so a switch with fewer choices does not leave a hole above
     * the Done button.
     */
    private int rowY(int row) {
        return height / 2 - (totalRows() * ROW_HEIGHT) / 2 + row * ROW_HEIGHT;
    }

    /** Every captioned row, plus the Done button that always follows them. */
    private int totalRows() {
        int captioned = (kind.markings().size() > 1 ? 1 : 0)
                + (kind.size().styles().size() > 1 ? 2 : 0)
                + RotarySwitchSpring.springable(kind).size()
                + (mount == null ? 1 : 0);
        return captioned + 1;
    }

    private void send(boolean turn) {
        ModPackets.sendToServer(new ConfigureRotarySwitchPacket(pos, appearance, springs, turn, mount));
    }

    private Component markingLabel() {
        return appearance.marking().label();
    }

    private Component paintLabel() {
        return appearance.paint().label();
    }

    private Component handleLabel() {
        return appearance.handle().label();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        drawBackground(graphics, mouseX, mouseY, partialTicks);
        int cx = width / 2;
        int top = rowY(0);
        graphics.drawCenteredString(font, title, cx, top - 28, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable(LANG_PREFIX + "positions", kind.positions()), cx,
                top - 16, 0x909090);
        for (int row = 0; row < rows.size(); row++)
            if (rows.get(row) != null)
                label(graphics, rows.get(row), cx + LABEL_OFFSET, rowY(row));
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    private void label(GuiGraphics graphics, String key, int x, int y) {
        graphics.drawString(font, Component.translatable(LANG_PREFIX + key), x, y + 6, 0xA0A0A0);
    }

    /** So the switch behind the menu keeps ticking while its look is being chosen. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
