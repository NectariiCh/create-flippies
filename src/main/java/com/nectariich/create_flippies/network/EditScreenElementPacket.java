package com.nectariich.create_flippies.network;

import java.util.function.Predicate;

import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignStore;
import com.nectariich.create_flippies.content.design_workbench.DesignText;
import com.nectariich.create_flippies.content.display.DisplayPrint;
import com.nectariich.create_flippies.content.display.element.BarSettings;
import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.element.ElementDisplays;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ElementPaint;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostElementBudget;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * One edit the display editor makes to one display's {@link ScreenElements}: put or
 * remove an element, set some of its fields, or move it in the drawing order.
 *
 * <h2>One operation per packet, scoped to the fields it names</h2>
 *
 * A set carries a field mask and only the fields in it, so two players editing the
 * same element at once each change what they touched and nothing else: one recolouring
 * a box while the other drags it leaves it both recoloured and moved, where a packet
 * carrying the whole element would have the later of the two undo the earlier. It
 * also keeps every packet tiny, far under the 32 KiB a serverbound packet may carry,
 * whatever the display holds.
 *
 * <h2>Typed text</h2>
 *
 * The rule this replaces said nothing on a screen could be typed, only sent by a
 * display link, so that a client could not put text on a panel from a menu. Lettering
 * elements now carry text typed in the editor (what shows until a link writes to them),
 * so text does travel here, held to what any other screwdriver edit is held to: the
 * display must be loaded and within reach, and the text is filtered and cut to the
 * per-element, per-display and per-host caps exactly as a link's is. A put is
 * refused when the display's host already holds all the elements it may
 * ({@link HostElementBudget}).
 *
 * <h2>Nothing on the wire is trusted</h2>
 *
 * {@link #decode} makes every value safe before the packet exists: an id outside the
 * slots or a kind this build does not know makes the whole packet nothing; the mask is
 * cut to the fields an edit may set; numbers are clamped, colours made opaque, the
 * text filtered and a font that cannot be named dropped from the mask (rather than
 * replaced by the default, which would be an edit nobody asked for). The values are
 * then sanitised once more against the element they land on, by
 * {@link ScreenElements#setFields}, and a TTF font is taken only when the world's
 * design store holds its file.
 *
 * <h2>Adds</h2>
 *
 * An element is added by putting it: the editor chooses a free slot from the display
 * it sees and sends the element whole, which is what lets an undo bring back a deleted
 * element in its own slot with its own generation. If another player took the slot
 * first the put is refused and the next sync shows it.
 *
 * A null mount means the block at {@code pos} is the display; a mount means it is a
 * host and the display is the control there, as with every other control's packet.
 */
public class EditScreenElementPacket implements FlippiesPacket {

    public enum Op {
        REMOVE, SET, Z_TO, PUT;

        static Op byOrdinal(int ordinal) {
            Op[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : null;
        }
    }

    /** What an op came to: whether the display changed. */
    public record Outcome(boolean changed) {
        static final Outcome NOTHING = new Outcome(false);
    }

    /** The longest font name worth reading: {@code ttf:} and a hash, with room to spare. */
    static final int MAX_FONT_LENGTH = 64;

    /** The longest picture hash worth reading, with room to spare. */
    static final int MAX_ART_LENGTH = 64;

    /** Where a put element's missing width and height would come from; a put always carries both. */
    private static final Viewport PUT_VIEWPORT = new Viewport(Short.MAX_VALUE, Short.MAX_VALUE);

    /** Written in place of an op that did not decode, so a relayed nothing stays nothing. */
    private static final int NO_OP = 0xFF;

    /** Every field placeholder a set leaves out of its mask; never read, but deterministic. */
    private static final ScreenElement.Fields NO_FIELDS = new ScreenElement.Fields(0, 0, 1, 1,
            ScreenElement.DEFAULT_COLOUR, 0, ScreenElement.DEFAULT_STROKE, "", DesignText.DEFAULT_FONT, false, false,
            ScreenElement.FIT, 0, ScreenElement.ALIGN_CENTRE, ScreenElement.ALIGN_CENTRE);

    private final BlockPos pos;
    private final ControlMount mount;
    /** Null when what arrived was not an edit at all; such a packet does nothing. */
    private final Op op;
    private final int id;
    private final ElementKind kind;
    /** A z step's direction (-1 or 1), or a z move's target place. */
    private final int amount;
    private final int mask;
    private final ScreenElement.Fields values;
    /** A put's element whole, and the generation its slot is to have; null for every other op. */
    private final ScreenElement element;
    private final int generation;

    private EditScreenElementPacket(BlockPos pos, ControlMount mount, Op op, int id, ElementKind kind, int amount,
            int mask, ScreenElement.Fields values) {
        this(pos, mount, op, id, kind, amount, mask, values, null, 0);
    }

    private EditScreenElementPacket(BlockPos pos, ControlMount mount, Op op, int id, ElementKind kind, int amount,
            int mask, ScreenElement.Fields values, ScreenElement element, int generation) {
        this.pos = pos;
        this.mount = mount;
        this.op = op;
        this.id = id;
        this.kind = kind;
        this.amount = amount;
        this.mask = mask;
        this.values = values;
        this.element = element;
        this.generation = generation;
    }

    public static EditScreenElementPacket remove(BlockPos pos, ControlMount mount, int id) {
        return new EditScreenElementPacket(pos, mount, Op.REMOVE, id, null, 0, 0, NO_FIELDS);
    }

    /** Sets the fields named in {@code mask} (see {@link ScreenElement#FIELD_X} and the rest) from {@code values}. */
    public static EditScreenElementPacket set(BlockPos pos, ControlMount mount, int id, int mask,
            ScreenElement.Fields values) {
        return new EditScreenElementPacket(pos, mount, Op.SET, id, null, 0, mask & ScreenElement.ALL_FIELDS,
                values);
    }

    /** To that place in the drawing order, 0 at the back. */
    public static EditScreenElementPacket zTo(BlockPos pos, ControlMount mount, int id, int index) {
        return new EditScreenElementPacket(pos, mount, Op.Z_TO, id, null, Math.max(0, index), 0, NO_FIELDS);
    }

    /**
     * Puts an element whole into its own slot, at that place in the drawing order,
     * with the slot's generation set as given: how the editor places what it draws or
     * pastes, and how an undo brings a deleted element back so that links made
     * against it work again. Refused when the slot is taken.
     */
    public static EditScreenElementPacket put(BlockPos pos, ControlMount mount, ScreenElement element, int index,
            int generation) {
        return new EditScreenElementPacket(pos, mount, Op.PUT, element.id(), element.kind(), Math.max(0, index), 0,
                NO_FIELDS, element, generation & ScreenElements.GENERATION_MASK);
    }

    public EditScreenElementPacket(FriendlyByteBuf buffer) {
        this(decode(buffer));
    }

    private EditScreenElementPacket(EditScreenElementPacket decoded) {
        this(decoded.pos, decoded.mount, decoded.op, decoded.id, decoded.kind, decoded.amount, decoded.mask,
                decoded.values, decoded.element, decoded.generation);
    }

    /**
     * Reads a packet and makes every value in it safe; see the class comment. An op,
     * id or kind that cannot be one gives a packet whose {@link #op} is null, and the
     * rest of the buffer is left unread, since nothing after it means anything.
     */
    static EditScreenElementPacket decode(FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        ControlMount mount = buffer.readBoolean() ? ControlMount.read(buffer) : null;
        Op op = Op.byOrdinal(buffer.readUnsignedByte());
        if (op == null)
            return nothing(pos, mount);
        int id = buffer.readUnsignedByte();
        if (id >= ScreenElement.MAX_ID)
            return nothing(pos, mount);
        return switch (op) {
            case REMOVE -> remove(pos, mount, id);
            case Z_TO -> zTo(pos, mount, id, Math.min(buffer.readVarInt(), ScreenElement.MAX_ID - 1));
            case SET -> readSet(buffer, pos, mount, id);
            case PUT -> readPut(buffer, pos, mount, id);
        };
    }

    /** A put: the place, the generation and the element as a save holds it, read back through its sanitising. */
    private static EditScreenElementPacket readPut(FriendlyByteBuf buffer, BlockPos pos, ControlMount mount, int id) {
        int index = Math.min(buffer.readVarInt(), ScreenElement.MAX_ID - 1);
        int generation = buffer.readUnsignedByte() & ScreenElements.GENERATION_MASK;
        CompoundTag tag = buffer.readNbt();
        ScreenElement element = tag == null ? null : ScreenElement.read(tag, PUT_VIEWPORT);
        if (element == null || element.id() != id)
            return nothing(pos, mount);
        return put(pos, mount, element, index, generation);
    }

    private static EditScreenElementPacket readSet(FriendlyByteBuf buffer, BlockPos pos, ControlMount mount, int id) {
        int mask = buffer.readVarInt() & ScreenElement.ALL_FIELDS;
        ScreenElement.Fields d = NO_FIELDS;
        int x = has(mask, ScreenElement.FIELD_X) ? length(buffer.readVarInt(), 0) : d.x();
        int y = has(mask, ScreenElement.FIELD_Y) ? length(buffer.readVarInt(), 0) : d.y();
        // Nought is a level or upright line's; the element holds every other kind to one
        int w = has(mask, ScreenElement.FIELD_W) ? length(buffer.readVarInt(), 0) : d.w();
        int h = has(mask, ScreenElement.FIELD_H) ? length(buffer.readVarInt(), 0) : d.h();
        int colour = has(mask, ScreenElement.FIELD_COLOUR) ? buffer.readInt() | 0xFF000000 : d.colour();
        int highlight = d.highlight();
        if (has(mask, ScreenElement.FIELD_HIGHLIGHT)) {
            highlight = buffer.readInt();
            highlight = (highlight >>> 24) == 0 ? 0 : highlight;
        }
        int stroke = has(mask, ScreenElement.FIELD_STROKE)
                ? Mth.clamp(buffer.readVarInt(), 1, ScreenElement.MAX_STROKE)
                : d.stroke();
        String text = has(mask, ScreenElement.FIELD_TEXT)
                ? ScreenElement.sanitiseText(buffer.readUtf(ScreenElement.MAX_TEXT))
                : d.text();
        String font = d.font();
        if (has(mask, ScreenElement.FIELD_FONT)) {
            String read = buffer.readUtf(MAX_FONT_LENGTH);
            // A name nobody can resolve is no edit at all, not a reset to the default
            if (DesignText.isValidFont(read))
                font = read;
            else
                mask &= ~ScreenElement.FIELD_FONT;
        }
        boolean bold = has(mask, ScreenElement.FIELD_BOLD) ? buffer.readBoolean() : d.bold();
        boolean italic = has(mask, ScreenElement.FIELD_ITALIC) ? buffer.readBoolean() : d.italic();
        int size = d.size();
        if (has(mask, ScreenElement.FIELD_SIZE)) {
            size = buffer.readVarInt();
            size = size == ScreenElement.FIT ? ScreenElement.FIT
                    : Mth.clamp(size, DisplayPrint.MIN_SIZE, ScreenElement.MAX_SIZE);
        }
        int quarters = has(mask, ScreenElement.FIELD_QUARTERS) ? Math.floorMod(buffer.readByte(), 4) : d.quarters();
        int hAlign = has(mask, ScreenElement.FIELD_H_ALIGN) ? align(buffer.readByte()) : d.hAlign();
        int vAlign = has(mask, ScreenElement.FIELD_V_ALIGN) ? align(buffer.readByte()) : d.vAlign();
        String name = has(mask, ScreenElement.FIELD_NAME)
                ? ScreenElement.sanitiseName(buffer.readUtf(4 * ScreenElement.MAX_NAME))
                : d.name();
        boolean locked = has(mask, ScreenElement.FIELD_LOCKED) ? buffer.readBoolean() : d.locked();
        int radius = has(mask, ScreenElement.FIELD_RADIUS) ? length(buffer.readVarInt(), 0) : d.radius();
        boolean outline = has(mask, ScreenElement.FIELD_OUTLINE) ? buffer.readBoolean() : d.outline();
        int flip = has(mask, ScreenElement.FIELD_FLIP) ? buffer.readByte() & 3 : d.flip();
        int heads = d.heads(), headStyle = d.headStyle(), headLength = d.headLength(), headWidth = d.headWidth();
        if (has(mask, ScreenElement.FIELD_ARROW)) {
            heads = Mth.clamp(buffer.readByte(), ScreenElement.HEADS_NONE, ScreenElement.HEADS_BOTH);
            headStyle = Mth.clamp(buffer.readByte(), ScreenElement.HEAD_TRIANGLE, ScreenElement.HEAD_OPEN);
            headLength = Mth.clamp(buffer.readVarInt(), 1, ScreenElement.MAX_HEAD);
            headWidth = Mth.clamp(buffer.readVarInt(), 1, ScreenElement.MAX_HEAD);
        }
        ElementPaint paint = has(mask, ScreenElement.FIELD_PAINT) ? ElementPaint.read(buffer.readNbt()) : d.paint();
        String art = d.art();
        if (has(mask, ScreenElement.FIELD_ART)) {
            String read = buffer.readUtf(MAX_ART_LENGTH);
            // As with a font: a hash that cannot be one is no edit, not a removal
            if (read.isEmpty() || ControlDesign.isHash(read))
                art = read;
            else
                mask &= ~ScreenElement.FIELD_ART;
        }
        boolean tint = has(mask, ScreenElement.FIELD_TINT) ? buffer.readBoolean() : d.tint();
        BarSettings bar = has(mask, ScreenElement.FIELD_BAR)
                ? new BarSettings(buffer.readFloat(), buffer.readFloat(), buffer.readByte(), buffer.readBoolean())
                : d.bar();
        return set(pos, mount, id, mask, new ScreenElement.Fields(x, y, w, h, colour, highlight, stroke, text, font,
                bold, italic, size, quarters, hAlign, vAlign, name, locked, radius, outline, flip, heads, headStyle,
                headLength, headWidth, paint, art, tint, bar));
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeBoolean(mount != null);
        if (mount != null)
            mount.write(buffer);
        if (op == null) {
            buffer.writeByte(NO_OP);
            return;
        }
        buffer.writeByte(op.ordinal());
        buffer.writeByte(id);
        switch (op) {
            case Z_TO -> buffer.writeVarInt(amount);
            case SET -> writeSet(buffer);
            case PUT -> {
                buffer.writeVarInt(amount);
                buffer.writeByte(generation);
                buffer.writeNbt(element.write());
            }
            default -> {
            }
        }
    }

    /** Only the fields in the mask, in mask-bit order: an edit to one field costs a few bytes. */
    private void writeSet(FriendlyByteBuf buffer) {
        buffer.writeVarInt(mask);
        if (has(mask, ScreenElement.FIELD_X))
            buffer.writeVarInt(values.x());
        if (has(mask, ScreenElement.FIELD_Y))
            buffer.writeVarInt(values.y());
        if (has(mask, ScreenElement.FIELD_W))
            buffer.writeVarInt(values.w());
        if (has(mask, ScreenElement.FIELD_H))
            buffer.writeVarInt(values.h());
        if (has(mask, ScreenElement.FIELD_COLOUR))
            buffer.writeInt(values.colour());
        if (has(mask, ScreenElement.FIELD_HIGHLIGHT))
            buffer.writeInt(values.highlight());
        if (has(mask, ScreenElement.FIELD_STROKE))
            buffer.writeVarInt(values.stroke());
        if (has(mask, ScreenElement.FIELD_TEXT))
            buffer.writeUtf(ScreenElement.cut(values.text() == null ? "" : values.text(), ScreenElement.MAX_TEXT),
                    ScreenElement.MAX_TEXT);
        if (has(mask, ScreenElement.FIELD_FONT))
            buffer.writeUtf(values.font() == null ? "" : values.font(), MAX_FONT_LENGTH);
        if (has(mask, ScreenElement.FIELD_BOLD))
            buffer.writeBoolean(values.bold());
        if (has(mask, ScreenElement.FIELD_ITALIC))
            buffer.writeBoolean(values.italic());
        if (has(mask, ScreenElement.FIELD_SIZE))
            buffer.writeVarInt(values.size());
        if (has(mask, ScreenElement.FIELD_QUARTERS))
            buffer.writeByte(values.quarters());
        if (has(mask, ScreenElement.FIELD_H_ALIGN))
            buffer.writeByte(values.hAlign());
        if (has(mask, ScreenElement.FIELD_V_ALIGN))
            buffer.writeByte(values.vAlign());
        if (has(mask, ScreenElement.FIELD_NAME))
            buffer.writeUtf(ScreenElement.cut(values.name() == null ? "" : values.name(), ScreenElement.MAX_NAME),
                    4 * ScreenElement.MAX_NAME);
        if (has(mask, ScreenElement.FIELD_LOCKED))
            buffer.writeBoolean(values.locked());
        if (has(mask, ScreenElement.FIELD_RADIUS))
            buffer.writeVarInt(values.radius());
        if (has(mask, ScreenElement.FIELD_OUTLINE))
            buffer.writeBoolean(values.outline());
        if (has(mask, ScreenElement.FIELD_FLIP))
            buffer.writeByte(values.flip());
        if (has(mask, ScreenElement.FIELD_ARROW)) {
            buffer.writeByte(values.heads());
            buffer.writeByte(values.headStyle());
            buffer.writeVarInt(values.headLength());
            buffer.writeVarInt(values.headWidth());
        }
        if (has(mask, ScreenElement.FIELD_PAINT))
            buffer.writeNbt((values.paint() == null ? ElementPaint.SOLID : values.paint()).write());
        if (has(mask, ScreenElement.FIELD_ART))
            buffer.writeUtf(values.art() == null ? "" : values.art(), MAX_ART_LENGTH);
        if (has(mask, ScreenElement.FIELD_TINT))
            buffer.writeBoolean(values.tint());
        if (has(mask, ScreenElement.FIELD_BAR)) {
            BarSettings bar = values.bar() == null ? BarSettings.DEFAULT : values.bar();
            buffer.writeFloat(bar.min());
            buffer.writeFloat(bar.max());
            buffer.writeByte(bar.direction());
            buffer.writeBoolean(bar.easing());
        }
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            if (player == null || op == null)
                return;
            Level level = player.level();
            // The reach every other screwdriver menu's edit is held to. A refusal here
            // still falls through to the answer below, as a display that is not there
            ElementDisplay display = !level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > 64 * 64
                    ? null : ElementDisplays.at(level.getBlockEntity(pos), mount);
            Outcome outcome = display == null ? Outcome.NOTHING
                    : apply(display.elements(), display.viewport(), hash -> DesignStore.has(player.server, hash),
                            HostElementBudget.roomForElement(display), HostElementBudget.textCap(display));
            if (outcome.changed())
                display.elementsChanged();
        });
    }

    /**
     * Applies this edit to a display's elements: the whole of what the server does
     * once it has found the display, apart from saving and syncing. {@code storedFont}
     * says whether the design store holds a font file, by hash.
     */
    public Outcome apply(ScreenElements elements, Viewport viewport, Predicate<String> storedFont) {
        return apply(elements, viewport, storedFont, true, ScreenElements.MAX_TOTAL_TEXT);
    }

    /**
     * {@link #apply(ScreenElements, Viewport, Predicate)} for a display whose host may
     * hold it to less than its own caps ({@link HostElementBudget}): a put is
     * refused unless {@code roomForElement}, and text is held to
     * {@code textCap} for the whole display.
     */
    public Outcome apply(ScreenElements elements, Viewport viewport, Predicate<String> storedFont,
            boolean roomForElement, int textCap) {
        if (op == null)
            return Outcome.NOTHING;
        return switch (op) {
            case REMOVE -> new Outcome(elements.remove(id));
            case SET -> new Outcome(elements.setFields(id, mask, values, viewport, storedFont, textCap));
            case Z_TO -> new Outcome(elements.zTo(id, amount));
            case PUT -> roomForElement
                    ? new Outcome(elements.put(element, amount, generation, viewport, storedFont, textCap))
                    : Outcome.NOTHING;
        };
    }

    private static EditScreenElementPacket nothing(BlockPos pos, ControlMount mount) {
        return new EditScreenElementPacket(pos, mount, null, 0, null, 0, 0, NO_FIELDS);
    }

    private static boolean has(int mask, int field) {
        return (mask & field) != 0;
    }

    /** A box coordinate or side, in tenths, held to what a short holds. */
    private static int length(int value, int least) {
        return Mth.clamp(value, least, Short.MAX_VALUE);
    }

    private static int align(int value) {
        return Mth.clamp(value, ScreenElement.ALIGN_START, ScreenElement.ALIGN_END);
    }

    public Op op() {
        return op;
    }

    public int id() {
        return id;
    }

    public ElementKind kind() {
        return kind;
    }

    public int amount() {
        return amount;
    }

    public int mask() {
        return mask;
    }

    public ScreenElement.Fields values() {
        return values;
    }

    /** A put's element; null for any other op. */
    public ScreenElement element() {
        return element;
    }

    public int generation() {
        return generation;
    }

    public ControlMount mount() {
        return mount;
    }

    public BlockPos pos() {
        return pos;
    }
}
