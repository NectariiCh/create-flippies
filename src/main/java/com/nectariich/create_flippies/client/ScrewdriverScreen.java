package com.nectariich.create_flippies.client;

import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.panel.PanelEditKind;
import com.nectariich.create_flippies.content.screwdriver.DynamicBlockKind;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverMode;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverSettings;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.SpannedControlType;
import com.nectariich.create_flippies.network.ConfigureScrewdriverPacket;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The screwdriver menu: which mode the tool is in, how finely it rules, and which
 * way up it screws a control down.
 *
 * It follows the gauge menus in this package rather than Create's
 * {@code ZapperScreen} — a plain {@link Screen} with vanilla widgets, labels drawn
 * in {@code render}, and one packet sent when the player is done — because a
 * screwdriver has no more to say than a gauge does and matching the two mod menus
 * to each other is worth more than matching one of them to Create.
 *
 * Every widget exists in every mode and the ones that do not apply are hidden
 * rather than rebuilt. Rebuilding would mean clearing the widget list from inside
 * a button's own press handler, which is the widget list being iterated at the
 * time.
 */
public class ScrewdriverScreen extends Screen {

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".screwdriver.";

    private static final int ROW_HEIGHT = 24;
    private static final int WIDGET_WIDTH = 180;
    private static final int HALF_WIDTH = 86;

    /** What both number boxes accept while it is being typed, including nothing yet. */
    private static final String DIGITS = "\\d*";

    private final InteractionHand hand;
    private final boolean cornerPending;

    private ScrewdriverMode mode;
    private int columns;
    private int rows;
    private int turn;
    private DynamicBlockKind kind;
    private int yaw;
    private PanelEditKind editKind;
    private int step;

    private Button modeButton;
    private Button divisionButton;
    private Button turnButton;
    private Button kindButton;
    private Button yawButton;
    private Button editKindButton;
    private Button clearButton;
    private EditBox columnsBox;
    private EditBox rowsBox;
    private EditBox stepBox;

    private boolean clearCorner;

