package com.nectariich.create_flippies.client.design;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.content.design_workbench.BreakerIndicator;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignMotion;
import com.nectariich.create_flippies.content.design_workbench.DesignText;
import com.nectariich.create_flippies.content.design_workbench.DesignedFootprints;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;

import dev.nectariich.hypergraphical.api.HypergraphicalAPI;
import dev.nectariich.hypergraphical.api.ModelInstance;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * The model being designed, shown standing on top of the workbench as if the bench
 * top were a panel: the plate on the bench's top face, the front rising from it and
 * the rear inside the bench.
 *
 * Drawn with the same Hypergraphical instances, the same pose and the same text
 * renderer a placed control gets, so what is aligned here is what goes on a panel.
 * The moving parts either stand at the position the workbench picked or step
 * through all of them on a loop so their travel can be checked; a button's lamp
 * parts glow while it is shown pressed, so the player can see what lights up.
 *
 * A breaker control switch's flag is a third instance, posed by its own motion. On
 * the loop a breaker is thrown the way it is used, centre, clockwise, centre,
 * counter-clockwise, and back to the centre, with the flag latching each way as the
 * handle gets there and staying put while the handle returns. Held at a position,
 * the flag stands latched the way that position would leave it: clockwise for the
 * clockwise position and counter-clockwise otherwise, as a new breaker is.
 *
 * Parts the player unticked in the workbench's part list are hidden on both
 * instances, so whatever they cover can be seen and worked on; that is the editor's
 * business only and never part of the design.
 *
 * The model shows the design's ambient occlusion as a placed control would: its
 * strength at once, and a different reach or ray count by switching to the mesh
 * baked that way ({@link ClientDesigns#baked}). That switch waits until the settings
 * have been still for a moment, so dragging a slider across its range bakes once
 * where it is let go rather than once for every value it passed.
 *
 * A designed gauge's printed face is a material of its model, drawn as a placed
 * one's is, and on the loop its needle sweeps slowly across the whole scale and
 * back rather than stepping between its two ends.
 *
 * Optionally the boxes are outlined: the footprint's front in white, the moving
 * parts' box in yellow, the rear contacts in grey, and Hypergraphical's own measure
 * of what it is drawing in cyan. Text boxes are always outlined, faintly, with the
 * one being edited in orange.
 */
public final class DesignPreview {

    /** How long the animated preview rests at each position, and how long it takes to move on. */
    private static final long STEP_MILLIS = 1200, SWING_MILLIS = 300, PRESS_MILLIS = 100;

    /**
     * The {@code previewPosition} that shows the moving parts where the model has them, with
     * no motion at all. That is the frame text boxes are authored in (a box on the moving parts
     * is stored unposed and carried by the motion afterwards), so the text editor shows this
     * and what is drawn on screen is exactly what the mouse edits. It is not necessarily one
     * of the positions: a three-position switch's first position is turned 45 degrees.
     */
    public static final int REST = -2;

    /** How long a change to how the model is baked has to stand before the preview bakes it. */
    private static final long REBAKE_DELAY_MILLIS = 400;

    /** How far text box outlines are lifted off their plane, in pixels, so the plane's own surface does not hide them. */
    private static final float OUTLINE_LIFT = 0.03f;

    private static BlockPos bench;
    private static ControlDesign design;
    private static ResourceLocation mesh;
    /** The mesh the instances draw: {@link #mesh} or a variant of it, and the bake key it was chosen for. */
    private static ResourceLocation drawn;
    private static String bake;
    /** When the bake the design asks for stopped matching {@link #bake}, or -1 while it matches. */
    private static long bakeChanged = -1;
    private static Set<String> hiddenParts = Set.of();
    private static int previewPosition = -1;
    private static int selectedText = -1;
    private static boolean showBoxes = true;
    private static ModelInstance body;
    private static ModelInstance moving;
    private static ModelInstance indicator;
    /** The glow last given to the instances, NaN for none yet, and the lights it was given to. */
    private static float glow = Float.NaN;
    private static List<String> glowLights;
    /** What the element materials were last set from, or null until they could be. */
    private static Object materials;

    /** A box to pick out in orange, in authored pixels: the rack window being edited. Null for none. */
    private static double[] highlight;

    private DesignPreview() {}

    /**
     * Shows a design on the bench, or updates what is shown. {@code design} is the
     * design as it stands in the editor, boxes included; {@code mesh} its model.
     * {@code hiddenParts} are hidden in the preview alone. {@code previewPosition}
     * holds the moving parts at one position, {@link #REST} holds them unposed, and
     * -1 animates them through all of them; {@code selectedText} is the index of the text box being edited, or -1;
     * {@code showBoxes} outlines the footprint and moving boxes.
     */
    public static void show(BlockPos bench, ControlDesign design, ResourceLocation mesh, Set<String> hiddenParts,
            int previewPosition, int selectedText, boolean showBoxes) {
        boolean respawn = body == null || !mesh.equals(DesignPreview.mesh);
        String asked = bakeOf(design);
        if (respawn) {
            hide();
        } else if (Objects.equals(asked, bake)) {
            bakeChanged = -1;
        } else if (bakeChanged < 0 || !Objects.equals(asked, bakeOf(DesignPreview.design))) {
            // The wait starts again with every new value, so a slider being dragged
            // is only baked for once it stops
            bakeChanged = Util.getMillis();
        }
        DesignPreview.bench = bench;
        DesignPreview.design = design;
        DesignPreview.mesh = mesh;
        DesignPreview.hiddenParts = Set.copyOf(hiddenParts);
        DesignPreview.previewPosition = previewPosition;
        DesignPreview.selectedText = selectedText;
        DesignPreview.showBoxes = showBoxes;
        if (respawn)
            spawn();
        else
            showParts();
        pose();
    }

    /** The bake a design asks for: null for the model's own, else the key of its own. */
    private static String bakeOf(ControlDesign design) {
        return design.occlusion().ownBake() ? design.occlusion().bakeKey() : null;
    }

    /** Puts up the three instances on the mesh the design's occlusion asks for. */
    private static void spawn() {
        if (body != null) {
            HypergraphicalAPI.destroy(body);
            HypergraphicalAPI.destroy(moving);
            HypergraphicalAPI.destroy(indicator);
        }
        drawn = ClientDesigns.baked(mesh, design.model(), design.occlusion());
        bake = bakeOf(design);
        bakeChanged = -1;
        body = HypergraphicalAPI.spawn(drawn, null, Vec3.ZERO);
        moving = HypergraphicalAPI.spawn(drawn, null, Vec3.ZERO);
        indicator = HypergraphicalAPI.spawn(drawn, null, Vec3.ZERO);
        glow = Float.NaN;
        materials = null;
        showParts();
    }

    private static void showParts() {
        // The instances split the model as a placed control's do, and the editor's
        // hidden parts come off all of them. Without a flag the third shows nothing
        Set<String> movingParts = Set.copyOf(design.movingParts());
        Set<String> indicatorParts = design.indicator() == null ? Set.of() : Set.copyOf(design.indicator().parts());
        Set<String> offBody = new HashSet<>(movingParts);
        offBody.addAll(indicatorParts);
        offBody.addAll(DesignPreview.hiddenParts);
        body.hideParts(offBody);
        moving.showOnlyParts(movingParts).hideParts(DesignPreview.hiddenParts);
        indicator.showOnlyParts(indicatorParts).hideParts(DesignPreview.hiddenParts);
    }

    /** The mesh the preview draws (the model's, or a variant baked for its occlusion), or null when nothing is shown. */
    public static ResourceLocation drawnMesh() {
        return drawn;
    }

    /** Outlines a box on the preview, such as the window being edited, or clears it with null. */
    public static void highlight(double[] box) {
        highlight = box == null ? null : box.clone();
    }

    public static void hide() {
        highlight = null;
        if (body != null) {
            HypergraphicalAPI.destroy(body);
            HypergraphicalAPI.destroy(moving);
            HypergraphicalAPI.destroy(indicator);
        }
        body = null;
        moving = null;
        indicator = null;
        mesh = null;
        drawn = null;
        bake = null;
        bakeChanged = -1;
        design = null;
        glow = Float.NaN;
        glowLights = null;
        materials = null;
    }

    /** The cell above the bench, whose floor is the panel the preview stands on. */
    private static Vec3 origin() {
        return Vec3.atLowerCornerOf(bench.above());
    }

    /**
     * Where the moving parts stand now: the chosen position, or on the loop, resting
     * at each position in turn and moving on to the next with an eased swing (a
     * button's press is quicker, as the real one is).
     */
    private static float position() {
        int positions = design.positions();
        if (previewPosition >= 0)
            return Math.min(previewPosition, positions - 1);
        if (design.indicator() != null)
            return breakerStep()[0];
        long millis = Util.getMillis();
        if (design.kind().isGauge())
            return needleSweep(millis);
        long step = millis / STEP_MILLIS;
        float travel = design.kind().isButton() ? PRESS_MILLIS : SWING_MILLIS;
        float t = Mth.clamp((millis % STEP_MILLIS) / travel, 0, 1);
        t = t * t * (3 - 2 * t);
        int to = (int) (step % positions);
        int from = (int) ((step + positions - 1) % positions);
        return Mth.lerp(t, from, to);
    }

    /** A gauge's needle on the loop: from one end of the scale to the other and back, eased at each end. */
    static float needleSweep(long millis) {
        double t = (millis % 6000L) / 6000.0 * Math.PI * 2;
        return Mth.clamp((float) (0.5 - 0.5 * Math.cos(t)), 0, 1);
    }

    /**
     * A breaker on the loop: the handle's position and the flag's, in that order. The
     * handle goes centre, clockwise, centre, counter-clockwise, resting at each and
     * swinging to the next as {@link #position()} does; the flag swings with it on
     * the way out to either side and stays latched on the way back.
     */
    private static float[] breakerStep() {
        RotarySwitchKind kind = design.rotary();
        int centre = kind.defaultPosition(), clockwise = -1, counter = -1;
        for (int i = 0; i < kind.positions(); i++) {
            if (kind.handleAngle(i) < 0)
                clockwise = i;
            else if (kind.handleAngle(i) > 0)
                counter = i;
        }
        int[] handle = { centre, clockwise, centre, counter };
        int[] flag = { BreakerIndicator.CCW, BreakerIndicator.CW, BreakerIndicator.CW, BreakerIndicator.CCW };
        long millis = Util.getMillis();
        long step = millis / STEP_MILLIS;
        float t = Mth.clamp((millis % STEP_MILLIS) / (float) SWING_MILLIS, 0, 1);
        t = t * t * (3 - 2 * t);
        int to = (int) (step % handle.length);
        int from = (to + handle.length - 1) % handle.length;
        return new float[] { Mth.lerp(t, handle[from], handle[to]), Mth.lerp(t, flag[from], flag[to]) };
    }

    /**
     * Where a breaker's flag stands now: on the loop, where {@link #breakerStep()}
     * has it; held at a position, latched the way that position leaves a new
     * breaker.
     */
    private static float indicatorPosition() {
        if (previewPosition >= 0)
            return BreakerIndicator.latch(design.rotary(), BreakerIndicator.CCW,
                    Math.min(previewPosition, design.positions() - 1));
        return breakerStep()[1];
    }

    /** Places the instances for this frame and returns the moving parts' motion. */
    private static Matrix4f pose() {
        boolean rest = previewPosition == REST;
        float position = rest ? 0 : position();
        Matrix4f motion = rest ? new Matrix4f() : DesignMotion.pose(design, position);
        Vec3 origin = origin();
        Quaternionf mount = new Quaternionf();
        DesignPose.apply(body, origin, mount, new Matrix4f(), design.scale(), design.offsetX(), design.offsetY(),
                design.offsetZ());
        DesignPose.apply(moving, origin, mount, motion, design.scale(), design.offsetX(), design.offsetY(),
                design.offsetZ());
        Matrix4f flag = rest || design.indicator() == null ? new Matrix4f()
                : DesignMotion.indicatorPose(design, indicatorPosition());
        DesignPose.apply(indicator, origin, mount, flag, design.scale(), design.offsetX(), design.offsetY(),
                design.offsetZ());
        float occlusion = design.occlusion().instanceStrength();
        body.setOcclusionStrength(occlusion);
        moving.setOcclusionStrength(occlusion);
        indicator.setOcclusionStrength(occlusion);

        // A button shows its lamp lit while it is shown pressed
        float lit = !rest && design.kind().isButton() && position >= 0.5f ? 1 : 0;
        if (lit != glow || !design.lights().equals(glowLights)) {
            DesignedModels.glow(body, design, lit);
            DesignedModels.glow(moving, design, lit);
            DesignedModels.glow(indicator, design, lit);
            glow = lit;
            glowLights = design.lights();
        }
        Object key = DesignedModels.materialsKey(design, lit > 0);
        if (!key.equals(materials) && DesignMaterials.apply(drawn, design, lit > 0, body, moving, indicator))
            materials = key;
        return motion;
    }

    /** Called once per frame after the world is drawn. */
    public static void render(PoseStack ms, Vec3 camera) {
        if (body == null || design == null)
            return;
        if (bakeChanged >= 0 && Util.getMillis() - bakeChanged >= REBAKE_DELAY_MILLIS)
            spawn();
        ClientDesigns.touch(drawn);
        Matrix4f motion = pose();
        Vec3 origin = origin();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        if (showBoxes) {
            ControlFootprint footprint = DesignedFootprints.of(design);
            for (double[] box : footprint.boxes())
                if (box != design.front())
                    outline(ms, lines, origin, camera, box, 0.6f, 0.6f, 0.6f);
            outline(ms, lines, origin, camera, design.front(), 1, 1, 1);
            outline(ms, lines, origin, camera, design.moving(), 1, 0.85f, 0.2f);
            for (ModelInstance instance : new ModelInstance[] { body, moving, indicator }) {
                AABB measured = instance.hitbox();
                if (measured != null)
                    LevelRenderer.renderLineBox(ms, lines, measured.move(camera.reverse()), 0.2f, 0.9f, 1, 1);
            }
        }
        if (highlight != null)
            outline(ms, lines, origin, camera, highlight, 1, 0.55f, 0.1f);

        ms.pushPose();
        ms.translate(origin.x - camera.x, origin.y - camera.y, origin.z - camera.z);
        List<DesignText> texts = design.texts();
        for (int i = 0; i < texts.size(); i++)
            textOutline(ms, lines, texts.get(i), motion, i == selectedText);
        buffers.endBatch(RenderType.lines());

        int light = Minecraft.getInstance().level != null
                ? LevelRenderer.getLightColor(Minecraft.getInstance().level, bench.above())
                : LightTexture.FULL_BRIGHT;
        DesignTextRenderer.draw(ms, buffers, design, new Quaternionf(), motion, light);
        ms.popPose();
        buffers.endBatch();
    }

    /**
     * The rectangle of one text box on its plane, carried with the moving parts if
     * the text is. {@code ms} stands at the cell's min corner.
     */
    private static void textOutline(PoseStack ms, VertexConsumer lines, DesignText text, Matrix4f motion,
            boolean selected) {
        ms.pushPose();
        ms.scale(1 / 16f, 1 / 16f, 1 / 16f);
        if (text.onMoving())
            ms.mulPoseMatrix(motion);
        float y = text.y() + OUTLINE_LIFT;
        float r = selected ? 1 : 0.55f, g = 0.55f, b = selected ? 0.1f : 0.6f;
        float a = selected ? 1 : 0.6f;
        // Four edges by hand: a flat box through renderLineBox would also draw its
        // zero-length uprights, whose direction the line shader cannot normalise
        PoseStack.Pose pose = ms.last();
        edge(pose, lines, text.x1(), y, text.z1(), text.x2(), y, text.z1(), r, g, b, a);
        edge(pose, lines, text.x2(), y, text.z1(), text.x2(), y, text.z2(), r, g, b, a);
        edge(pose, lines, text.x2(), y, text.z2(), text.x1(), y, text.z2(), r, g, b, a);
        edge(pose, lines, text.x1(), y, text.z2(), text.x1(), y, text.z1(), r, g, b, a);
        ms.popPose();
    }

    private static void edge(PoseStack.Pose pose, VertexConsumer lines, float x1, float y1, float z1, float x2,
            float y2, float z2, float r, float g, float b, float a) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 0)
            return;
        dx /= length;
        dy /= length;
        dz /= length;
        lines.vertex(pose.pose(), x1, y1, z1).color(r, g, b, a).normal(pose.normal(), dx, dy, dz).endVertex();
        lines.vertex(pose.pose(), x2, y2, z2).color(r, g, b, a).normal(pose.normal(), dx, dy, dz).endVertex();
    }

    private static void outline(PoseStack ms, VertexConsumer lines, Vec3 origin, Vec3 camera, double[] box, float r,
            float g, float b) {
        AABB world = new AABB(box[0] / 16, box[1] / 16, box[2] / 16, box[3] / 16, box[4] / 16, box[5] / 16)
                .move(origin).move(camera.reverse());
        LevelRenderer.renderLineBox(ms, lines, world, r, g, b, 1);
    }
}
