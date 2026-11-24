package com.nectariich.create_flippies.content.cabinet;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One stroke of the screwdriver on a cabinet wall: a rectangle or an ellipse in the
 * wall's own {@code (u, v)} terms ({@link CabinetWall}), cut through it or, with
 * {@code fill}, put back.
 *
 * A cabinet keeps its strokes in order and its walls are what the strokes leave,
 * each in turn ({@link CabinetCuts}): so a hole drilled, a smaller one filled in the
 * middle of it and a third drilled across both come out as the player made them.
 */
public record CabinetCut(CabinetWall wall, Shape shape, double u0, double v0, double u1, double v1, boolean fill) {

    public enum Shape {
        RECT, ELLIPSE
    }

    public CabinetCut {
        double a = Math.min(u0, u1), b = Math.max(u0, u1), c = Math.min(v0, v1), d = Math.max(v0, v1);
        u0 = a;
        u1 = b;
        v0 = c;
        v1 = d;
    }

    /** Whether the point {@code (u, v)} on the wall is inside it. */
    public boolean covers(double u, double v) {
        if (u < u0 || u > u1 || v < v0 || v > v1)
            return false;
        if (shape == Shape.RECT)
            return true;
        double ru = (u1 - u0) / 2, rv = (v1 - v0) / 2;
        if (ru <= 0 || rv <= 0)
            return false;
        double du = (u - (u0 + u1) / 2) / ru, dv = (v - (v0 + v1) / 2) / rv;
        return du * du + dv * dv <= 1;
    }

    /** Whether everything {@code other} reaches lies inside this, on the same wall: then this makes it moot. */
    public boolean contains(CabinetCut other) {
        if (other.wall != wall)
            return false;
        return covers(other.u0, other.v0) && covers(other.u1, other.v0) && covers(other.u0, other.v1)
                && covers(other.u1, other.v1);
    }

    public CabinetCut withFill(boolean fill) {
        return new CabinetCut(wall, shape, u0, v0, u1, v1, fill);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("Wall", (byte) wall.ordinal());
        tag.putByte("Shape", (byte) shape.ordinal());
        tag.putFloat("U0", (float) u0);
        tag.putFloat("V0", (float) v0);
        tag.putFloat("U1", (float) u1);
        tag.putFloat("V1", (float) v1);
        if (fill)
            tag.putBoolean("Fill", true);
        return tag;
    }

    /** The stroke saved in {@code tag}, or null for one this build cannot read. */
    @Nullable
    public static CabinetCut load(CompoundTag tag) {
        CabinetWall wall = CabinetWall.byIndex(tag.getByte("Wall"));
        int shape = tag.getByte("Shape");
        if (wall == null || shape < 0 || shape >= Shape.values().length)
            return null;
        return new CabinetCut(wall, Shape.values()[shape], tag.getFloat("U0"), tag.getFloat("V0"),
                tag.getFloat("U1"), tag.getFloat("V1"), tag.getBoolean("Fill"));
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeByte(wall.ordinal());
        buffer.writeByte(shape.ordinal());
        buffer.writeFloat((float) u0);
        buffer.writeFloat((float) v0);
        buffer.writeFloat((float) u1);
        buffer.writeFloat((float) v1);
        buffer.writeBoolean(fill);
    }

    /** A stroke off the wire, or null for nonsense: a client may send anything. */
    @Nullable
    public static CabinetCut read(FriendlyByteBuf buffer) {
        CabinetWall wall = CabinetWall.byIndex(buffer.readByte());
        int shape = buffer.readByte();
        double u0 = buffer.readFloat(), v0 = buffer.readFloat(), u1 = buffer.readFloat(), v1 = buffer.readFloat();
        boolean fill = buffer.readBoolean();
        if (wall == null || shape < 0 || shape >= Shape.values().length)
            return null;
        for (double d : new double[] {u0, v0, u1, v1})
            if (!Double.isFinite(d) || Math.abs(d) > 128)
                return null;
        return new CabinetCut(wall, Shape.values()[shape], u0, v0, u1, v1, fill);
    }
}
