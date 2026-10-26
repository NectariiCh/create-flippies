package com.nectariich.create_flippies.client.display.editor;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * The display editor's view of a display in the world, through {@link EditorCamera}:
 * the camera flies from the player's eyes to face the glass head-on, and once there
 * the view is orthographic, so the mapping between the screen and the viewport is
 * affine and exact.
 *
 * The mapping is worked out from the pose the camera is flying to, not from wherever
 * it is mid-flight, which is why it is only offered once the camera is
 * {@link #ready}. It inverts the full two by two linear part, so it holds for a
 * mirrored frame too, where the viewport's +y runs up the screen rather than down.
 */
public final class WorldGlassView implements GlassView {

    /** How much room is left around the display when fitting it, in GUI pixels. */
    private static final double FIT_MARGIN = 8;

    private final Supplier<DisplayGlass.Frame> frame;

    /**
     * A view of the glass {@code frame} names. It is asked again every frame, since the
     * Rotate button can turn the block and a sync may replace a host's control objects.
     */
    public WorldGlassView(Supplier<DisplayGlass.Frame> frame) {
        this.frame = frame;
    }

    /** A view of the display at {@code pos} and {@code mount} (null for a display block) in the client's level. */
    public WorldGlassView(BlockPos pos, ControlMount mount) {
        this(() -> DisplayGlass.at(Minecraft.getInstance().level, pos, mount));
    }

    @Override
    public void open() {
        EditorCamera.enter(frame);
    }

    @Override
    public void close() {
        EditorCamera.exit();
    }

    @Override
    public boolean ready() {
        return EditorCamera.ready();
    }

    @Override
    public double[] toScreen(double vx, double vy, int width, int height) {
        EditorCamera.View view = EditorCamera.view();
        if (!ready() || view == null)
            return null;
        double unit = Viewport.UNITS_PER_PIXEL;
        double[] ndc = view.toNdc(vx / unit, vy / unit, EditorCamera.zoom(), EditorCamera.windowAspect());
        return new double[] { EditorCamera.View.guiX(ndc[0], width), EditorCamera.View.guiY(ndc[1], height) };
    }

    @Override
    public double[] toViewport(double gx, double gy, int width, int height) {
        EditorCamera.View view = EditorCamera.view();
        if (!ready() || view == null)
            return null;
        double[] pixels = view.fromNdc(EditorCamera.View.ndcX(gx, width), EditorCamera.View.ndcY(gy, height),
                EditorCamera.zoom(), EditorCamera.windowAspect());
        if (pixels == null)
            return null;
        double unit = Viewport.UNITS_PER_PIXEL;
        return new double[] { pixels[0] * unit, pixels[1] * unit };
    }

    @Override
    public double zoom() {
        return EditorCamera.zoom();
    }

    @Override
    public void setZoom(double halfHeight) {
        EditorCamera.setZoom(halfHeight);
    }

    /**
     * Works from the display as it is now, not from the camera, so it may be asked as
     * soon as the editor opens, before the camera arrives. The current zoom if the
     * display is not there.
     */
    @Override
    public double fitZoom(int left, int right, int top, int bottom, int width, int height) {
        DisplayGlass.Frame now = frame.get();
        if (now == null)
            return EditorCamera.zoom();
        // The yaw does not matter here: whatever it is, the roll puts the viewport upright
        EditorCamera.View view = EditorCamera.View.of(now.pose(), now.origin().x, now.origin().y, now.origin().z,
                now.viewport().widthPixels(), now.viewport().heightPixels(), 0);
        return view.fitHalfHeight(left, right, top, bottom, width, height, FIT_MARGIN, EditorCamera.windowAspect());
    }
}
