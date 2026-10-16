package com.nectariich.create_flippies.content.rotary_switch;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Everything about a rotary switch that is only skin: the front it is printed
 * with, the paint on its body, and which handle is fitted.
 *
 * All three are per-switch state rather than per-block, so one item covers every
 * look and a switch already on the wall can be re-dressed with a wrench. None of
 * them touches the circuit or the geometry — the body models are the same boxes
 * with a different front texture, and the handles are interchangeable — so
 * changing one is a redraw and nothing more.
 *
 * What has to be checked is that the parts named exist for the switch being
 * dressed. A marking belongs to one {@link RotarySwitchKind}, and a paint or a
 * handle is only authored at some {@link RotarySwitchSize}s, so anything arriving
 * from a save or from a client goes through {@link #forKind} before it is kept.
 */
public record RotarySwitchAppearance(RotarySwitchMarking marking, RotarySwitchStyle paint,
        RotarySwitchStyle handle) {

    /** How a switch comes out of the item: first marking, first paint, first handle. */
    public static RotarySwitchAppearance defaultFor(RotarySwitchKind kind) {
        RotarySwitchStyle style = kind.size().defaultStyle();
        return new RotarySwitchAppearance(kind.defaultMarking(), style, style);
    }

    /**
     * Which set of authored parts this look is drawn from. Taken off the marking
     * rather than passed in, because a marking already belongs to exactly one kind
     * and so already knows how big the plate it is printed on is.
     */
    public RotarySwitchSize size() {
        return marking.kind().size();
    }

    /**
     * The authored body: one full model per paint and marking, with the front
     * texture baked in rather than layered over a shared parent.
     */
    public String bodyModel() {
        return size().modelStem() + paint.id() + "-body-" + marking.id();
    }

    /** The authored handle, which carries no marking and so varies only by style. */
    public String handleModel() {
        return size().modelStem() + handle.id() + "-handle";
    }

    public RotarySwitchAppearance withMarking(RotarySwitchMarking marking) {
        return new RotarySwitchAppearance(marking, paint, handle);
    }

    public RotarySwitchAppearance withPaint(RotarySwitchStyle paint) {
        return new RotarySwitchAppearance(marking, paint, handle);
    }

    public RotarySwitchAppearance withHandle(RotarySwitchStyle handle) {
        return new RotarySwitchAppearance(marking, paint, handle);
    }

    /**
     * The next paint this switch can wear, wrapping. Stepped through the size's own
     * list rather than through every style there is, so a menu button cannot land
     * on a paint that was never authored at this size.
     */
    public RotarySwitchAppearance nextPaint() {
        return withPaint(size().next(paint));
    }

    /** The next handle that fits this switch, wrapping; see {@link #nextPaint}. */
    public RotarySwitchAppearance nextHandle() {
        return withHandle(size().next(handle));
    }

    /**
     * This look as it applies to the given switch: the marking falls back to the
     * kind's default if it names a front that belongs to a different switch, and
     * the paint and handle fall back to the size's default if they name parts that
     * were never authored at that size.
     */
    public RotarySwitchAppearance forKind(RotarySwitchKind kind) {
        RotarySwitchSize size = kind.size();
        return new RotarySwitchAppearance(marking.kind() == kind ? marking : kind.defaultMarking(),
                size.clamp(paint), size.clamp(handle));
    }

    public void write(CompoundTag tag) {
        tag.putString("Marking", marking.id());
        tag.putString("Paint", paint.id());
        tag.putString("Handle", handle.id());
    }

    /** Anything missing or unrecognised falls back to what the kind is placed with. */
    public static RotarySwitchAppearance read(CompoundTag tag, RotarySwitchKind kind) {
        RotarySwitchAppearance fallback = defaultFor(kind);
        return new RotarySwitchAppearance(
                RotarySwitchMarking.of(tag.getString("Marking"), fallback.marking()),
                RotarySwitchStyle.of(tag.getString("Paint"), fallback.paint()),
                RotarySwitchStyle.of(tag.getString("Handle"), fallback.handle()));
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeEnum(marking);
        buffer.writeEnum(paint);
        buffer.writeEnum(handle);
    }

    /**
     * Read straight off the wire, so still to be put through {@link #forKind}: a
     * client is free to name a three-position front for a two-position switch and
     * the server must not take its word for it.
     */
    public static RotarySwitchAppearance read(FriendlyByteBuf buffer) {
        return new RotarySwitchAppearance(buffer.readEnum(RotarySwitchMarking.class),
                buffer.readEnum(RotarySwitchStyle.class), buffer.readEnum(RotarySwitchStyle.class));
    }
}
