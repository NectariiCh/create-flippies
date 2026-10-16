package com.nectariich.create_flippies.mixin;

import java.util.List;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops a ray clip rebuilding the shape's box list on every cast.
 *
 * {@code VoxelShape.clip} ends, for every shape the ray is not already inside, in
 * {@code AABB.clip(this.toAabbs(), ...)} — and {@code toAabbs} is not a getter. It
 * runs the greedy merge: a copy of the shape's bit set and a walk of the whole
 * discrete grid, whose side along each axis is the number of distinct coordinates
 * the shape's boxes have on that axis. Nothing caches it. A shape that is asked to
 * clip a thousand rays merges itself a thousand times, and the merge cannot come out
 * differently, because a {@link VoxelShape} is immutable.
 *
 * On ordinary blocks that is invisible and the reason nobody has ever minded: a
 * stair's grid is a handful of cells and the walk is lost in the noise. On a large
 * panel cell carrying instruments it is the single largest thing left in a frame. A
 * dozen indicator units and their labels on a diagonal cell make a grid of 94,380
 * cells and the merge measures 0.23 milliseconds; two dozen make 213,248 and 0.52.
 * Handed a list that has already been merged, the same clip measures 0.006 — between
 * thirty and ninety times less.
 *
 * That cost is paid several times over per frame, which is what makes it worth a
 * mixin rather than a helper of our own. The crosshair's own ray goes through
 * {@code Level.clip}, which clips every cell it passes through, and a diagonal panel
 * is a staircase the ray can enter two or three cells of before it meets plate.
 * {@link com.nectariich.create_flippies.foundation.ProtrudingHitboxPicker} then
 * clips every protruding block within one cell of that ray, which on a panel is all
 * of it. Both go through this one call and neither is ours to rewrite, so the cache
 * belongs here, where it serves the vanilla pick, our own, the server's re-pick
 * behind a wire click, and anything a future mod points at a panel.
 *
 * <h2>Why this cannot go stale, and why it is only here</h2>
 *
 * The key is the shape object and the value is derived from nothing else, so there
 * is no invalidation to get wrong: a shape that has been rebuilt is a different
 * object with its own empty field, and the old one's list dies with it. A
 * {@code VoxelShape} has no mutator — {@code move}, {@code optimize} and
 * {@code getFaceShape} all return new shapes — so a list once computed describes the
 * shape it was computed from for as long as that shape exists.
 *
 * The field is volatile because shapes are clipped from the render thread, the
 * server thread and Create's chunk build threads at once. Two threads both merging
 * the same shape and one of them winning costs a merge; publishing a reference to a
 * half-built list would cost a wrong hit, and volatile is what forbids it.
 *
 * Only {@code clip}'s call is redirected, and deliberately so. {@code toAabbs} is
 * documented by its behaviour as handing back a fresh mutable list, and while every
 * caller in vanilla only reads it — the arrow's block-face scan, the piston's moved
 * boxes, the debug renderers, the collision streams — a mod that sorted or trimmed
 * the list it was given would be within its rights and would corrupt a cache shared
 * with the ray tracer. Vanilla's {@code clip} hands the list straight to
 * {@code AABB.clip}, which iterates it and nothing more, so here and only here is
 * sharing one list safe.
 */
@Mixin(VoxelShape.class)
public class VoxelShapeClipMixin {

    /** The merged boxes of this shape, once anything has clipped a ray against it. */
    @Unique
    private volatile List<AABB> create_flippies$clipBoxes;

    @Redirect(
            method = "clip",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/VoxelShape;toAabbs()Ljava/util/List;"))
    private List<AABB> create_flippies$mergedOnce(VoxelShape shape) {
        List<AABB> cached = create_flippies$clipBoxes;
        if (cached != null)
            return cached;
        return create_flippies$clipBoxes = shape.toAabbs();
    }
}
