package com.nectariich.create_flippies.content.data_cord;

/**
 * Whether two ports may be joined by a data cord (spec 11.2.5). Pure, so the rules
 * are tested without a world.
 *
 * Two ports on the same block are allowed when the rules hold: COM1 to COM2 on one
 * PC is a loopback cable.
 */
public final class PortCompatibility {

    /** The longest cord, in blocks between the two port origins. */
    public static final double MAX_CORD_BLOCKS = 16;

    /** What a check found; {@link #OK} or the reason the pair was refused. */
    public enum Result {
        OK,
        DIFFERENT_TYPE,
        ROLES,
        SAME_PORT,
        OCCUPIED,
        TOO_LONG;

        public String langKey() {
            return "gui.create_flippies.pc.cord." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One end as the check sees it. {@code id} identifies the port (the same block and index give equal ids). */
    public record End(Object id, PortType type, PortRole role, boolean occupied) {}

    private PortCompatibility() {}

    public static Result check(End a, End b, double distanceBlocks) {
        if (a.id().equals(b.id()))
            return Result.SAME_PORT;
        if (a.type() != b.type())
            return Result.DIFFERENT_TYPE;
        if (a.role().partner() != b.role())
            return Result.ROLES;
        if (a.occupied() || b.occupied())
            return Result.OCCUPIED;
        if (distanceBlocks > MAX_CORD_BLOCKS)
            return Result.TOO_LONG;
        return Result.OK;
    }
}
