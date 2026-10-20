package com.nectariich.create_flippies.foundation.wire.duct;

/**
 * The two sizes a wire duct comes in. A duct is a rectangular channel lying flat on
 * a face: {@link #width} across the face, {@link #depth} out of it. The wires it
 * carries run in {@link #lanes} side by side lanes, which is also how many wires
 * one stretch of it can hold.
 */
public enum DuctSize {
    SMALL("small", 4, 2, 2),
    LARGE("large", 8, 3, 4);

    private final String id;
    private final double width;
    private final double depth;
    private final int lanes;

    DuctSize(String id, int widthPx, int depthPx, int lanes) {
        this.id = id;
        this.width = widthPx / 16.0;
        this.depth = depthPx / 16.0;
        this.lanes = lanes;
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

    public int lanes() {
        return lanes;
    }

    private double laneSpacing() {
        return width / lanes;
    }

    /** How far lane {@code lane} sits from the duct's centre line, across it, blocks. */
    public double laneOffset(int lane) {
        return (lane + 0.5) * laneSpacing() - width / 2;
    }

    /** The lane nearest a sideways offset from the centre line. */
    public int nearestLane(double offset) {
        int lane = (int) Math.round((offset + width / 2) / laneSpacing() - 0.5);
        return Math.max(0, Math.min(lanes - 1, lane));
    }

    public static DuctSize byOrdinal(int ordinal) {
        DuctSize[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : SMALL;
    }
}
