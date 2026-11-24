package com.nectariich.create_flippies.content.cabinet;

import net.minecraft.nbt.CompoundTag;

/**
 * One length of DIN rail screwed to the back plate of an electrical cabinet, in the
 * cabinet's own model pixels ({@link CabinetGeometry}).
 *
 * A rail runs either level, along the model's z (across the cabinet), or upright,
 * along its y. {@code centre} is the line it runs on: the height of a level rail,
 * the z of an upright one. It covers {@code start} to {@code start + length} along
 * that line. All three are whole pixels, and since a cabinet's position is on the
 * world's pixel grid and it only ever stands at a quarter turn, so is every rail in
 * the world: a module is a pixel, as it is on a rail block.
 *
 * The rail lies on the inside face of the back plate, {@link CabinetGeometry#backPlane};
 * nothing here says how deep it stands, which is the rail section's business
 * ({@code DinRailBlock}).
 *
 * {@code id} names the rail within its cabinet, so that the cells it runs through
 * can say which of the cabinet's rails they carry. It is handed out by the cabinet
 * and never reused while the cabinet stands.
 */
public record CabinetRail(int id, boolean vertical, int start, int centre, int length) {

    /** Where it ends along its line, exclusive. */
    public int end() {
        return start + length;
    }

    /** The same rail under another id. */
    public CabinetRail withId(int id) {
        return new CabinetRail(id, vertical, start, centre, length);
    }

    /**
     * A point on its centre line, model px, {@code along} pixels from where its line
     * starts counting: {@code {y, z}}.
     */
    public double[] pointAlong(double along) {
        return vertical ? new double[] {along, centre} : new double[] {centre, along};
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Id", id);
        tag.putBoolean("Vertical", vertical);
        tag.putInt("Start", start);
        tag.putInt("Centre", centre);
        tag.putInt("Length", length);
        return tag;
    }

    public static CabinetRail load(CompoundTag tag) {
        return new CabinetRail(tag.getInt("Id"), tag.getBoolean("Vertical"), tag.getInt("Start"), tag.getInt("Centre"),
                tag.getInt("Length"));
    }
}
