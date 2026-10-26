package com.nectariich.create_flippies.client.display.editor;

import java.util.function.Supplier;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * The camera move the display editor makes over a display in the world: out of the
 * player's eyes to a point just in front of the glass, looking straight into it,
 * rolled so the display's viewport reads upright (its +x screen right, its +y screen
 * down) whichever way the block faces or is turned, and then flattened into an
 * orthographic view so a point on the screen is a point on the glass.
 *
 * Modelled on {@link com.nectariich.create_flippies.client.design.DesignCamera}: the
 * flight is eased both ways and driven by wall-clock time, closing part way in flies
 * back from wherever the camera had got to, and while the projection is (partly)
 * orthographic the world is culled with a very wide perspective instead. Unlike the
 * workbench's camera there is no shift lens and no panning: the display is centred on
 * the whole screen, and zoom is the orthographic half height in blocks.
 *
 * The roll cannot go through {@code Camera.setRotation}, which only takes yaw and
 * pitch; it is applied through Forge's camera angles event
 * ({@link EditorCameraEvents}), which is where Forge puts roll into the view:
 * {@code Rz(roll) * Rx(pitch) * Ry(yaw + 180)}.
 *
 * The display is asked where it is once a frame (the camera mixin calls {@link #pose}
 * once per frame), because the Rotate button can turn the block under the editor and
 * a sync may replace the host's control objects. The rest of the frame (projection,
 * roll, the editor's mapping) works from that one answer.
 */
public final class EditorCamera {

    private static final float DURATION_MILLIS = 900;

    /** How far in front of the glass the camera stands, in blocks. */
    public static final double DISTANCE = 0.5;

    /**
     * How far in front of the glass the near plane is, in blocks: one pixel. Anything
     * standing nearer the camera than that, a block, an entity, the player, is clipped
     * away and cannot hide the display.
     */
    public static final double NEAR_GAP = 1 / 16.0;

    /** How far behind the glass the orthographic view still draws, in blocks. */
    private static final double DEPTH_BEYOND = 64;

    /** As {@code DesignCamera}'s: a perspective wide enough to hold the whole orthographic view for culling. */
    private static final float CULL_FOV_DEGREES = 160;

    /** The share of the move the flight takes; the projection flattens over the end of it. */
    private static final float FLIGHT_SHARE = 0.6f;

    /** Where in the move the projection starts to flatten, and over how much of it. */
    private static final float ORTHO_START = 0.55f, ORTHO_SHARE = 0.45f;

    public static final double MIN_ZOOM = 0.01, MAX_ZOOM = 16;

    private static final double DEFAULT_ZOOM = 0.5;

    public record Pose(Vec3 position, float yaw, float pitch) {}

    private static Supplier<DisplayGlass.Frame> source;
    private static DisplayGlass.Frame frame;
    private static View view;
    /** The yaw kept when the glass faces straight up or down, where any yaw would do: the player's own. */
    private static float preferredYaw;
    private static double zoom = DEFAULT_ZOOM;
    private static boolean entering;
    private static long startMillis;
    private static float startProgress;

    private EditorCamera() {}

    /**
     * Starts the flight in toward the glass {@code source} names, which is asked again
     * every frame (it may return null for a frame, when the last answer is kept).
     * False, and nothing done, when the display is not there now.
     */
    public static boolean enter(Supplier<DisplayGlass.Frame> source) {
        DisplayGlass.Frame first = source.get();
        if (first == null)
            return false;
        Minecraft minecraft = Minecraft.getInstance();
        float yaw = minecraft.player != null ? minecraft.player.getYRot() : 0;
        if (EditorCamera.source == null || !first.equals(frame))
            preferredYaw = yaw;
        EditorCamera.source = source;
        frame = null;
        retarget(first);
        startProgress = progress();
        startMillis = Util.getMillis();
        entering = true;
        return true;
    }

    /** Starts the flight back to the player's eyes, from wherever the camera has got to. */
    public static void exit() {
        if (!entering)
            return;
        startProgress = progress();
        startMillis = Util.getMillis();
        entering = false;
    }

    /** Drops the move at once, for a level going away under it. */
    public static void reset() {
        entering = false;
        startProgress = 0;
        source = null;
        frame = null;
        view = null;
    }

    /** Whether the camera is anywhere but the player's eyes. */
    public static boolean active() {
        return view != null && progress() > 0;
    }

    /** Whether the camera is in place in front of the glass and the view wholly orthographic. */
    public static boolean ready() {
        return entering && view != null && progress() >= 1;
    }

    /** The view the camera is flying to or holding, or null when there is none. */
    public static View view() {
        return view;
    }

    public static double zoom() {
        return zoom;
    }

    /** Sets the orthographic half height, in blocks, clamped to {@link #MIN_ZOOM} and {@link #MAX_ZOOM}. */
    public static void setZoom(double halfHeight) {
        if (Double.isFinite(halfHeight))
            zoom = Mth.clamp(halfHeight, MIN_ZOOM, MAX_ZOOM);
    }

    public static double windowAspect() {
        var window = Minecraft.getInstance().getWindow();
        return window.getHeight() <= 0 ? 1 : (double) window.getWidth() / window.getHeight();
    }

    /** How far along the move is, from the player's eyes at 0 to in place and flat at 1. */
    private static float progress() {
        if (view == null)
            return 0;
        float step = (Util.getMillis() - startMillis) / DURATION_MILLIS;
        return Mth.clamp(entering ? startProgress + step : startProgress - step, 0, 1);
    }

    /** Starts and ends at rest. */
    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    /** How far the camera has flown from the player's eyes toward the glass. */
    private static float flight() {
        return smooth(Mth.clamp(progress() / FLIGHT_SHARE, 0, 1));
    }

    /**
     * How orthographic the projection is, 0 to 1: over the end of the move, once the
     * camera is nearly in front of the glass, so the world is never drawn flat from
     * anywhere else.
     */
    private static float orthoBlend() {
        return smooth(Mth.clamp((progress() - ORTHO_START) / ORTHO_SHARE, 0, 1));
    }

    private static void retarget(DisplayGlass.Frame next) {
        if (next == null || next.equals(frame))
            return;
        frame = next;
        Vec3 origin = next.origin();
        view = View.of(next.pose(), origin.x, origin.y, origin.z, next.viewport().widthPixels(),
                next.viewport().heightPixels(), preferredYaw);
    }

    /**
     * The pose the camera should have, given the one vanilla gave it, or null when the
     * editor is not holding the camera. Called once a frame, before anything else here
     * is asked; it asks the display where it is.
     */
    public static Pose pose(Vec3 eyes, float yaw, float pitch) {
        if (view != null && source != null)
            retarget(source.get());
        float progress = progress();
        if (progress <= 0) {
            if (!entering)
                reset();
            return null;
        }
        float t = flight();
        Vec3 eye = new Vec3(view.eye()[0], view.eye()[1], view.eye()[2]);
        return new Pose(eyes.lerp(eye, t), Mth.rotLerp(t, yaw, view.yaw()), Mth.lerp(t, pitch, view.pitch()));
    }

    /** The roll to draw with, given the one the frame would otherwise have, blended with the flight. */
    public static float roll(float roll) {
        if (!active())
            return roll;
        return Mth.rotLerp(flight(), roll, view.roll());
    }

    /**
     * The world's projection while the editor holds the camera, given vanilla's:
     * blended toward the orthographic view as far as the move has got. Returns the
     * matrix it is given, changed in place.
     */
    public static Matrix4f projection(Matrix4f vanilla) {
        float ortho = orthoBlend();
        if (ortho > 0 && view != null)
            vanilla.lerp(View.ortho(zoom, windowAspect()), ortho);
        return vanilla;
    }

    /**
     * The projection the world is culled with: unchanged unless the orthographic view
     * is coming in, when it is a very wide perspective instead, since vanilla's
     * culling setup cannot work with an orthographic box (see {@code DesignCamera}).
     */
    public static Matrix4f cullProjection(Matrix4f projection, float depthFar) {
        if (orthoBlend() <= 0)
            return projection;
        return new Matrix4f().setPerspective((float) Math.toRadians(CULL_FOV_DEGREES), (float) windowAspect(), 0.05f,
                depthFar);
    }

    // ------------------------------------------------------------------ view

    /**
     * Turns that face the camera along {@code forward} with {@code right} as screen
     * right, in Minecraft's conventions, as {yaw, pitch, roll} in degrees.
     *
     * Yaw and pitch are what {@code Camera.setRotation} takes: its forward is
     * (-cos p sin y, -sin p, cos p cos y), so pitch is asin(-f.y) and yaw is
     * atan2(-f.x, f.z). Looking straight up or down any yaw faces the same way, so
     * {@code preferredYaw} is kept there and the roll makes up the difference. The
     * roll is what Forge turns the view by last ({@code Rz(roll) * Rx(pitch) *
     * Ry(yaw + 180)}): yaw and pitch alone take {@code right} to some direction at an
     * angle alpha in the screen's plane, and turning that back by -alpha puts it on +x.
     * {@code forward} and {@code right} need not be unit length but must not be parallel.
     */
    public static float[] angles(double fx, double fy, double fz, double rx, double ry, double rz,
            float preferredYaw) {
        Vector3d forward = new Vector3d(fx, fy, fz).normalize();
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -forward.y))));
        double horizontal = Math.sqrt(forward.x * forward.x + forward.z * forward.z);
        double yaw = horizontal < 1e-6 ? preferredYaw : Math.toDegrees(Math.atan2(-forward.x, forward.z));
        Vector3d q = new Matrix3d().rotationX(Math.toRadians(pitch)).rotateY(Math.toRadians(yaw + 180))
                .transform(new Vector3d(rx, ry, rz));
        double roll = -Math.toDegrees(Math.atan2(q.y, q.x));
        return new float[] { (float) yaw, (float) pitch, (float) roll };
    }

    /**
     * The editor's view of one display's glass, and the exact mapping between the
     * glass and the screen that comes with it, as plain numbers so it can be checked
     * without a game.
     *
     * {@code eye} is where the camera stands, in world blocks, and yaw, pitch and roll
     * how it is turned (see {@link EditorCamera#angles}). The mapping is from the
     * viewport in pixels to the view's plane in blocks: a viewport point (x, y) lies
     * {@code a * dx + b * dy} right of the middle of the screen and {@code c * dx +
     * d * dy} above it, where (dx, dy) is its offset from the viewport's centre
     * ({@code centreX}, {@code centreY}). With the orthographic half height and the
     * window's aspect that is a point in normalised device coordinates (NDC, -1 to 1
     * across the screen, +y up).
     */
    public record View(double[] eye, float yaw, float pitch, float roll, double a, double b, double c, double d,
            double centreX, double centreY) {

        /**
         * The view of the glass whose frame {@code pose} takes viewport pixels to blocks
         * from {@code (originX, originY, originZ)}, its third axis out of the glass; the
         * viewport is {@code width} by {@code height} pixels.
         */
        public static View of(Matrix4f pose, double originX, double originY, double originZ, double width,
                double height, float preferredYaw) {
            Vector3d x = column(pose, 0), y = column(pose, 1), z = column(pose, 2), t = column(pose, 3);
            Vector3d normal = new Vector3d(z).normalize();
            Vector3d forward = new Vector3d(normal).negate();
            // Screen right is the viewport's +x laid flat on the glass, should the frame
            // ever lean; and the viewport's +y if +x is degenerate
            Vector3d right = new Vector3d(x).fma(-x.dot(forward), forward);
            if (right.lengthSquared() < 1e-12)
                right = new Vector3d(forward).cross(y);
            right.normalize();
            float[] angles = angles(forward.x, forward.y, forward.z, right.x, right.y, right.z, preferredYaw);
            // The view's +y, screen up, is +z cross +x, with +z back toward the camera
            Vector3d up = new Vector3d(right).cross(forward);
            double centreX = width / 2, centreY = height / 2;
            Vector3d centre = new Vector3d(t).fma(centreX, x).fma(centreY, y).add(originX, originY, originZ);
            Vector3d eye = centre.fma(DISTANCE, normal);
            return new View(new double[] { eye.x, eye.y, eye.z }, angles[0], angles[1], angles[2], x.dot(right),
                    y.dot(right), x.dot(up), y.dot(up), centreX, centreY);
        }

        private static Vector3d column(Matrix4f pose, int column) {
            Vector3f c = pose.getColumn(column, new Vector3f());
            return new Vector3d(c.x, c.y, c.z);
        }

        /**
         * The orthographic projection: {@code halfHeight} up and down and
         * {@code halfHeight * aspect} across, from {@link #NEAR_GAP} in front of the glass
         * to well behind it.
         */
        public static Matrix4f ortho(double halfHeight, double aspect) {
            float halfWidth = (float) (halfHeight * aspect);
            return new Matrix4f().setOrtho(-halfWidth, halfWidth, (float) -halfHeight, (float) halfHeight,
                    (float) (DISTANCE - NEAR_GAP), (float) (DISTANCE + DEPTH_BEYOND));
        }

        /** Where a viewport point, in pixels, is drawn, in NDC {x, y}. */
        public double[] toNdc(double x, double y, double halfHeight, double aspect) {
            double dx = x - centreX, dy = y - centreY;
            return new double[] { (a * dx + b * dy) / (halfHeight * aspect), (c * dx + d * dy) / halfHeight };
        }

        /**
         * The viewport point, in pixels, drawn at a point of the screen in NDC, or null
         * if the glass is seen edge on (it never is, since the camera faces it).
         */
        public double[] fromNdc(double ndcX, double ndcY, double halfHeight, double aspect) {
            double vx = ndcX * halfHeight * aspect, vy = ndcY * halfHeight;
            double det = a * d - b * c;
            if (Math.abs(det) < 1e-12)
                return null;
            return new double[] { centreX + (d * vx - b * vy) / det, centreY + (a * vy - c * vx) / det };
        }

        /**
         * The farthest the viewport's corners reach from the middle of the screen at a
         * half height of one block, in NDC {x, y} (x already divided by {@code aspect}).
         */
        public double[] reach(double aspect) {
            double rx = 0, ry = 0;
            for (double[] corner : new double[][] { { 0, 0 }, { 2 * centreX, 0 }, { 0, 2 * centreY },
                    { 2 * centreX, 2 * centreY } }) {
                double[] ndc = toNdc(corner[0], corner[1], 1, aspect);
                rx = Math.max(rx, Math.abs(ndc[0]));
                ry = Math.max(ry, Math.abs(ndc[1]));
            }
            return new double[] { rx, ry };
        }

        /** A GUI-scaled x, from 0 at the left edge to {@code width} at the right, in NDC. */
        public static double ndcX(double guiX, int width) {
            return 2 * guiX / width - 1;
        }

        /** A GUI-scaled y, from 0 at the top edge to {@code height} at the bottom, in NDC. */
        public static double ndcY(double guiY, int height) {
            return 1 - 2 * guiY / height;
        }

        public static double guiX(double ndcX, int width) {
            return (ndcX + 1) * width / 2;
        }

        public static double guiY(double ndcY, int height) {
            return (1 - ndcY) * height / 2;
        }

        /**
         * The half height at which the whole viewport, centred on the middle of a screen
         * {@code width} by {@code height}, fits between {@code left} and {@code right}
         * and {@code top} and {@code bottom} with {@code margin} to spare. The view is
         * centred on the screen, so on each axis it is the nearer edge that limits it.
         */
        public double fitHalfHeight(int left, int right, int top, int bottom, int width, int height,
                double margin, double aspect) {
            double[] reach = reach(aspect);
            double roomX = Math.min(width / 2.0 - left, right - width / 2.0) - margin;
            double roomY = Math.min(height / 2.0 - top, bottom - height / 2.0) - margin;
            // At half height h a reach r (in NDC at h = 1) spans r / h * size / 2 GUI pixels
            double needX = reach[0] * width / 2, needY = reach[1] * height / 2;
            double fit = 0;
            fit = Math.max(fit, roomX > 0 ? needX / roomX : MAX_ZOOM);
            fit = Math.max(fit, roomY > 0 ? needY / roomY : MAX_ZOOM);
            return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, fit));
        }
    }
}
