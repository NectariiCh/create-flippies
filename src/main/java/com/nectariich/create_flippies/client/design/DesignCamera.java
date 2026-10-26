package com.nectariich.create_flippies.client.design;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The camera move the Design Workbench makes while its screen is open: out of the
 * player's eyes to the cell above the bench, halfway up its edge on the player's side
 * and to their right, looking straight at the middle of the bench top where the
 * preview stands.
 *
 * The screen's controls sit on the left, so the picture is shifted rather than the
 * camera turned: the projection is moved sideways, as a shift lens moves the image on
 * the film, putting the middle of the view three quarters of the way across the
 * screen while the camera still points straight at the bench.
 *
 * The flight is eased both ways and driven by wall-clock time rather than ticks, so it
 * is as smooth as the frame rate. Closing the screen part way in flies back from
 * wherever the camera had got to.
 *
 * <h2>Top view</h2>
 * For placing text the camera has a second pose, {@link #setTop(boolean) the top
 * view}: straight above the bench top looking down, with an orthographic projection,
 * so the plate is drawn flat and undistorted and a point on the screen is a point on
 * the plate. Going there the camera first flies up, then the projection blends from
 * perspective to orthographic; coming back does the same in reverse. The top view is
 * oriented so that authored +X is screen right and authored +Z is screen down, which
 * for the preview's unturned mount means world +X right and world +Z down: a yaw of
 * 180 degrees (facing north) pitched 90 degrees down. It can be zoomed about the
 * cursor and panned; both are held in a {@link TopView}, which also does every
 * conversion between the screen and the plate, exactly, from the same numbers the
 * projection is built from.
 *
 * <h2>Editor mode</h2>
 * The display editor takes the top view over for a while ({@link #setEditor}): the
 * camera goes to the top view over the middle of the bench, where the preview lays
 * the model glass up, and the picture's sideways shift eases away so the display is
 * centred on the whole screen. It is zoomed only, never panned, with a framing of its
 * own; the workbench's own framing, top view or not, is kept aside and eased back to
 * when the editor leaves. While the editor holds the camera the workbench may still
 * ask for the top view or the bench view: that is remembered and done on leaving.
 */
public final class DesignCamera {

    private static final float DURATION_MILLIS = 650;

    /** How long the move between the bench view and the top view takes, both ways. */
    private static final float TOP_DURATION_MILLIS = 900;

    /**
     * Where the middle of the view is moved to across the screen, from -1 at the left
     * edge to 1 at the right: three quarters of the way across.
     */
    private static final float SCREEN_X = 0.5f;

    /**
     * How high above the plate the top view's camera stands, in blocks: above the
     * tallest front a design may have (16 px, one block), and low enough that a
     * ceiling two blocks above the bench top is still above it.
     */
    public static final double TOP_HEIGHT = 1.5;

    /**
     * The top view's depth range, in blocks in front of the camera: from just in
     * front of it to well past the floor under the bench.
     */
    private static final float TOP_NEAR = 0.05f, TOP_FAR = 8;

    /**
     * The field of view the world is culled with while the projection is (partly)
     * orthographic. An orthographic box is not a frustum, and vanilla's culling
     * setup steps the camera back until a whole 16-block cube fits, which an
     * orthographic box a block wide never allows; so culling is given a perspective
     * wide enough to hold everything the top view can show instead.
     */
    private static final float CULL_FOV_DEGREES = 160;

    public record Pose(Vec3 position, float yaw, float pitch) {}

    private static Vec3 target;
    private static float targetYaw, targetPitch;
    private static boolean entering;
    private static long startMillis;
    private static float startProgress;

    /** The lower corner of the cell above the bench: the plate's origin, authored (0, 0, 0). */
    private static Vec3 plateOrigin;

    private static boolean top;
    private static long topStartMillis;
    private static float topStartProgress;
    private static TopView view = TopView.DEFAULT;

    /**
     * Editor mode: whether it is being moved to or held, how far along that move is
     * (eased by {@link #editorProgress}), the editor's own framing, and whether the
     * workbench last asked for the top view, which is what leaving goes back to.
     */
    private static boolean editor;
    private static long editorStartMillis;
    private static float editorStartProgress;
    private static TopView editorView = TopView.DEFAULT;
    private static boolean benchTop;

    private DesignCamera() {}

    /** Starts the flight in. {@code facing} is the way the player faces the bench. */
    public static void enter(BlockPos bench, Direction facing) {
        Vec3 look = Vec3.atBottomCenterOf(bench.above());
        Direction toPlayer = facing.getOpposite();
        Direction right = facing.getClockWise();
        target = look.add(toPlayer.getStepX() * 0.5 + right.getStepX() * 0.5, 0.5,
                toPlayer.getStepZ() * 0.5 + right.getStepZ() * 0.5);
        Vec3 d = look.subtract(target);
        targetYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        targetPitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        Vec3 origin = Vec3.atLowerCornerOf(bench.above());
        if (!origin.equals(plateOrigin))
            view = TopView.DEFAULT;
        plateOrigin = origin;
        startProgress = progress();
        startMillis = Util.getMillis();
        entering = true;
    }

    /** Starts the flight back to the player's eyes, leaving the top view (and the editor) on the way. */
    public static void exit() {
        setEditor(false);
        setTop(false);
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
        target = null;
        top = false;
        topStartProgress = 0;
        view = TopView.DEFAULT;
        editor = false;
        editorStartProgress = 0;
        editorView = TopView.DEFAULT;
        benchTop = false;
    }

    /**
     * Starts the move to the top view, or back to the bench view. Asking for the
     * view already being moved to does nothing, so the screen may call this freely.
     * While the editor holds the camera this is only remembered, for when it leaves.
     */
    public static void setTop(boolean top) {
        benchTop = top;
        if (!editor)
            moveTop(top);
    }

    private static void moveTop(boolean top) {
        if (top == DesignCamera.top)
            return;
        topStartProgress = topProgress();
        topStartMillis = Util.getMillis();
        DesignCamera.top = top;
    }

    /** Whether the top view is the one being moved to, or held. */
    public static boolean isTop() {
        return top;
    }

    /**
     * Whether the top view is fully in place: flown to and wholly orthographic, so
     * that {@link #screenToPlate} holds. Clicks on the plate are only taken then.
     */
    public static boolean topReady() {
        return top && topProgress() >= 1 && progress() >= 1 && (!editor || editorProgress() >= 1);
    }

    // ------------------------------------------------------------ editor mode

    /**
     * Starts the move into editor mode, or out of it. In: to the top view, centred
     * over the middle of the bench at the zoom the camera had, with the shift easing
     * to nothing. Out: back to the workbench's own framing and shift, and to the bench
     * view if that is what the workbench last asked for. Asking for the mode already
     * being moved to does nothing.
     */
    public static void setEditor(boolean on) {
        if (on == editor)
            return;
        editorStartProgress = editorProgress();
        editorStartMillis = Util.getMillis();
        editor = on;
        if (on) {
            // From wherever the camera is now, so the move in is smooth however far
            // back out a previous one had got
            editorView = new TopView(0.5, 0.5, framing().halfHeight());
            moveTop(true);
        } else {
            moveTop(benchTop);
        }
    }

    /** Whether editor mode is being moved to, or held. */
    public static boolean isEditor() {
        return editor;
    }

    /** The editor's zoom: its view's half height in blocks. */
    public static double editorZoom() {
        return editorView.halfHeight();
    }

    /** Sets the editor's zoom, clamped as every top view's is; the view stays over the middle of the bench. */
    public static void editorZoom(double halfHeight) {
        editorView = new TopView(0.5, 0.5, halfHeight);
    }

    /** How far along the move into editor mode is, from 0 (the workbench's own) to 1. */
    private static float editorProgress() {
        float step = (Util.getMillis() - editorStartMillis) / TOP_DURATION_MILLIS;
        return Mth.clamp(editor ? editorStartProgress + step : editorStartProgress - step, 0, 1);
    }

    /**
     * How far into editor mode the camera is, eased, 0 to 1: how far the shift has
     * gone and the framing has moved to the editor's. The preview lays the model flat
     * by the same amount, so the two arrive together.
     */
    static float editorBlend() {
        return smooth(editorProgress());
    }

    /**
     * The top view the camera uses now: the workbench's, the editor's, or on the way
     * between them. Eased this way rather than switched, so going in and out of the
     * editor from a panned or zoomed top view never jumps.
     */
    private static TopView framing() {
        float t = editorBlend();
        if (t <= 0)
            return view;
        if (t >= 1)
            return editorView;
        return new TopView(Mth.lerp(t, view.centreX(), editorView.centreX()),
                Mth.lerp(t, view.centreZ(), editorView.centreZ()),
                Mth.lerp(t, view.halfHeight(), editorView.halfHeight()));
    }

    /** How far along the flight is, from the player's eyes at 0 to the bench view at 1. */
    private static float progress() {
        if (target == null)
            return 0;
        float step = (Util.getMillis() - startMillis) / DURATION_MILLIS;
        return Mth.clamp(entering ? startProgress + step : startProgress - step, 0, 1);
    }

    /** How far along the move to the top view is, from the bench view at 0 to the top view at 1. */
    private static float topProgress() {
        float step = (Util.getMillis() - topStartMillis) / TOP_DURATION_MILLIS;
        return Mth.clamp(top ? topStartProgress + step : topStartProgress - step, 0, 1);
    }

    /** Starts and ends at rest. */
    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    /**
     * How far the camera has flown toward the top view's pose. The flight takes the
     * first 60% of the move, so the projection only starts to flatten once the
     * camera is nearly overhead.
     */
    private static float topFlight() {
        return smooth(Mth.clamp(topProgress() / 0.6f, 0, 1));
    }

    /**
     * How orthographic the projection is, 0 to 1: the last 45% of the move, and only
     * as far as the camera has come in from the player's eyes, so flying back out
     * never draws the world flat from their head.
     */
    private static float orthoBlend() {
        if (target == null)
            return 0;
        return smooth(Mth.clamp((topProgress() - 0.55f) / 0.45f, 0, 1)) * smooth(progress());
    }

    /** Where the top view's camera stands. */
    private static Vec3 topPosition() {
        TopView framing = framing();
        return plateOrigin.add(framing.centreX(), TOP_HEIGHT, framing.centreZ());
    }

    /**
     * The pose the camera should have, given the one vanilla gave it, or null when
     * the workbench is not holding the camera.
     */
    public static Pose pose(Vec3 eyes, float yaw, float pitch) {
        float progress = progress();
        if (progress <= 0) {
            if (!entering)
                target = null;
            return null;
        }
        // The bench view the camera is heading for: the angled view, or the top view
        // part or all of the way
        Vec3 benchPosition = target;
        float benchYaw = targetYaw, benchPitch = targetPitch;
        float flight = topFlight();
        if (flight > 0 && plateOrigin != null) {
            benchPosition = target.lerp(topPosition(), flight);
            benchYaw = Mth.rotLerp(flight, targetYaw, TopView.YAW);
            benchPitch = Mth.lerp(flight, targetPitch, TopView.PITCH);
        }
        float t = smooth(progress);
        return new Pose(eyes.lerp(benchPosition, t), Mth.rotLerp(t, yaw, benchYaw), Mth.lerp(t, pitch, benchPitch));
    }

    /**
     * Whether the player's own body should be drawn: it is while the camera is
     * beside the bench, as in third person, but not from the top view, where the
     * player's head would stand between the camera and the plate.
     */
    public static boolean showPlayer() {
        return orthoBlend() <= 0;
    }

    /**
     * How far to shift the picture right, in normalised screen units (2 is the whole
     * width), eased in and out with the flight, and eased away in editor mode.
     */
    public static float screenShift() {
        float progress = progress();
        if (target == null || progress <= 0)
            return 0;
        return SCREEN_X * smooth(progress) * (1 - editorBlend());
    }

    public static boolean active() {
        return target != null && progress() > 0;
    }

    // ------------------------------------------------------------ projection

    /** The window's width over its height: what the top view's projection is built for. */
    public static double windowAspect() {
        var window = Minecraft.getInstance().getWindow();
        return window.getHeight() <= 0 ? 1 : (double) window.getWidth() / window.getHeight();
    }

    /**
     * The world's projection while the workbench holds the camera, given vanilla's:
     * blended toward the top view's orthographic one as far as the move has got, then
     * shifted sideways. Returns the matrix it is given, changed in place.
     */
    public static Matrix4f projection(Matrix4f vanilla) {
        float ortho = orthoBlend();
        if (ortho > 0) {
            Matrix4f flat = framing().projection(windowAspect(), 0, TOP_NEAR, TOP_FAR);
            vanilla.lerp(flat, ortho);
        }
        float shift = screenShift();
        if (shift != 0)
            // Clip-space x gains shift * w, which after the divide is a move of shift in
            // normalised screen units, the same at every depth
            vanilla.set(new Matrix4f().translation(shift, 0, 0).mul(vanilla));
        return vanilla;
    }

    /**
     * The projection the world is culled with, given the one it would be culled with
     * otherwise: unchanged unless the top view's orthographic projection is coming
     * in, when it is a very wide perspective instead (see {@link #CULL_FOV_DEGREES}).
     */
    public static Matrix4f cullProjection(Matrix4f projection, float depthFar) {
        if (orthoBlend() <= 0)
            return projection;
        return new Matrix4f().setPerspective((float) Math.toRadians(CULL_FOV_DEGREES), (float) windowAspect(), 0.05f,
                depthFar);
    }

    // -------------------------------------------------------------- top view

    /**
     * The point of the plate under a point of the screen in the top view, in
     * authored pixels {x, z}, or null while the top view is not fully in place.
     * Mouse coordinates are the screen's, GUI-scaled, with the screen's size.
     */
    public static double[] screenToPlate(double mouseX, double mouseY, int width, int height) {
        if (!topReady())
            return null;
        // The shift the picture is drawn with now: the whole of it at the bench, none in
        // the editor (topReady waits for it to settle there), and on the way back out
        // of the editor whatever it has got to
        return framing().toPlate(TopView.ndcX(mouseX, width), TopView.ndcY(mouseY, height), windowAspect(),
                screenShift());
    }

    /**
     * The point of the screen, GUI-scaled, over a point of the plate in authored
     * pixels, or null while the top view is not fully in place.
     */
    public static double[] plateToScreen(double x, double z, int width, int height) {
        if (!topReady())
            return null;
        double[] ndc = framing().toNdc(x, z, windowAspect(), screenShift());
        return new double[] { TopView.guiX(ndc[0], width), TopView.guiY(ndc[1], height) };
    }

    /**
     * Zooms the top view by {@code factor} (below 1 zooms in) keeping the point
     * under the cursor where it is.
     */
    public static void zoomAbout(double mouseX, double mouseY, int width, int height, double factor) {
        view = view.zoomAbout(TopView.ndcX(mouseX, width), TopView.ndcY(mouseY, height), factor, windowAspect(),
                screenShift());
    }

    /** Moves the top view so the plate follows a drag of the cursor by (dx, dy), GUI-scaled. */
    public static void pan(double dx, double dy, int width, int height) {
        view = view.pan(2 * dx / width, -2 * dy / height, windowAspect());
    }

    /** Puts the top view back over the middle of the bench at its first zoom. */
    public static void resetView() {
        view = TopView.DEFAULT;
    }

    /**
     * The top view's framing and every conversion it needs, as plain numbers so it
     * can be checked without a game.
     *
     * Units: the centre is where the camera stands over the plate, in blocks from the
     * plate's origin (the lower corner of the cell above the bench), so (0.5, 0.5) is
     * the middle of the bench top. The half height is how many blocks the screen
     * shows from its middle to its top edge. Normalised device coordinates (NDC) run
     * from -1 to 1 across the screen, +y up, and {@code shift} is the sideways move
     * the workbench gives the picture, in the same units.
     *
     * The camera looks straight down with yaw {@link #YAW}: view x is world +X and
     * view y (screen up) is world -Z. So a screen point (ndcX, ndcY) is over world
     * (centreX + (ndcX - shift) * halfHeight * aspect, centreZ - ndcY * halfHeight),
     * which is what the projection matrix inverts.
     */
    public record TopView(double centreX, double centreZ, double halfHeight) {

        /** Facing north, so screen up is world -Z and screen right is world +X. */
        public static final float YAW = 180;

        /** Straight down. */
        public static final float PITCH = 90;

        public static final double DEFAULT_HALF_HEIGHT = 0.6;
        public static final double MIN_HALF_HEIGHT = 0.08, MAX_HALF_HEIGHT = 3;

        /** How far the view may be panned from the middle of the bench top, in blocks. */
        public static final double MAX_PAN = 2;

        public static final TopView DEFAULT = new TopView(0.5, 0.5, DEFAULT_HALF_HEIGHT);

        public TopView {
            halfHeight = clamp(halfHeight, MIN_HALF_HEIGHT, MAX_HALF_HEIGHT);
            centreX = clamp(centreX, 0.5 - MAX_PAN, 0.5 + MAX_PAN);
            centreZ = clamp(centreZ, 0.5 - MAX_PAN, 0.5 + MAX_PAN);
        }

        /** Kept free of game classes, so the view can be tested on its own. */
        private static double clamp(double value, double min, double max) {
            return value < min ? min : Math.min(value, max);
        }

        /**
         * The orthographic projection: the view's box, {@code halfHeight} up and down
         * and {@code halfHeight * aspect} across, from {@code near} to {@code far} in
         * front of the camera, then shifted sideways by {@code shift} in NDC.
         */
        public Matrix4f projection(double aspect, float shift, float near, float far) {
            float halfWidth = (float) (halfHeight * aspect);
            Matrix4f ortho = new Matrix4f().setOrtho(-halfWidth, halfWidth, (float) -halfHeight, (float) halfHeight,
                    near, far);
            return shift == 0 ? ortho : new Matrix4f().translation(shift, 0, 0).mul(ortho);
        }

        /** The plate point, in authored pixels {x, z}, drawn at a point of the screen in NDC. */
        public double[] toPlate(double ndcX, double ndcY, double aspect, double shift) {
            double worldX = centreX + (ndcX - shift) * halfHeight * aspect;
            double worldZ = centreZ - ndcY * halfHeight;
            return new double[] { worldX * 16, worldZ * 16 };
        }

        /** The point of the screen, in NDC {x, y}, a plate point in authored pixels is drawn at. */
        public double[] toNdc(double x, double z, double aspect, double shift) {
            double ndcX = (x / 16 - centreX) / (halfHeight * aspect) + shift;
            double ndcY = -(z / 16 - centreZ) / halfHeight;
            return new double[] { ndcX, ndcY };
        }

        /** The view zoomed by {@code factor} with the plate point at (ndcX, ndcY) kept there. */
        public TopView zoomAbout(double ndcX, double ndcY, double factor, double aspect, double shift) {
            double next = clamp(halfHeight * factor, MIN_HALF_HEIGHT, MAX_HALF_HEIGHT);
            // The point under the cursor is centre + offset * halfHeight; keeping it
            // fixed while the half height changes moves the centre by the difference
            double dx = (ndcX - shift) * aspect, dz = -ndcY;
            return new TopView(centreX + dx * (halfHeight - next), centreZ + dz * (halfHeight - next), next);
        }

        /**
         * The view moved so the plate follows the cursor by (dNdcX, dNdcY), in NDC:
         * the camera moves the other way.
         */
        public TopView pan(double dNdcX, double dNdcY, double aspect) {
            return new TopView(centreX - dNdcX * halfHeight * aspect, centreZ + dNdcY * halfHeight, halfHeight);
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
    }
}
