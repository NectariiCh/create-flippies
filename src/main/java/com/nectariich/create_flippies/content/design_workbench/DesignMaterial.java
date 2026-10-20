package com.nectariich.create_flippies.content.design_workbench;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/**
 * How one material element of a design's model is drawn, in place of what its
 * {@code .mtl} says: the faces of one part that use one material, edited in the
 * workbench's material editor. Any textures the element has are kept; these values
 * replace the scalars that go with them.
 *
 * The colour is held the way an {@code .mtl} writes {@code Kd}, as the sRGB colour
 * the surface looks, and multiplies the element's texture when it has one.
 *
 * @param color     the surface colour, 0xRRGGBB
 * @param opacity   1 for solid, down to 0 for invisible; below 1 the element is drawn
 *                  blended, after everything solid
 * @param metallic  0 for a dielectric, 1 for bare metal
 * @param roughness 0 for a mirror finish, 1 for fully matte
 * @param glow      how brightly the element glows on its own, lamp or not, in its own
 *                  colour; 0 for not at all
 * @param occlusion how much of the model's ambient occlusion the element shows, 0 for
 *                  none (a lens lit from behind), 1 for all of it
 * @param litColor  the colour the element turns while a button's lamp lights it, or
 *                  {@link #AUTO} for its own {@link #color}; only a button lights
 *                  anything, so only a button's lit elements use it
 */
public record DesignMaterial(int color, float opacity, float metallic, float roughness, float glow, float occlusion,
        int litColor) {

    /** A {@link #litColor} that follows {@link #color}. */
    public static final int AUTO = -1;

    public static final float MAX_GLOW = 4;

    /**
     * Held in range whoever made it: colours to 24 bits, every value clamped and kept
     * to hundredths so a value from a slider and the same value read back compare
     * equal, and a non-finite value reads as the default.
     */
    public DesignMaterial {
        color &= 0xFFFFFF;
        litColor = litColor < 0 ? AUTO : litColor & 0xFFFFFF;
        opacity = unit(opacity, 1);
        metallic = unit(metallic, 0);
        roughness = unit(roughness, 0.5f);
        glow = Float.isFinite(glow) ? hundredths(Mth.clamp(glow, 0, MAX_GLOW)) : 0;
        occlusion = unit(occlusion, 1);
    }

    /** The colour the element lights in: its {@link #litColor}, or its own colour on {@link #AUTO}. */
    public int effectiveLitColor() {
        return litColor == AUTO ? color : litColor;
    }

    public DesignMaterial withColor(int color) {
        return new DesignMaterial(color, opacity, metallic, roughness, glow, occlusion, litColor);
    }

    public DesignMaterial withOpacity(float opacity) {
        return new DesignMaterial(color, opacity, metallic, roughness, glow, occlusion, litColor);
    }

    public DesignMaterial withMetallic(float metallic) {
        return new DesignMaterial(color, opacity, metallic, roughness, glow, occlusion, litColor);
    }

    public DesignMaterial withRoughness(float roughness) {
        return new DesignMaterial(color, opacity, metallic, roughness, glow, occlusion, litColor);
    }

    public DesignMaterial withGlow(float glow) {
        return new DesignMaterial(color, opacity, metallic, roughness, glow, occlusion, litColor);
    }

    public DesignMaterial withOcclusion(float occlusion) {
        return new DesignMaterial(color, opacity, metallic, roughness, glow, occlusion, litColor);
    }

    public DesignMaterial withLitColor(int litColor) {
        return new DesignMaterial(color, opacity, metallic, roughness, glow, occlusion, litColor);
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Color", color);
        tag.putFloat("Opacity", opacity);
        tag.putFloat("Metallic", metallic);
        tag.putFloat("Roughness", roughness);
        tag.putFloat("Glow", glow);
        tag.putFloat("Occlusion", occlusion);
        // Left out on auto, which is what a material made before it could be set had
        if (litColor != AUTO)
            tag.putInt("Lit", litColor);
        return tag;
    }

    /** Reads a material, with anything missing at its default: white, solid, matte-ish, unlit, fully occluded. */
    public static DesignMaterial read(CompoundTag tag) {
        return new DesignMaterial(tag.contains("Color", Tag.TAG_INT) ? tag.getInt("Color") : 0xFFFFFF,
                tag.contains("Opacity", Tag.TAG_FLOAT) ? tag.getFloat("Opacity") : 1, tag.getFloat("Metallic"),
                tag.contains("Roughness", Tag.TAG_FLOAT) ? tag.getFloat("Roughness") : 0.5f, tag.getFloat("Glow"),
                tag.contains("Occlusion", Tag.TAG_FLOAT) ? tag.getFloat("Occlusion") : 1,
                tag.contains("Lit", Tag.TAG_INT) ? tag.getInt("Lit") : AUTO);
    }

    private static float unit(float value, float fallback) {
        return Float.isFinite(value) ? hundredths(Mth.clamp(value, 0, 1)) : fallback;
    }

    private static float hundredths(float value) {
        return Math.round(value * 100) / 100f;
    }
}
