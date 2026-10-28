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
import com.nectariich.create_flippies.content.design_workbench.screen.ScreenSurface;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.client.design.screen.ScreenPattern;
import com.nectariich.create_flippies.client.display.ScreenElementRenderer;
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
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
 * A designed screen shows on its glass what a placed one would, backlit: its layout,
 * or in its place one of the workbench's test patterns ({@link ScreenPattern}),
 * drawn by the displays' own renderer on the viewport the design's surface places.
 *
 * Optionally the boxes are outlined: the footprint's front in white, the moving
 * parts' box in yellow, the rear contacts in grey, and Hypergraphical's own measure
 * of what it is drawing in cyan. Text boxes are always outlined, faintly, with the
 * one being edited in orange.
 *
 * While the display editor holds the camera ({@link DesignCamera#setEditor}) a screen
 * design is laid flat: everything drawn here is carried by one rigid move that turns
 * the glass to face straight up, with the viewport's right along world +X and its
 * down along +Z, and puts the viewport's centre over the middle of the bench at
 * {@link #FLAT_HEIGHT}. It eases in and out with the camera's move, turning about the
 * viewport's centre. See {@link #flatFrame}.
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
    private static Object glowLights;
    /** What the element materials were last set from, or null until they could be. */
    private static Object materials;

    /**
     * What the screen on the bench is named by to the renderer: the key its bars ease
     * under, and the one the display editor previews it by.
     */
    public static final Object SCREEN_OWNER = new Object();

    /** What a screen design shows on its glass, and its layout as last shown, back to front. */
    private static ScreenPattern pattern = ScreenPattern.LAYOUT;
    private static List<ScreenElement> screenLayout = List.of();

    /** A box to pick out in orange, in authored pixels: the rack window being edited. Null for none. */
    private static double[] highlight;

    /** How high above the bench top the laid-flat viewport's centre stands, in blocks: below the top view's camera. */
    public static final double FLAT_HEIGHT = 0.75;

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
        // Read out of the design here, once per change, rather than once per frame
        DesignPreview.screenLayout = design.screen() != null ? design.screen().layout().snapshot() : List.of();
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

    /** What a screen design shows on its glass: its layout, or a test pattern over it. */
    public static void pattern(ScreenPattern pattern) {
        DesignPreview.pattern = pattern == null ? ScreenPattern.LAYOUT : pattern;
    }

    public static void hide() {
        highlight = null;
        screenLayout = List.of();
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
        Flat flat = flat();
        if (flat != null) {
            // DesignPose turns about the cell's middle and puts that at origin + 0.5, so
            // the flat move is handed over as that turn and the origin that lands the
            // middle where the move takes it
            mount = new Quaternionf(flat.rotation);
            Vector3f middle = flat.rotation.transform(new Vector3f(0.5f, 0.5f, 0.5f)).add(flat.translation);
            origin = origin.add(middle.x - 0.5, middle.y - 0.5, middle.z - 0.5);
        }
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

        // A button shows its lamp lit while it is shown pressed; a screen is shown
        // backlit, as its content is, so its glass glows as a lit one placed would
        float lit = design.kind().isScreen() || !rest && design.kind().isButton() && position >= 0.5f ? 1 : 0;
        Object lights = DesignedModels.glowKey(design);
        if (lit != glow || !lights.equals(glowLights)) {
            DesignedModels.glow(body, design, lit);
            DesignedModels.glow(moving, design, lit);
            DesignedModels.glow(indicator, design, lit);
            glow = lit;
            glowLights = lights;
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
        Matrix4f flat = flatMatrix();

        if (showBoxes)
            // Hypergraphical's measure is of the instances as placed, laid flat already
            for (ModelInstance instance : new ModelInstance[] { body, moving, indicator }) {
                AABB measured = instance.hitbox();
                if (measured != null)
                    LevelRenderer.renderLineBox(ms, lines, measured.move(camera.reverse()), 0.2f, 0.9f, 1, 1);
            }

        // The boxes are drawn about the camera, so the flat move goes about the cell's
        // corner as it stands relative to the camera
        ms.pushPose();
        ms.translate(origin.x - camera.x, origin.y - camera.y, origin.z - camera.z);
        //? if forge {
        ms.mulPoseMatrix(flat);
        //?} else {
        /*ms.mulPose(flat);
        *///?}
        ms.translate(camera.x - origin.x, camera.y - origin.y, camera.z - origin.z);
        if (showBoxes) {
            ControlFootprint footprint = DesignedFootprints.of(design);
            for (double[] box : footprint.boxes())
                if (box != design.front())
                    outline(ms, lines, origin, camera, box, 0.6f, 0.6f, 0.6f);
            outline(ms, lines, origin, camera, design.front(), 1, 1, 1);
            outline(ms, lines, origin, camera, design.moving(), 1, 0.85f, 0.2f);
        }
        if (highlight != null)
            outline(ms, lines, origin, camera, highlight, 1, 0.55f, 0.1f);
        ms.popPose();

        ms.pushPose();
        ms.translate(origin.x - camera.x, origin.y - camera.y, origin.z - camera.z);
        //? if forge {
        ms.mulPoseMatrix(flat);
        //?} else {
        /*ms.mulPose(flat);
        *///?}
        List<DesignText> texts = design.texts();
        for (int i = 0; i < texts.size(); i++)
            textOutline(ms, lines, texts.get(i), motion, i == selectedText);
        buffers.endBatch(RenderType.lines());

        int light = Minecraft.getInstance().level != null
                ? LevelRenderer.getLightColor(Minecraft.getInstance().level, bench.above())
                : LightTexture.FULL_BRIGHT;
        DesignTextRenderer.draw(ms, buffers, design, new Quaternionf(), motion, light);
        if (design.screen() != null)
            drawScreen(ms, buffers, design.screen(), light);
        ms.popPose();
        buffers.endBatch();
    }

    /**
     * A screen's content on its glass. {@code ms} stands at the cell's min corner; the
     * pose onto the glass is the placed screen's ({@link DesignScreenRenderer#toViewport}),
     * so what the bench shows is where a placed one shows it.
     */
    private static void drawScreen(PoseStack ms, MultiBufferSource buffers, ScreenSurface surface, int light) {
        List<ScreenElement> shown = pattern.elements(surface.viewport());
        if (shown == null)
            shown = screenLayout;
        ms.pushPose();
        ms.scale(1 / 16f, 1 / 16f, 1 / 16f);
        if (DesignScreenRenderer.toViewport(ms, surface))
            ScreenElementRenderer.draw(ms, buffers, SCREEN_OWNER, shown, surface.viewport(), true, true, light,
                    pattern.wraps());
        ms.popPose();
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
            //? if forge {
            ms.mulPoseMatrix(motion);
            //?} else {
            /*ms.mulPose(motion);
            *///?}
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
        //? if forge {
        lines.vertex(pose.pose(), x1, y1, z1).color(r, g, b, a).normal(pose.normal(), dx, dy, dz).endVertex();
        lines.vertex(pose.pose(), x2, y2, z2).color(r, g, b, a).normal(pose.normal(), dx, dy, dz).endVertex();
        //?} else {
        /*lines.addVertex(pose.pose(), x1, y1, z1).setColor(r, g, b, a).setNormal(pose, dx, dy, dz);
        lines.addVertex(pose.pose(), x2, y2, z2).setColor(r, g, b, a).setNormal(pose, dx, dy, dz);
        *///?}
    }

    private static void outline(PoseStack ms, VertexConsumer lines, Vec3 origin, Vec3 camera, double[] box, float r,
            float g, float b) {
        AABB world = new AABB(box[0] / 16, box[1] / 16, box[2] / 16, box[3] / 16, box[4] / 16, box[5] / 16)
                .move(origin).move(camera.reverse());
        LevelRenderer.renderLineBox(ms, lines, world, r, g, b, 1);
    }

    // ------------------------------------------------------------- lay flat

    /** The flat move as it stands now, in blocks about the cell's min corner: x goes to rotation * x + translation. */
    private record Flat(Quaternionf rotation, Vector3f translation) {}

    /**
     * The flat move for this frame, part way while the camera's editor move is, or
     * null when there is none: no editor, or no screen (or no glass) to lay flat.
     * Turned about the viewport's centre, which travels straight to its place.
     */
    private static Flat flat() {
        float t = DesignCamera.editorBlend();
        if (t <= 0 || design == null || design.screen() == null)
            return null;
        double[] frame = flatFrame(design.screen(), FLAT_HEIGHT);
        if (frame == null)
            return null;
        Quaternionf full = new Quaternionf().setFromNormalized(new Matrix3f((float) frame[0], (float) frame[3],
                (float) frame[6], (float) frame[1], (float) frame[4], (float) frame[7], (float) frame[2],
                (float) frame[5], (float) frame[8]));
        Quaternionf rotation = new Quaternionf().slerp(full, t);
        double[] centre = viewportCentre(design.screen());
        Vector3f from = new Vector3f((float) centre[0], (float) centre[1], (float) centre[2]);
        Vector3f to = new Vector3f(0.5f, (float) FLAT_HEIGHT, 0.5f);
        Vector3f translation = new Vector3f(from).lerp(to, t).sub(rotation.transform(new Vector3f(from)));
        return new Flat(rotation, translation);
    }

    /** {@link #flat()} as a matrix in blocks about the cell's min corner; identity when there is none. */
    private static Matrix4f flatMatrix() {
        Flat flat = flat();
        return flat == null ? new Matrix4f() : new Matrix4f().translation(flat.translation).rotate(flat.rotation);
    }

    /** The middle of a surface's viewport, in blocks from the cell's min corner. */
    private static double[] viewportCentre(ScreenSurface surface) {
        double[] f = surface.viewportFrame();
        double x = surface.viewport().widthPixels() / 2, y = surface.viewport().heightPixels() / 2;
        return new double[] { (f[0] + f[3] * x + f[6] * y) / 16, (f[1] + f[4] * x + f[7] * y) / 16,
                (f[2] + f[5] * x + f[8] * y) / 16 };
    }

    /**
     * The whole flat move for a surface, in blocks about the cell's min corner, as
     * {r00, r01, r02, r10, r11, r12, r20, r21, r22, tx, ty, tz}: a row-major rotation
     * then a translation. The rotation takes the viewport's right to world +X, the
     * glass's front to +Y and right x front to +Z, so it is always a proper rotation;
     * for a frame that is not mirrored, right x front is the viewport's down. The
     * translation puts the viewport's centre at (0.5, {@code height}, 0.5).
     *
     * The front is the one {@link DesignScreenRenderer#toViewport} poses by, from the
     * UV frame's axes, made square to the right in case the two are not quite. Null
     * when the frame spans no area. Plain numbers, so it can be checked without a game.
     */
    static double[] flatFrame(ScreenSurface surface, double height) {
        double[] f = surface.viewportFrame();
        float[] uv = surface.frame();
        // dP/du x dP/dv points into the glass, so the front is the other way
        double nx = -(uv[4] * uv[8] - uv[5] * uv[7]), ny = -(uv[5] * uv[6] - uv[3] * uv[8]),
                nz = -(uv[3] * uv[7] - uv[4] * uv[6]);
        double rx = f[3], ry = f[4], rz = f[5];
        double rLength = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (!(rLength > 0))
            return null;
        rx /= rLength;
        ry /= rLength;
        rz /= rLength;
        double along = nx * rx + ny * ry + nz * rz;
        nx -= along * rx;
        ny -= along * ry;
        nz -= along * rz;
        double nLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(nLength > 1e-9))
            return null;
        nx /= nLength;
        ny /= nLength;
        nz /= nLength;
        double zx = ry * nz - rz * ny, zy = rz * nx - rx * nz, zz = rx * ny - ry * nx;
        double[] c = viewportCentre(surface);
        // The rows are where the axes come from, so the rotation takes each to its world axis
        double tx = 0.5 - (rx * c[0] + ry * c[1] + rz * c[2]);
        double ty = height - (nx * c[0] + ny * c[1] + nz * c[2]);
        double tz = 0.5 - (zx * c[0] + zy * c[1] + zz * c[2]);
        return new double[] { rx, ry, rz, nx, ny, nz, zx, zy, zz, tx, ty, tz };
    }

    /**
     * Where a point of the screen design's viewport lies on the plate once the model
     * is laid wholly flat, in authored pixels {x, z} of the cell above the bench, as
     * the top view measures the plate; null when no screen design is shown or its
     * glass spans no area. {@code x} and {@code y} are viewport pixels from its top
     * left, +x right and +y down. Exact: the move is rigid and the frame affine.
     */
    public static double[] viewportToPlate(double x, double y) {
        if (design == null || design.screen() == null)
            return null;
        return viewportToPlate(design.screen(), x, y);
    }

    /** {@link #viewportToPlate(double, double)} for a given surface. */
    static double[] viewportToPlate(ScreenSurface surface, double x, double y) {
        double[] m = flatFrame(surface, FLAT_HEIGHT);
        if (m == null)
            return null;
        double[] f = surface.viewportFrame();
        double px = (f[0] + f[3] * x + f[6] * y) / 16, py = (f[1] + f[4] * x + f[7] * y) / 16,
                pz = (f[2] + f[5] * x + f[8] * y) / 16;
        double wx = m[0] * px + m[1] * py + m[2] * pz + m[9];
        double wz = m[6] * px + m[7] * py + m[8] * pz + m[11];
        return new double[] { wx * 16, wz * 16 };
    }

    /** The viewport's size, in pixels {width, height}, of the screen design shown, or null for none. */
    public static double[] viewportSize() {
        if (design == null || design.screen() == null)
            return null;
        return new double[] { design.screen().viewport().widthPixels(), design.screen().viewport().heightPixels() };
    }
}
