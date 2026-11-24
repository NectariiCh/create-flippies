package com.nectariich.create_flippies.content.cabinet;

import java.util.List;

import javax.annotation.Nullable;

import com.nectariich.create_flippies.compat.StackData;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * What an electrical cabinet is: its outside size in pixels, its colour and the side
 * its door hangs on. Set on the item in the cabinet menu and carried by the cabinet
 * it places, and back onto the item it drops.
 *
 * The colour is either one of {@link #PRESETS}, the RAL colours cabinets are really
 * sold in, or any colour typed or picked ({@link #CUSTOM}). A preset's colour is
 * always its own, so a stack carrying a preset index never disagrees with itself.
 *
 * The hinge is named as seen from in front of the cabinet, looking at the door.
 *
 * A {@code standing} cabinet stands on the ground rather than hanging on a wall, and
 * may be larger ({@link #STANDING_MAX_WIDTH}); which one a spec is follows the item
 * it is on, never the menu.
 */
public record CabinetSpec(int widthPx, int heightPx, int depthPx, int rgb, int preset, Hinge hinge,
        boolean standing) {

    /** A door between two sides across and between top and bottom up, and a back and a door deep. */
    public static final int MIN_WIDTH = 3, MIN_HEIGHT = 3, MIN_DEPTH = 2;
    /**
     * Five blocks every way, wall or standing. Parts of the largest are then further
     * from the position than the game looks for entities; {@code DeviceReach} widens
     * that search to match.
     */
    public static final int MAX_WIDTH = 80, MAX_HEIGHT = 80, MAX_DEPTH = 80;
    public static final int STANDING_MAX_WIDTH = 80, STANDING_MAX_HEIGHT = 80, STANDING_MAX_DEPTH = 80;

    /** {@link #preset} for a colour of the player's own. */
    public static final int CUSTOM = -1;

    /** Where on the item the spec is kept. */
    public static final String TAG = "Cabinet";

    public enum Hinge {
        LEFT, RIGHT;

        public Hinge other() {
            return this == LEFT ? RIGHT : LEFT;
        }

        public String key() {
            return this == LEFT ? "left" : "right";
        }
    }

    /** A colour cabinets come in: its lang key under {@code ...cabinet.colour.} and its 0xRRGGBB. */
    public record Preset(String key, int rgb) {}

    public static final List<Preset> PRESETS = List.of(
            new Preset("ral7035", 0xCBD0CC), // light grey, the usual enclosure
            new Preset("ral7032", 0xB5B0A1), // pebble grey
            new Preset("ral9005", 0x1E1E1E), // jet black
            new Preset("ral9016", 0xF1F0EA), // traffic white
            new Preset("ral2004", 0xE25303), // pure orange
            new Preset("ral5015", 0x2874B2), // sky blue
            new Preset("ral6011", 0x68825B)); // reseda green

    /** A one-block light grey cabinet half a block deep, its door hung on the left. */
    public static final CabinetSpec DEFAULT = new CabinetSpec(16, 16, 8, PRESETS.get(0).rgb(), 0, Hinge.LEFT);
    /** A light grey standing cabinet a block and a half wide, three high and three quarters deep. */
    public static final CabinetSpec STANDING_DEFAULT = new CabinetSpec(24, 48, 12, PRESETS.get(0).rgb(), 0,
            Hinge.LEFT, true);

    /** A wall cabinet. */
    public CabinetSpec(int widthPx, int heightPx, int depthPx, int rgb, int preset, Hinge hinge) {
        this(widthPx, heightPx, depthPx, rgb, preset, hinge, false);
    }

    /** What a cabinet item of the kind places when nothing is set on it. */
    public static CabinetSpec defaultFor(boolean standing) {
        return standing ? STANDING_DEFAULT : DEFAULT;
    }

    public int maxWidth() {
        return standing ? STANDING_MAX_WIDTH : MAX_WIDTH;
    }

    public int maxHeight() {
        return standing ? STANDING_MAX_HEIGHT : MAX_HEIGHT;
    }

    public int maxDepth() {
        return standing ? STANDING_MAX_DEPTH : MAX_DEPTH;
    }

    /** Everything in range: sizes clamped, the colour opaque 24-bit, a preset's colour its own. */
    public CabinetSpec clamp() {
        int w = Mth.clamp(widthPx, MIN_WIDTH, maxWidth());
        int h = Mth.clamp(heightPx, MIN_HEIGHT, maxHeight());
        int d = Mth.clamp(depthPx, MIN_DEPTH, maxDepth());
        int p = preset < 0 || preset >= PRESETS.size() ? CUSTOM : preset;
        int colour = p == CUSTOM ? rgb & 0xFFFFFF : PRESETS.get(p).rgb();
        Hinge side = hinge == null ? Hinge.LEFT : hinge;
        CabinetSpec clamped = new CabinetSpec(w, h, d, colour, p, side, standing);
        return clamped.equals(this) ? this : clamped;
    }

    public CabinetSpec withSize(int widthPx, int heightPx, int depthPx) {
        return new CabinetSpec(widthPx, heightPx, depthPx, rgb, preset, hinge, standing).clamp();
    }

    public CabinetSpec withPreset(int preset) {
        return new CabinetSpec(widthPx, heightPx, depthPx, rgb, preset, hinge, standing).clamp();
    }

    /** The same cabinet standing on the ground, or hung on a wall: clamped to that kind's sizes. */
    public CabinetSpec withStanding(boolean standing) {
        return new CabinetSpec(widthPx, heightPx, depthPx, rgb, preset, hinge, standing).clamp();
    }

    /** A colour of the player's own; one that is exactly a preset's is that preset. */
    public CabinetSpec withColour(int rgb) {
        int colour = rgb & 0xFFFFFF;
        int match = CUSTOM;
        for (int i = 0; i < PRESETS.size(); i++)
            if (PRESETS.get(i).rgb() == colour)
                match = i;
        return new CabinetSpec(widthPx, heightPx, depthPx, colour, match, hinge, standing).clamp();
    }

    public CabinetSpec withHinge(Hinge hinge) {
        return new CabinetSpec(widthPx, heightPx, depthPx, rgb, preset, hinge, standing).clamp();
    }

    // NBT

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Width", widthPx);
        tag.putInt("Height", heightPx);
        tag.putInt("Depth", depthPx);
        tag.putInt("Colour", rgb);
        tag.putInt("Preset", preset);
        tag.putBoolean("HingeRight", hinge == Hinge.RIGHT);
        if (standing)
            tag.putBoolean("Standing", true);
        return tag;
    }

    /** The spec saved in {@code tag}, clamped; the default for none. */
    public static CabinetSpec load(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty())
            return DEFAULT;
        return new CabinetSpec(tag.getInt("Width"), tag.getInt("Height"), tag.getInt("Depth"), tag.getInt("Colour"),
                tag.contains("Preset") ? tag.getInt("Preset") : CUSTOM,
                tag.getBoolean("HingeRight") ? Hinge.RIGHT : Hinge.LEFT, tag.getBoolean("Standing")).clamp();
    }

    // Buffers

    public void write(FriendlyByteBuf buffer) {
        buffer.writeByte(widthPx);
        buffer.writeByte(heightPx);
        buffer.writeByte(depthPx);
        buffer.writeInt(rgb);
        buffer.writeByte(preset);
        buffer.writeBoolean(hinge == Hinge.RIGHT);
        buffer.writeBoolean(standing);
    }

    /** A spec off the wire, clamped, since a client may send anything. */
    public static CabinetSpec read(FriendlyByteBuf buffer) {
        return new CabinetSpec(buffer.readUnsignedByte(), buffer.readUnsignedByte(), buffer.readUnsignedByte(),
                buffer.readInt(), buffer.readByte(), buffer.readBoolean() ? Hinge.RIGHT : Hinge.LEFT,
                buffer.readBoolean()).clamp();
    }

    // Stacks

    /** The spec on {@code stack}, standing or not as its item is; the item's default for none. */
    public static CabinetSpec read(ItemStack stack) {
        boolean standing = standing(stack);
        CompoundTag tag = StackData.sub(stack, TAG);
        return tag == null || tag.isEmpty() ? defaultFor(standing) : load(tag).withStanding(standing);
    }

    /** Whether {@code stack} places standing cabinets. */
    public static boolean standing(ItemStack stack) {
        return stack.getItem() instanceof ElectricalCabinetItem item && item.standing();
    }

    /** Puts the spec on {@code stack}; the default is no tag at all, so plain cabinets still stack together. */
    public void apply(ItemStack stack) {
        if (equals(defaultFor(standing)))
            StackData.remove(stack, TAG);
        else
            StackData.put(stack, TAG, save());
    }
}
