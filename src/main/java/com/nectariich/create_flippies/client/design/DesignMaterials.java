package com.nectariich.create_flippies.client.design;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignKind;
import com.nectariich.create_flippies.content.design_workbench.DesignMaterial;
import com.nectariich.create_flippies.content.design_workbench.annunciator.AnnunciatorMesh;
import com.nectariich.create_flippies.content.design_workbench.gauge.GaugeDial;

import dev.nectariich.hypergraphical.api.HypergraphicalAPI;
import dev.nectariich.hypergraphical.api.ModelInstance;
import dev.nectariich.hypergraphical.material.HgMaterial;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws a design's edited material elements ({@link ControlDesign#materials}) with
 * Hypergraphical materials of their own.
 *
 * Each edited element gets a runtime material made from the one its model gives it,
 * textures and all, with the edited colour, finish, opacity, glow and occlusion in
 * place of the model's. A button's lit elements get a second one while the lamp is
 * on, coloured in the element's lit colour, which the glow then shines in. The
 * material's id is a hash of everything it is made from, so the same edit on two
 * controls, or on the same control twice, shares one material.
 *
 * An annunciator rack's lenses that were not edited are drawn with no occlusion:
 * each lens is one quad whose corners sit in its socket's corners, which bake close
 * to black and would darken the whole window whatever its colour.
 *
 * A designed gauge's face submesh ({@link GaugeDial#face}) is drawn with its printed
 * dial ({@link DesignDials}) as its albedo, stretched over the submesh's UV rectangle
 * by the material's UV transform. The element's own maps are dropped, since they
 * would be stretched with it; its finish is kept, or the edited one if it was
 * edited, and its colour multiplies the dial, white unless it was edited.
 */
public final class DesignMaterials {

    private static final String PREFIX = "design_mat/";

    /** The materials registered, and the model material each was made from, to remake one after a reload. */
    private static final Map<ResourceLocation, HgMaterial> REGISTERED = new HashMap<>();

    private DesignMaterials() {}

    /**
     * Sets every instance's element materials for a design: its edits, and with
     * {@code lit} its lit elements in their lit colour. False when the mesh is not on
     * the GPU yet, so the caller tries again on a later frame.
     */
    public static boolean apply(ResourceLocation mesh, ControlDesign design, boolean lit, ModelInstance... instances) {
        List<HypergraphicalAPI.ElementRef> elements = HypergraphicalAPI.residentElements(mesh);
        if (elements == null)
            return false;
        for (ModelInstance instance : instances)
            instance.clearElementMaterials();
        GaugeDial gauge = design.gauge();
        for (HypergraphicalAPI.ElementRef element : elements) {
            DesignMaterial edit = design.material(element.part(), element.material());
            if (gauge != null && gauge.hasFace()
                    && gauge.face.equals(ControlDesign.elementKey(element.part(), element.material()))) {
                ResourceLocation dial = dialMaterial(mesh, element, edit, gauge);
                if (dial != null)
                    for (ModelInstance instance : instances)
                        instance.setElementMaterial(element.part(), element.material(), dial);
                continue;
            }
            boolean lens = edit == null && isUnshadedLens(design, element.part(), element.material());
            // Lit but never edited needs nothing: the glow shines in the model's own colour
            if (edit == null && !lens)
                continue;
            HgMaterial base = HypergraphicalAPI.elementMaterial(mesh, element.part(), element.material());
            if (base == null)
                continue;
            if (lens)
                edit = of(base).withOcclusion(0);
            boolean lights = lit && isLight(design, element.part(), element.material());
            ResourceLocation id = material(base, edit, lights);
            for (ModelInstance instance : instances)
                instance.setElementMaterial(element.part(), element.material(), id);
        }
        return true;
    }

    /**
     * How an element looks before the material editor changes it, as an edit would
     * hold it: what the model gives it, a rack lens without occlusion. Null while the
     * mesh is not on the GPU yet.
     */
    public static DesignMaterial initial(ResourceLocation mesh, DesignKind kind, String part, String material) {
        DesignMaterial base = base(mesh, part, material);
        return base != null && kind.isRack() && AnnunciatorMesh.isLens(part, material) ? base.withOcclusion(0) : base;
    }

    /** What the model gives an element, as an edit would hold it; null while the mesh is not on the GPU. */
    public static DesignMaterial base(ResourceLocation mesh, String part, String material) {
        HgMaterial base = mesh == null ? null : HypergraphicalAPI.elementMaterial(mesh, part, material);
        return base == null ? null : of(base);
    }

