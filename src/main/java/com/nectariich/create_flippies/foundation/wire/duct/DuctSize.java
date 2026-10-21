package com.nectariich.create_flippies.foundation.wire.duct;

/**
 * The two sizes a wire duct comes in. A duct is a rectangular channel lying flat on
 * a face: {@link #width} across the face, {@link #depth} out of it.
 *
 * What one stretch of it holds is a cross-section, not a count of wires: as much
 * copper as one wire of {@link #capacityAwg} gauge (4 AWG for the regular duct, 2 AWG
 * for the large one), shared out among the wires running through it by each one's
 * own gauge ({@link DuctWires#capacity}).
 */
public enum DuctSize {
    SMALL("small", 4, 2, 4),
    LARGE("large", 8, 3, 2);

    private final String id;
    private final double width;
    private final double depth;
    private final int capacityAwg;

    DuctSize(String id, int widthPx, int depthPx, int capacityAwg) {
        this.id = id;
        this.width = widthPx / 16.0;
        this.depth = depthPx / 16.0;
        this.capacityAwg = capacityAwg;
    }

    public String id() {
        return id;
    }

    /** Across the face, blocks. */
    public double width() {
        return width;
    }

    /** Out of the face, blocks. */
    public double depth() {
        return depth;
    }

    /** The gauge of the one wire whose cross-section fills a stretch of this duct. */
    public int capacityAwg() {
        return capacityAwg;
    }

    public static DuctSize byOrdinal(int ordinal) {
        DuctSize[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : SMALL;
    }
}