    public static void open(InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null)
            return;
        minecraft.setScreen(new ScrewdriverScreen(hand, minecraft.player.getItemInHand(hand)));
    }

    private ScrewdriverScreen(InteractionHand hand, ItemStack stack) {
        super(Component.translatable(LANG_PREFIX + "title"));
        this.hand = hand;
        ScrewdriverSettings settings = ScrewdriverSettings.read(stack);
        this.mode = settings.mode();
        this.columns = settings.columns();
        this.rows = settings.rows();
        this.turn = settings.turn();
        this.kind = settings.kind();
        this.yaw = settings.yaw();
        this.editKind = settings.editKind();
        this.step = settings.step();
        this.cornerPending = settings.isPicking();
    }

    @Override
    protected void init() {
        super.init();
        int x = width / 2 - 30;
        int top = height / 2 - 60;

        modeButton = addRenderableWidget(Button.builder(mode.label(), button -> {
            mode = mode.next();
            int[] division = mode.divisions().length == 0 ? new int[] { columns, rows } : mode.defaultDivision();
            columns = division[0];
            rows = division[1];
            refresh();
        }).bounds(x, top, WIDGET_WIDTH, 20).build());

        divisionButton = addRenderableWidget(Button.builder(divisionLabel(), button -> {
            int[] division = mode.nextDivision(columns, rows);
            columns = division[0];
            rows = division[1];
            refresh();
        }).bounds(x, top + ROW_HEIGHT, WIDGET_WIDTH, 20).build());

        columnsBox = trackBox(x, top + ROW_HEIGHT, columns);
        rowsBox = trackBox(x + WIDGET_WIDTH - HALF_WIDTH, top + ROW_HEIGHT, rows);

        turnButton = addRenderableWidget(Button.builder(turnLabel(), button -> {
            turn = (turn + 1) & 3;
            refresh();
        }).bounds(x, top + 2 * ROW_HEIGHT, WIDGET_WIDTH, 20).build());

        kindButton = addRenderableWidget(Button.builder(kindLabel(), button -> {
            kind = kind.next();
            refresh();
        }).bounds(x, top + ROW_HEIGHT, WIDGET_WIDTH, 20).build());

        yawButton = addRenderableWidget(Button.builder(yawLabel(), button -> {
            yaw = (yaw + 1) % PanelPlane.YAW_STEPS;
            refresh();
        }).bounds(x, top + 2 * ROW_HEIGHT, WIDGET_WIDTH, 20).build());

        editKindButton = addRenderableWidget(Button.builder(editKindLabel(), button -> {
            editKind = editKind.next();
            refresh();
        }).bounds(x, top + ROW_HEIGHT, WIDGET_WIDTH, 20).build());

        // The step is typed rather than cycled: it is a length in pixels between one
        // and sixteen, and the useful one is whatever the cable or the gap happens to be
        stepBox = new EditBox(font, x, top + 2 * ROW_HEIGHT, HALF_WIDTH, 20, Component.empty());
        stepBox.setMaxLength(2);
        stepBox.setFilter(text -> text.matches(DIGITS));
        stepBox.setValue(Integer.toString(step));
        addRenderableWidget(stepBox);

        clearButton = addRenderableWidget(Button.builder(Component.translatable(LANG_PREFIX + "clear_corner"),
                button -> {
                    clearCorner = true;
                    applyAndClose();
                }).bounds(x, top + 3 * ROW_HEIGHT, WIDGET_WIDTH, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> applyAndClose())
                .bounds(width / 2 - 60, top + 4 * ROW_HEIGHT + 8, 120, 20).build());

        refresh();
    }

    private EditBox trackBox(int x, int y, int value) {
        EditBox box = new EditBox(font, x, y, HALF_WIDTH, 20, Component.empty());
        box.setMaxLength(2);
        box.setFilter(text -> text.matches(DIGITS));
        box.setValue(Integer.toString(value));
        addRenderableWidget(box);
        return box;
    }

    /**
     * Brings the widgets into line with the mode. Reading the boxes back here as
     * well as on apply is what stops a mode switch from discarding a track count
     * the player has typed but not committed.
     */
    private void refresh() {
        if (mode == ScrewdriverMode.DEFINE_GRID) {
            columns = parseTracks(columnsBox, columns);
            rows = parseTracks(rowsBox, rows);
        }
        if (mode == ScrewdriverMode.EDIT_PANEL)
            step = parseStep(stepBox, step);
        boolean editing = mode == ScrewdriverMode.EDIT_PANEL;
        boolean ruling = mode == ScrewdriverMode.DEFINE_GRID;
        boolean placing = mode.places();
        // The kind and the yaw are the dynamic gesture's alone: nothing else builds a
        // surface, so nothing else has a facing to turn off the cardinals
        boolean dynamic = mode == ScrewdriverMode.DEFINE_DYNAMIC_BLOCK;
        kindButton.visible = dynamic;
        kindButton.active = dynamic;
        yawButton.visible = dynamic && kind.allowsDiagonal();
        yawButton.active = yawButton.visible;
        // The turn only says which way up a control is screwed down, so it belongs to
        // the modes that screw one down
        turnButton.visible = placing;
        turnButton.active = placing;
        divisionButton.visible = placing;
        divisionButton.active = placing;
        columnsBox.visible = ruling;
        rowsBox.visible = ruling;
        columnsBox.setValue(Integer.toString(columns));
        rowsBox.setValue(Integer.toString(rows));
        editKindButton.visible = editing;
        editKindButton.active = editing;
        // A tile is the panel's own square, and takes no size from the tool
        stepBox.visible = editing && editKind.sized();
        stepBox.setValue(Integer.toString(step));
        editKindButton.setMessage(editKindLabel());
        // Offered in every mode, because every mode can leave a rectangle half
        // drawn: a grid, a protected box, or a label being sized as it goes on
        clearButton.visible = cornerPending;
        clearButton.active = cornerPending;
        modeButton.setMessage(mode.label());
        divisionButton.setMessage(divisionLabel());
        turnButton.setMessage(turnLabel());
        kindButton.setMessage(kindLabel());
        yawButton.setMessage(yawLabel());
    }

    private Component divisionLabel() {
        return Component.translatable(LANG_PREFIX + "division", columns, rows);
    }

    private Component turnLabel() {
        return Component.translatable(LANG_PREFIX + "turn", turn * 90);
    }

    private Component kindLabel() {
        return Component.translatable(LANG_PREFIX + "dynamic_kind", kind.label());
    }

    private Component yawLabel() {
        return Component.translatable(LANG_PREFIX + "yaw", yaw * 45);
    }

    private Component editKindLabel() {
        return Component.translatable(LANG_PREFIX + "dynamic_kind", editKind.label());
    }

    private void applyAndClose() {
        if (mode == ScrewdriverMode.DEFINE_GRID) {
            columns = parseTracks(columnsBox, columns);
            rows = parseTracks(rowsBox, rows);
        }
        if (mode == ScrewdriverMode.EDIT_PANEL)
            step = parseStep(stepBox, step);
        ModPackets.CHANNEL
                .sendToServer(new ConfigureScrewdriverPacket(hand, mode, columns, rows, turn, kind, yaw, editKind,
                        step, clearCorner));
        onClose();
    }

    private static int parseTracks(EditBox box, int fallback) {
        try {
            return Mth.clamp(Integer.parseInt(box.getValue()), 1, PlacementGrid.MAX_TRACKS);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseStep(EditBox box, int fallback) {
        try {
            return Mth.clamp(Integer.parseInt(box.getValue()), ScrewdriverSettings.MIN_STEP,
                    ScrewdriverSettings.MAX_STEP);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        int cx = width / 2;
        int top = height / 2 - 60;
        graphics.drawCenteredString(font, title, cx, top - 20, 0xFFFFFF);
        label(graphics, "mode", cx - 150, top);
        // The protection mode rules nothing, so the row is blank rather than naming
        // a division that would not be used
        if (mode == ScrewdriverMode.DEFINE_GRID)
            label(graphics, "tracks", cx - 150, top + ROW_HEIGHT);
        else if (mode.places())
            label(graphics, "cells", cx - 150, top + ROW_HEIGHT);
        if (mode.places())
            label(graphics, "turn_label", cx - 150, top + 2 * ROW_HEIGHT);
        if (mode == ScrewdriverMode.DEFINE_DYNAMIC_BLOCK) {
            label(graphics, "kind_label", cx - 150, top + ROW_HEIGHT);
            if (kind.allowsDiagonal())
                label(graphics, "yaw_label", cx - 150, top + 2 * ROW_HEIGHT);
        }
        if (mode == ScrewdriverMode.EDIT_PANEL) {
            label(graphics, "kind_label", cx - 150, top + ROW_HEIGHT);
            if (editKind.sized()) {
                label(graphics, "step_label", cx - 150, top + 2 * ROW_HEIGHT);
                graphics.drawString(font, Component.translatable(LANG_PREFIX + "step_pixels"),
                        cx - 30 + HALF_WIDTH + 6, top + 2 * ROW_HEIGHT + 6, 0xA0A0A0);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTicks);
        graphics.drawCenteredString(font, Component.translatable(LANG_PREFIX + "hint_" + mode.name().toLowerCase(Locale.ROOT)), cx,
                top + 4 * ROW_HEIGHT - 8, 0xA0A0A0);
        // A chamfer is the one dynamic block not drawn corner to corner, so the hint
        // above it is wrong for it and has to be corrected rather than repeated
        if (mode == ScrewdriverMode.DEFINE_DYNAMIC_BLOCK && (kind.picksPanels() || kind.picksEdge()))
            graphics.drawCenteredString(font,
                    Component.translatable(LANG_PREFIX + "hint_" + kind.name().toLowerCase(Locale.ROOT)), cx,
                    top + 4 * ROW_HEIGHT + 4, 0xA0A0A0);
        // The three edits differ in what a click covers, which the one hint above
        // cannot say for all of them
        if (mode == ScrewdriverMode.EDIT_PANEL)
            graphics.drawCenteredString(font, Component.translatable(LANG_PREFIX + "hint_edit_" + editKind.key()), cx,
                    top + 4 * ROW_HEIGHT + 4, 0xA0A0A0);
        // The placement modes behave differently for a control that is drawn to size,
        // and the tool is open in the player's hands with that control in the other
        if (mode.places() && spannedInHand())
            graphics.drawCenteredString(font, Component.translatable(LANG_PREFIX + "hint_spanned"), cx,
                    top + 4 * ROW_HEIGHT + 4, 0xA0A0A0);
    }

    /** Whether the hand that is not holding the tool holds a control drawn to size. */
    private boolean spannedInHand() {
        return Minecraft.getInstance().player != null
                && ControlTypes.forItem(Minecraft.getInstance().player
                        .getItemInHand(ScrewdriverItem.otherHand(hand))) instanceof SpannedControlType;
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
