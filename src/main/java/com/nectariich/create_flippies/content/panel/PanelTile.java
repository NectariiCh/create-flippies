package com.nectariich.create_flippies.content.panel;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One block's worth of material, and where on the panel it goes.
 *
 * A tile is the answer to two questions at once, which is why it is one thing. The
 * {@link #source} box says which part of the material to take — it is stated in the
 * material's own unit cube, so cropping to it is the same operation Create performs
 * for an ordinary copycat, with the same textures, tints and shading falling out.
 * The frame — {@link #origin} and the three axes — says where that piece lands, and
 * it is a rotation, so the piece lands at whatever angle the panel is at rather than
 * at the nearest axis.
 *
 * <h2>Why this and not a box</h2>
 *
 * Because a box cannot be turned. The collision shape has no choice about that:
 * {@code VoxelShape} is a union of axis-aligned boxes and a diagonal plate can only
 * ever be a staircase of them. Geometry has no such limit — a baked quad is four
 * vertices of arbitrary floats, and the twenty-two-and-a-half-degree steps people
 * associate with Minecraft models are a restriction of the <em>JSON</em> format,
 * which nothing here goes through. So the plate is drawn at its true angle and
 * collides with a sixteenth-of-a-block staircase, and the two disagree by less than
 * the width of the line the outline is drawn with.
 *
 * <h2>Why tiles rather than one piece per cell</h2>
 *
 * The material has to repeat once per block, and on a diagonal "per block" means per
 * block <em>along the panel</em> — the u axis — not per block of the world grid. The
 * two do not line up: a diagonal crosses a cell every {@code 1/root 2} of a block, so
 * a cell holds either one piece of a tile or the end of one and the start of the
 * next. Splitting at whole u and whole v is what keeps the repeat square instead of
 * squashing it by the same root two.
 *
 * @param source     the part of the material's unit cube this tile shows: x along u,
 *                   y along v, z into the plate from its front face
 * @param origin     where the material's own origin lands, relative to the cell
 * @param alongU     unit vector the tile's x runs along
 * @param alongV     unit vector the tile's y runs along
 * @param alongDepth unit vector the tile's z runs along, pointing into the plate
 * @param clip       box, in the cell's own coordinates, to cut the placed geometry
 *                   down to, or null to keep all of it
 */
public record PanelTile(AABB source, Vec3 origin, Vec3 alongU, Vec3 alongV, Vec3 alongDepth, AABB clip) {

    /**
     * The same tile, cut down to a box once it has been placed.
     *
     * Needed only where a cell holds a piece of panel that is not a box in the plane's
     * frame. That happens when both in-plane axes are turned against the block grid —
     * a flat panel lying at forty-five degrees — because then the cell's own walls cut
     * the rectangle cornerwise and the piece comes out a polygon of up to eight sides.
     * Rather than describe that shape, the tile is allowed to overhang and the cell
     * trims it, which costs a clip and keeps every other case a box.
     */
    public PanelTile clipped(AABB box) {
        return new PanelTile(source, origin, alongU, alongV, alongDepth, box);
    }

    /** Where a point of the material's unit cube ends up, relative to the cell. */
    public Vec3 place(Vec3 point) {
        return origin.add(alongU.scale(point.x)).add(alongV.scale(point.y)).add(alongDepth.scale(point.z));
    }

    /** The same for a direction, which is the frame without the origin. */
    public Vec3 turn(Vec3 direction) {
        return alongU.scale(direction.x).add(alongV.scale(direction.y)).add(alongDepth.scale(direction.z));
    }

    /**
     * Whether the frame is left-handed, and so whether quads placed through it come
     * out wound the wrong way.
     *
     * Half the facings are: the axes are chosen so that u and v mean the same thing
     * on both sides of a surface — which is what lets a panel and the controls
     * reaching through it from behind agree — and paying for that with a mirrored
     * frame on one side is cheaper than paying for it with two coordinate systems.
     * A mirrored quad is not wrong, only inside out, and reversing its vertices puts
     * it right without touching a single position or texture coordinate.
     */
    public boolean mirrored() {
        return alongU.cross(alongV).dot(alongDepth) < 0;
    }
}
