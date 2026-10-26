package com.nectariich.create_flippies.foundation.wire.duct;

/**
 * The sizes a wire duct comes in. A duct is a rectangular channel lying flat on a
 * face: {@link #width} across the face, and half as much ({@link #depth}) out of it.
 *
 * What it holds is wires lying side by side across it, each taking up as much of
 * the width as it is thick: together no more than {@link #SPREAD} times the width
 * ({@link #capacity}), and no one wire thicker than that many times the depth
 * ({@link #maxWireDiameter}). See {@link DuctWires#fit}.
 */
public enum DuctSize {
    PX_0_5("0_5px", 0.5),
    PX_1("1px", 1),
    PX_1_5("1_5px", 1.5),
    PX_2("2px", 2),
    PX_3("3px", 3),
    PX_4("4px", 4),
    PX_6("6px", 6);

    /**
     * How many times its own size a duct takes in wires: the thicknesses of the wires
     * side by side across it add up to at most this times its width, and one wire is
     * at most this times its depth. Set so the 1.5 px duct holds two 12 AWG wires or
     * four 16 AWG ones, the middle of the range that gives both.
     */
    public static final double SPREAD = 1.5;

    /**
     * How far out of the face a duct counts as holding a wire, at least, blocks: just
     * past the depth a placeable wire runs at. The shallowest ducts are thinner than
     * that depth, and a wire run into one still lies over it, hidden by it.
     */
    private static final double MIN_HOLD_DEPTH = DuctWires.WIRE_DEPTH + DuctWires.UNIT;

    private final String id;
    private final double widthPx;
    private final double width;
    private final double depth;

    DuctSize(String id, double widthPx) {
        this.id = id;
        this.widthPx = widthPx;
        this.width = widthPx / 16.0;
        this.depth = width / 2;
    }

    /** The item and save name: {@code 1_5px} for the 1.5 px duct. */
    public String id() {
        return id;
    }

    /** Across the face, pixels. */
    public double widthPx() {
        return widthPx;
    }

    /** Across the face, blocks. */
    public double width() {
        return width;
    }

    /** Out of the face, blocks. */
    public double depth() {
        return depth;
    }

    /** Out of the face, blocks, as far as holding wires goes: never less than the depth a wire runs at. */
    public double holdDepth() {
        return Math.max(depth, MIN_HOLD_DEPTH);
    }

    /** The thickest single wire that goes in, blocks. */
    public double maxWireDiameter() {
        return SPREAD * depth;
    }

    /** How thick the wires along one stretch may add up to, side by side, blocks. */
    public double capacity() {
        return SPREAD * width;
    }

    public static DuctSize byOrdinal(int ordinal) {
        DuctSize[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : PX_4;
    }

    public static DuctSize byId(String id) {
        for (DuctSize size : values())
            if (size.id.equals(id))
                return size;
        return PX_4;
    }

    /**
     * A size saved by ordinal before there were these seven: the old regular duct
     * (4 px by 2) is the 4 px one exactly, and the old large one (8 px by 3) the
     * largest left.
     */
    public static DuctSize legacy(int ordinal) {
        return ordinal == 1 ? PX_6 : PX_4;
    }
}