    private static DesignMaterial of(HgMaterial base) {
        return new DesignMaterial(rgb(base.tint.x, base.tint.y, base.tint.z), base.tint.w, base.metallic,
                1 - base.smoothness, base.emissiveMap == null ? base.emissiveStrength : 0, base.occlusion,
                DesignMaterial.AUTO);
    }

    /** Whether a button's lamp lights an element: its part as a whole, or the element itself. */
    public static boolean isLight(ControlDesign design, String part, String material) {
        List<String> lights = design.lights();
        return lights.contains(part) || lights.contains(ControlDesign.elementKey(part, material));
    }

    private static boolean isUnshadedLens(ControlDesign design, String part, String material) {
        return design.kind().isRack() && AnnunciatorMesh.isLens(part, material);
    }

    /**
     * The id of the runtime material for one edit of one model material, registering
     * it the first time, and again whenever the model material was reloaded since.
     */
    private static ResourceLocation material(HgMaterial base, DesignMaterial edit, boolean lit) {
        String seed = base.id() + "|" + edit.write() + "|" + lit;
        ResourceLocation id = new ResourceLocation(CreateFlippies.MOD_ID, PREFIX
                + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""));
        if (REGISTERED.get(id) != base) {
            HypergraphicalAPI.registerMaterial(make(id, base, edit, lit));
            REGISTERED.put(id, base);
        }
        return id;
    }

    /**
     * The id of the material a gauge's face submesh is drawn with, registering it
     * the first time; null while the face cannot be baked or the element has no
     * material yet.
     */
    private static ResourceLocation dialMaterial(ResourceLocation mesh, HypergraphicalAPI.ElementRef element,
            DesignMaterial edit, GaugeDial gauge) {
        HgMaterial base = HypergraphicalAPI.elementMaterial(mesh, element.part(), element.material());
        ResourceLocation texture = base == null ? null : DesignDials.texture(gauge);
        if (texture == null)
            return null;
        DesignMaterial surface = edit != null ? edit : of(base).withColor(0xFFFFFF);
        float[] uv = gauge.uvTransform();
        String seed = base.id() + "|dial|" + texture + "|" + surface.write() + "|" + java.util.Arrays.toString(uv);
        ResourceLocation id = new ResourceLocation(CreateFlippies.MOD_ID, PREFIX
                + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""));
        if (REGISTERED.get(id) != base) {
            int color = surface.color();
            float r = (color >> 16 & 0xFF) / 255f, g = (color >> 8 & 0xFF) / 255f, b = (color & 0xFF) / 255f;
            HypergraphicalAPI.registerMaterial(new HgMaterial(id, texture, null, null, null, null, false,
                    new Vector2f(uv[0], uv[1]), new Vector2f(uv[2], uv[3]), surface.metallic(),
                    1 - surface.roughness(), new Vector4f(r, g, b, surface.opacity()), new Vector3f(r, g, b),
                    surface.glow(),
                    surface.opacity() < 1 ? HgMaterial.AlphaMode.BLEND : HgMaterial.AlphaMode.OPAQUE,
                    base.alphaCutoff, base.doubleSided, base.reflectivity, base.sunSpecular, surface.occlusion()));
            REGISTERED.put(id, base);
        }
        return id;
    }

    private static HgMaterial make(ResourceLocation id, HgMaterial base, DesignMaterial edit, boolean lit) {
        int color = lit ? edit.effectiveLitColor() : edit.color();
        float r = (color >> 16 & 0xFF) / 255f, g = (color >> 8 & 0xFF) / 255f, b = (color & 0xFF) / 255f;
        HgMaterial.AlphaMode mode;
        if (edit.opacity() < 1)
            mode = HgMaterial.AlphaMode.BLEND;
        else if (base.alphaMode == HgMaterial.AlphaMode.BLEND && base.tint.w < 1)
            mode = HgMaterial.AlphaMode.OPAQUE; // blended only for its own opacity, which the edit made solid
        else
            mode = base.alphaMode;
        return base.withSurface(id, new Vector4f(r, g, b, edit.opacity()), edit.metallic(), 1 - edit.roughness(),
                new Vector3f(r, g, b), edit.glow(), mode, edit.occlusion());
    }

    private static int rgb(float r, float g, float b) {
        return channel(r) << 16 | channel(g) << 8 | channel(b);
    }

    private static int channel(float value) {
        return Math.round(Math.max(0, Math.min(1, value)) * 255);
    }
}
