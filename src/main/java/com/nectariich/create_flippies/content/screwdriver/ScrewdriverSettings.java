package com.nectariich.create_flippies.content.screwdriver;

import com.nectariich.create_flippies.compat.NbtCompat;
import com.nectariich.create_flippies.compat.StackData;
import com.nectariich.create_flippies.content.panel.PanelEditKind;
import com.nectariich.create_flippies.content.panel.PanelPoint;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * Everything one screwdriver remembers, and the only code that reads or writes it.
 *
 * The state lives in the held stack's NBT, which is Create's own answer for a
 * multi-mode tool — the zapper family keeps its pattern there and nowhere else.
 * The reason it is the right answer here too is that both sides need the same
 * numbers for different purposes: the server has to know which ruling to mount
 * into, and the client has to draw that same ruling under the crosshair a frame
 * before anything is mounted. Anything held server side, in a capability or a map
 * keyed by player, would have to be synced deliberately, and the two copies would
 * be a desync waiting to happen. NBT on the stack is synced by the inventory
 * already.
 *
 * The direction of that sync is the one rule that keeps the two copies honest:
 * <b>only the server writes</b>. Configuration arrives as
 * {@link com.nectariich.create_flippies.network.ConfigureScrewdriverPacket} and is
 * applied to the stack the sender is holding; the pending corner of mode 2 is
 * written where the click is resolved, which is also the server. Vanilla's
 * container tracking notices the held stack has changed — {@code
 * AbstractContainerMenu.broadcastChanges} compares stacks tag and all — and sends
 * the slot back, so the client's copy follows a tick later. A tick of latency on a
 * preview is not worth a second writer: a client that wrote its own copy would
 * disagree with the server whenever a hit point landed on a lattice boundary,
 * because the position the server sees has been through a float in the use-on
 * packet.
 *
 * The turn is kept because a dense panel needs it. A standalone instrument gets
 * its quarter turn from the direction the placer was facing, which is the only
 * sensible default and is useless once four controls share a face and one of them
 * has to read the other way up.
 */
