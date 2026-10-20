package com.nectariich.create_flippies.content.design_workbench;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/**
 * How a design's model shows its ambient occlusion: the darkening of its own crevices,
 * seams and the foot of a knob that makes a small imported part read as solid.
 *
 * The occlusion is baked once per model on the client, and shown on every instance
 * that draws it. Two things here decide the bake, and so need a bake of their own when
 * they differ from the defaults: how far a crevice looks for something shading it
 * ({@link #radius}) and how many rays it casts doing so ({@link #rays}). The other two
 * are applied per instance at no cost: {@link #strength} deepens or lightens the baked
 * value, and a design with occlusion turned off simply shows none.
 *
 * The defaults are exactly what every design had before this could be set, so an old
 * design looks as it did, and a design left at them writes no tag at all
 * ({@link ControlDesign#write}).
 *
 * @param enabled  whether the model shows occlusion at all
 * @param strength the exponent the baked occlusion is raised to on each instance:
 *                 0 shows none, 1 the baked value, 2 twice as deep; see
 *                 {@code ModelInstance#setOcclusionStrength}
 * @param radius   how far a vertex looks for what shades it, as a fraction of the
 *                 model's largest dimension
 * @param rays     how many rays each vertex casts, always one of {@link #RAYS}
 */
public record DesignOcclusion(boolean enabled, float strength, float radius, int rays) {

    public static final String TAG = "Occlusion";

    /** The ray counts the quality setting steps through, lowest first. */
    public static final int[] RAYS = { 32, 64, 96, 160 };

    public static final float MIN_STRENGTH = 0, MAX_STRENGTH = 2;
    public static final float MIN_RADIUS = 0.05f, MAX_RADIUS = 1;

    /**
     * What every design had before this could be set: Hypergraphical's strong bake (96
     * rays reaching 0.3 of the model) shown at 1.5 on every instance.
     */
    public static final DesignOcclusion DEFAULT = new DesignOcclusion(true, 1.5f, 0.3f, 96);

    /**
     * Held in range whoever made it: strength and radius clamped and kept to hundredths,
     * so a value from a slider and the same value read back compare equal, and a
     * non-finite one reads as the default; the rays snapped to the nearest preset.
     */
    public DesignOcclusion {
        strength = Float.isFinite(strength) ? hundredths(Mth.clamp(strength, MIN_STRENGTH, MAX_STRENGTH))
                : 1.5f;
        radius = Float.isFinite(radius) ? hundredths(Mth.clamp(radius, MIN_RADIUS, MAX_RADIUS)) : 0.3f;
        rays = nearestRays(rays);
    }

    /** Whether this is exactly {@link #DEFAULT}, which a design does not write. */
    public boolean isDefault() {
        return equals(DEFAULT);
    }

    /** The strength an instance is given: none at all while occlusion is off. */
    public float instanceStrength() {
        return enabled ? strength : 0;
    }

    /**
     * Whether the model needs a bake of its own rather than the shared default one: only
     * when occlusion shows at all and the radius or the rays differ from the default.
     */
    public boolean ownBake() {
        return enabled && (radius != DEFAULT.radius || rays != DEFAULT.rays);
    }

    /**
     * A short name for the bake these settings make, the same for every design that bakes
     * the same way, and only made of characters a resource path allows.
     */
    public String bakeKey() {
        return "r" + rays + "_" + Math.round(radius * 100);
    }

    /** Which of {@link #RAYS} this is. */
    public int quality() {
        for (int i = 0; i < RAYS.length; i++)
            if (RAYS[i] == rays)
                return i;
        return 0;
    }

    public DesignOcclusion withEnabled(boolean enabled) {
        return new DesignOcclusion(enabled, strength, radius, rays);
    }

    public DesignOcclusion withStrength(float strength) {
        return new DesignOcclusion(enabled, strength, radius, rays);
    }

    public DesignOcclusion withRadius(float radius) {
        return new DesignOcclusion(enabled, strength, radius, rays);
    }

    /** The next quality up, back to the lowest after the highest. */
    public DesignOcclusion nextQuality() {
        return new DesignOcclusion(enabled, strength, radius, RAYS[(quality() + 1) % RAYS.length]);
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Enabled", enabled);
        tag.putFloat("Strength", strength);
        tag.putFloat("Radius", radius);
        tag.putInt("Rays", rays);
        return tag;
    }

    /** Reads settings back, a value missing from the tag reading as the default's. */
    public static DesignOcclusion read(CompoundTag tag) {
        return new DesignOcclusion(!tag.contains("Enabled", Tag.TAG_BYTE) || tag.getBoolean("Enabled"),
                tag.contains("Strength", Tag.TAG_FLOAT) ? tag.getFloat("Strength") : DEFAULT.strength,
                tag.contains("Radius", Tag.TAG_FLOAT) ? tag.getFloat("Radius") : DEFAULT.radius,
                tag.contains("Rays", Tag.TAG_INT) ? tag.getInt("Rays") : DEFAULT.rays);
    }

    private static float hundredths(float value) {
        return Math.round(value * 100) / 100f;
    }

    private static int nearestRays(int rays) {
        int best = RAYS[0];
        for (int preset : RAYS)
            if (Math.abs(preset - rays) < Math.abs(best - rays))
                best = preset;
        return best;
    }
}
