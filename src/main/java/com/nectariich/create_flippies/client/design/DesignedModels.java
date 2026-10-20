package com.nectariich.create_flippies.client.design;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignMotion;

import dev.nectariich.hypergraphical.api.HypergraphicalAPI;
import dev.nectariich.hypergraphical.api.ModelInstance;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * The Hypergraphical instances that draw the fronts of placed designed controls.
 *
 * Hypergraphical draws instances in a world-space pass of its own rather than from a
 * block entity renderer, so these are kept alive by being drawn: a control's renderer
 * updates its pair every frame, and a pair no renderer touched in a frame is removed
 * at the end of it. That covers a control broken, its chunk unloaded or its host out
 * of view without any of them having to say so. Each control gets two instances of
 * the same mesh: the body with the moving parts hidden, and the moving parts alone,
 * so they can turn or press together on their own. A breaker control switch gets a
 * third, its flag alone, posed by the flag's own motion; the body hides the flag's
 * parts as well.
 *
 * The model is drawn with the mesh its design's occlusion asks for (the model's own,
 * or a variant baked the design's way, see {@link ClientDesigns}), and every
 * instance is given the design's occlusion strength.
 *
 * Each pair also keeps what a frame on its own cannot know: how far a button has
 * travelled towards where it is going, since a button only knows pressed or not and
 * would otherwise jump, and which glow its lamp parts were last given, so the glow
 * is only set again when it changes. Likewise the element materials
 * ({@link DesignMaterials}), set once the mesh is on the GPU and again when the
 * design's edits or the lamp change.
 */
public final class DesignedModels {

    /** How long a button takes to travel all the way in or out: about two ticks. */
    private static final float PRESS_MILLIS = 100;

    private static final class Tracked {
        ResourceLocation mesh;
        Set<String> movingParts;
        Set<String> indicatorParts;
        ModelInstance body;
        ModelInstance moving;
        /** A breaker's flag, or null for a control without one. */
        ModelInstance indicator;
        long frame;
        /** The position drawn last frame, and when, for easing a button. */
        float position = Float.NaN;
        long millis;
        /** The glow and lights last applied, or NaN for none yet. */
        float glow = Float.NaN;
        List<String> lights;
        /** What the element materials were last set from, or null until they could be (the mesh still loading). */
        Object materials;

        void destroy() {
            HypergraphicalAPI.destroy(body);
            HypergraphicalAPI.destroy(moving);
            if (indicator != null)
                HypergraphicalAPI.destroy(indicator);
        }
    }

    private static final Map<Object, Tracked> TRACKED = new IdentityHashMap<>();
    private static long frame;

    private DesignedModels() {}