public record ScrewdriverSettings(ScrewdriverMode mode, int columns, int rows, int turn, GridCorner corner,
        BlockPos areaCorner, DynamicBlockKind kind, int yaw, PanelPoint firstPoint, BlockPos firstPanel,
        PanelEditKind editKind, int step, BlockPos editPanel) {

    /** The smallest a panel edit may cover or move, in pixels. */
    public static final int MIN_STEP = 1;

    /**
     * The largest, in pixels. A block, because both things the step means — how wide a
     * hole is drilled and how far an edge moves — are matched against the panel's own
     * block grid, and anything bigger is several clicks of something the player can see
     * the result of.
     */
    public static final int MAX_STEP = PlacementGrid.PIXELS_PER_BLOCK;

    /** What the step is until somebody changes it: a whole block, which is what an edge move usually wants. */
    public static final int DEFAULT_STEP = PlacementGrid.PIXELS_PER_BLOCK;

    private static final String MODE = "Mode";
    private static final String COLUMNS = "Columns";
    private static final String ROWS = "Rows";
    private static final String TURN = "Turn";
    private static final String CORNER = "Corner";
    private static final String AREA_CORNER = "AreaCorner";
    private static final String KIND = "Kind";
    private static final String YAW = "Yaw";
    private static final String FIRST_POINT = "FirstPoint";
    private static final String FIRST_PANEL = "FirstPanel";
    private static final String EDIT_KIND = "EditKind";
    private static final String STEP = "Step";
    private static final String EDIT_PANEL = "EditPanel";

    /** What an unconfigured screwdriver does, and the fallback for anything unreadable. */
    public static ScrewdriverSettings defaults() {
        ScrewdriverMode mode = ScrewdriverMode.FREE_PLACEMENT;
        int[] division = mode.defaultDivision();
        return new ScrewdriverSettings(mode, division[0], division[1], 0, null, null, DynamicBlockKind.PANEL, 0,
                null, null, PanelEditKind.TILE, DEFAULT_STEP, null);
    }

    public static ScrewdriverSettings read(ItemStack stack) {
        CompoundTag tag = StackData.peek(stack);
        if (tag == null)
            return defaults();
        ScrewdriverMode mode = NBTHelper.readEnum(tag, MODE, ScrewdriverMode.class);
        int columns = tag.contains(COLUMNS) ? tag.getInt(COLUMNS) : mode.defaultDivision()[0];
        int rows = tag.contains(ROWS) ? tag.getInt(ROWS) : mode.defaultDivision()[1];
        return new ScrewdriverSettings(mode, clampTracks(columns), clampTracks(rows), tag.getByte(TURN) & 3,
                GridCorner.load(tag.getCompound(CORNER)),
                // Typed, because getCompound answers with an empty tag for anything
                // else and readBlockPos reads that as the origin rather than as nothing
                tag.contains(AREA_CORNER, Tag.TAG_COMPOUND)
                        ? NbtCompat.readBlockPos(tag.getCompound(AREA_CORNER))
                        : null,
                NBTHelper.readEnum(tag, KIND, DynamicBlockKind.class),
                Math.floorMod(tag.getByte(YAW), PanelPlane.YAW_STEPS),
                PanelPoint.load(tag.getCompound(FIRST_POINT)),
                tag.contains(FIRST_PANEL, Tag.TAG_COMPOUND) ? NbtCompat.readBlockPos(tag.getCompound(FIRST_PANEL))
                        : null,
                NBTHelper.readEnum(tag, EDIT_KIND, PanelEditKind.class),
                tag.contains(STEP) ? clampStep(tag.getInt(STEP)) : DEFAULT_STEP,
                tag.contains(EDIT_PANEL, Tag.TAG_COMPOUND) ? NbtCompat.readBlockPos(tag.getCompound(EDIT_PANEL))
                        : null);
    }

    /**
     * Writes the configurable part onto a stack, leaving both pending corners alone.
     * Reconfiguring the tool is not a reason to forget where the player was
     * halfway through drawing a rectangle, and losing it silently would look like
     * the first click had never registered.
     */
    public void apply(ItemStack stack) {
        StackData.edit(stack, tag -> {
            NBTHelper.writeEnum(tag, MODE, mode);
            tag.putInt(COLUMNS, clampTracks(columns));
            tag.putInt(ROWS, clampTracks(rows));
            tag.putByte(TURN, (byte) (turn & 3));
            NBTHelper.writeEnum(tag, KIND, kind);
            tag.putByte(YAW, (byte) Math.floorMod(yaw, PanelPlane.YAW_STEPS));
            NBTHelper.writeEnum(tag, EDIT_KIND, editKind);
            tag.putInt(STEP, clampStep(step));
        });
    }

    /**
     * Records, or with null forgets, the panel the tool last edited.
     *
     * Not a pick the player made and not one they have to make: it is set by whichever
     * edit last succeeded, and read only when the crosshair is on no panel at all —
     * which is what a click aimed into the gap where a tile used to be looks like. Kept
     * apart from the chamfer's own first panel because that one means "the next click
     * finishes something", and inheriting that from an unrelated mode would build a
     * chamfer nobody asked for.
     */
    public static void setEditPanel(ItemStack stack, BlockPos panel) {
        if (panel == null) {
            forget(stack, EDIT_PANEL);
            return;
        }
        StackData.put(stack, EDIT_PANEL, NbtCompat.writeBlockPos(panel));
    }

    /** Records, or with null forgets, the first corner of a dynamic block. */
    public static void setFirstPoint(ItemStack stack, PanelPoint point) {
        if (point == null) {
            forget(stack, FIRST_POINT);
            return;
        }
        StackData.put(stack, FIRST_POINT, point.save());
    }

    /**
     * Records, or with null forgets, the first panel of a chamfer.
     *
     * A third pending pick rather than a reuse of either other, for the reason the
     * second one exists: it holds a different thing — a block that is part of a panel,
     * not a point and not a box — and switching kinds should not throw away a
     * rectangle the player was halfway through drawing.
     */
    public static void setFirstPanel(ItemStack stack, BlockPos panel) {
        if (panel == null) {
            forget(stack, FIRST_PANEL);
            return;
        }
        StackData.put(stack, FIRST_PANEL, NbtCompat.writeBlockPos(panel));
    }

    /** Records, or with null forgets, the first corner of a rectangle. */
    public static void setCorner(ItemStack stack, GridCorner corner) {
        if (corner == null) {
            forget(stack, CORNER);
            return;
        }
        StackData.put(stack, CORNER, corner.save());
    }

    /**
     * Records, or with null forgets, the first corner of a protected box.
     *
     * A second pending corner rather than the same field. The two are different
     * things — a point on a plane, snapped to the pixel, against a whole block — and
     * a tool that kept one of them would have to throw away a half-drawn rectangle
     * the moment the player switched modes to protect what they had just built.
     */
    public static void setAreaCorner(ItemStack stack, BlockPos corner) {
        if (corner == null) {
            forget(stack, AREA_CORNER);
            return;
        }
        StackData.put(stack, AREA_CORNER, NbtCompat.writeBlockPos(corner));
    }

    /** Whether either kind of rectangle is half drawn. */
    public boolean isPicking() {
        return corner != null || areaCorner != null || firstPoint != null || firstPanel != null;
    }

    private static void forget(ItemStack stack, String key) {
        //? if forge {
        CompoundTag tag = stack.getTag();
        if (tag != null)
            tag.remove(key);
        //?} else {
        /*StackData.remove(stack, key);
        *///?}
    }

    /**
     * The ruling this mode should act on: what the player chose if the mode offers
     * it, and the mode's own default otherwise. A mode switch leaves behind a pair
     * the new mode may not offer — sixteen by sixteen is not available under a
     * constraining grid — and correcting it here rather than at the switch means
     * the correction cannot be missed by a path that writes the mode some other
     * way.
     */
    public int[] division() {
        return mode.allows(columns, rows) ? new int[] { columns, rows } : mode.defaultDivision();
    }

    public ScrewdriverSettings withMode(ScrewdriverMode mode) {
        return new ScrewdriverSettings(mode, columns, rows, turn, corner, areaCorner, kind, yaw, firstPoint,
                firstPanel, editKind, step, editPanel);
    }

    public ScrewdriverSettings withDivision(int columns, int rows) {
        return new ScrewdriverSettings(mode, columns, rows, turn, corner, areaCorner, kind, yaw, firstPoint,
                firstPanel, editKind, step, editPanel);
    }

    public ScrewdriverSettings withTurn(int turn) {
        return new ScrewdriverSettings(mode, columns, rows, turn, corner, areaCorner, kind, yaw, firstPoint,
                firstPanel, editKind, step, editPanel);
    }

    public ScrewdriverSettings withKind(DynamicBlockKind kind) {
        return new ScrewdriverSettings(mode, columns, rows, turn, corner, areaCorner, kind, yaw, firstPoint,
                firstPanel, editKind, step, editPanel);
    }

    /** The yaw wraps rather than clamps, so the button always moves. */
    public ScrewdriverSettings withYaw(int yaw) {
        return new ScrewdriverSettings(mode, columns, rows, turn, corner, areaCorner, kind,
                Math.floorMod(yaw, PanelPlane.YAW_STEPS), firstPoint, firstPanel, editKind, step, editPanel);
    }

    public ScrewdriverSettings withEditKind(PanelEditKind editKind) {
        return new ScrewdriverSettings(mode, columns, rows, turn, corner, areaCorner, kind, yaw, firstPoint,
                firstPanel, editKind, step, editPanel);
    }

    public ScrewdriverSettings withStep(int step) {
        return new ScrewdriverSettings(mode, columns, rows, turn, corner, areaCorner, kind, yaw, firstPoint,
                firstPanel, editKind, clampStep(step), editPanel);
    }

    private static int clampTracks(int tracks) {
        return Mth.clamp(tracks, 1, PlacementGrid.MAX_TRACKS);
    }

    private static int clampStep(int step) {
        return Mth.clamp(step, MIN_STEP, MAX_STEP);
    }
}