    /**
     * Draws one control's front this frame. {@code key} identifies the control;
     * {@code mount} is its whole rotation about the middle of the cell whose min
     * corner is {@code origin}. {@code position} is where its moving parts stand, in
     * {@link DesignMotion#pose} terms; with {@code ease} it is only where they are
     * heading, and they get there over a couple of ticks (a button, whose state
     * flips at once). {@code indicatorPosition} is where a breaker's flag stands, in
     * {@link DesignMotion#indicatorPose} terms, already eased by the control; it is
     * ignored for a design without a flag. {@code glow} is how brightly its lamp
     * parts glow, 0 for dark.
     */
    public static void draw(Object key, ControlDesign design, Vec3 origin, Quaternionf mount, float position,
            float indicatorPosition, boolean ease, float glow, int light) {
        ResourceLocation mesh = ClientDesigns.mesh(design.model(), design.occlusion());
        if (mesh == null)
            return;
        Set<String> movingParts = Set.copyOf(design.movingParts());
        Set<String> indicatorParts = design.indicator() == null ? Set.of() : Set.copyOf(design.indicator().parts());
        Tracked tracked = TRACKED.get(key);
        if (tracked != null && (!tracked.mesh.equals(mesh) || !tracked.movingParts.equals(movingParts)
                || !tracked.indicatorParts.equals(indicatorParts))) {
            tracked.destroy();
            TRACKED.remove(key);
            tracked = null;
        }
        if (tracked == null) {
            tracked = new Tracked();
            tracked.mesh = mesh;
            tracked.movingParts = movingParts;
            tracked.indicatorParts = indicatorParts;
            Set<String> offBody = new HashSet<>(movingParts);
            offBody.addAll(indicatorParts);
            tracked.body = HypergraphicalAPI.spawn(mesh, null, origin).hideParts(offBody);
            tracked.moving = HypergraphicalAPI.spawn(mesh, null, origin).showOnlyParts(movingParts);
            if (!indicatorParts.isEmpty())
                tracked.indicator = HypergraphicalAPI.spawn(mesh, null, origin).showOnlyParts(indicatorParts);
            TRACKED.put(key, tracked);
        }
        tracked.frame = frame;

        long now = Util.getMillis();
        if (ease && Float.isFinite(tracked.position)) {
            float step = Math.min(now - tracked.millis, PRESS_MILLIS) / PRESS_MILLIS;
            position = tracked.position + Mth.clamp(position - tracked.position, -step, step);
        }
        tracked.position = position;
        tracked.millis = now;

        Matrix4f motion = DesignMotion.pose(design, position);
        DesignPose.apply(tracked.body, origin, mount, new Matrix4f(), design.scale(), design.offsetX(),
                design.offsetY(), design.offsetZ());
        DesignPose.apply(tracked.moving, origin, mount, motion, design.scale(), design.offsetX(), design.offsetY(),
                design.offsetZ());
        // Set every frame, which is only a field, so a design that changes under a
        // control is shown as it now is
        float occlusion = design.occlusion().instanceStrength();
        tracked.body.setPackedLight(light).setOcclusionStrength(occlusion);
        tracked.moving.setPackedLight(light).setOcclusionStrength(occlusion);
        if (tracked.indicator != null) {
            DesignPose.apply(tracked.indicator, origin, mount, DesignMotion.indicatorPose(design, indicatorPosition),
                    design.scale(), design.offsetX(), design.offsetY(), design.offsetZ());
            tracked.indicator.setPackedLight(light).setOcclusionStrength(occlusion);
        }

        if (glow != tracked.glow || !design.lights().equals(tracked.lights)) {
            glow(tracked.body, design, glow);
            glow(tracked.moving, design, glow);
            if (tracked.indicator != null)
                glow(tracked.indicator, design, glow);
            tracked.glow = glow;
            tracked.lights = design.lights();
        }
        Object materials = materialsKey(design, glow > 0);
        if (!materials.equals(tracked.materials)) {
            ModelInstance[] instances = tracked.indicator == null
                    ? new ModelInstance[] { tracked.body, tracked.moving }
                    : new ModelInstance[] { tracked.body, tracked.moving, tracked.indicator };
            if (DesignMaterials.apply(mesh, design, glow > 0, instances))
                tracked.materials = materials;
        }
    }

    /** Everything the element materials of a design's instances depend on, to tell when to set them again. */
    static Object materialsKey(ControlDesign design, boolean lit) {
        // A gauge's face is drawn as a material too, so a new face or a face moved
        // to another submesh sets them again
        return java.util.Arrays.asList(design.kind(), design.materials(), design.lights(), lit, design.gauge());
    }

    /**
     * Where a control's moving parts were last drawn, so its text can follow them
     * exactly; {@code fallback} for a control not drawn yet (its model still on its
     * way, say).
     */
    public static float position(Object key, float fallback) {
        Tracked tracked = TRACKED.get(key);
        return tracked != null && Float.isFinite(tracked.position) ? tracked.position : fallback;
    }

    /**
     * Lights a design's lamp parts on one instance at {@code strength}, or puts them
     * out at 0. Each light is a whole part, or with a "#" in it a "part#material"
     * element; where a part and one of its elements both light, the stronger glow
     * wins, which is Hypergraphical's rule. Both instances of a pair are given the same
     * calls, because a light may be on the body or on the moving parts; on the
     * instance that hides it, it simply never draws.
     */
    static void glow(ModelInstance instance, ControlDesign design, float strength) {
        instance.clearGlow();
        if (!(strength > 0))
            return;
        for (String light : design.lights()) {
            int split = light.indexOf('#');
            if (split > 0)
                instance.setElementGlow(light.substring(0, split), light.substring(split + 1), strength);
            else if (split < 0)
                instance.setPartGlow(light, strength);
        }
    }

    /** End of a frame: anything not drawn in it is gone. */
    public static void sweep() {
        for (Iterator<Tracked> it = TRACKED.values().iterator(); it.hasNext();) {
            Tracked tracked = it.next();
            if (tracked.frame != frame) {
                tracked.destroy();
                it.remove();
            }
        }
        frame++;
    }

    /** Hypergraphical drops every instance with the level, so the pairs go too. */
    public static void clear() {
        TRACKED.values().forEach(Tracked::destroy);
        TRACKED.clear();
    }
}
