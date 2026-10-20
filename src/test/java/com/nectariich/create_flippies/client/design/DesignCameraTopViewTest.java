package com.nectariich.create_flippies.client.design;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import com.nectariich.create_flippies.client.design.DesignCamera.TopView;

/**
 * The workbench's top view, checked as numbers: the orthographic projection the
 * world is drawn with and the screen-to-plate mapping clicks are read with must agree
 * exactly, including the sideways shift, zoom and pan, and the yaw must put authored
 * +X to the right and +Z down.
 *
 * The world side is built the way vanilla builds it: the view turns the world by
 * {@code Rx(pitch) * Ry(yaw + 180)} about the camera (GameRenderer.renderLevel), then
 * the projection is applied. The plate's origin is put at the world origin, so world
 * blocks times 16 are authored pixels.
 */
class DesignCameraTopViewTest {

    private static final double EPSILON = 1e-4;

    private static final double ASPECT = 1280.0 / 720.0;
    private static final float SHIFT = 0.5f;

    /** Where a world point (in blocks, plate origin at 0) is drawn, in NDC {x, y}. */
    private static double[] project(TopView view, double x, double y, double z) {
        Matrix4f projection = view.projection(ASPECT, SHIFT, 0.05f, 8);
        Matrix4f viewMatrix = new Matrix4f().rotationX((float) Math.toRadians(TopView.PITCH))
                .rotateY((float) Math.toRadians(TopView.YAW + 180));
        double camY = DesignCamera.TOP_HEIGHT;
        Vector4f point = new Vector4f((float) (x - view.centreX()), (float) (y - camY), (float) (z - view.centreZ()), 1);
        viewMatrix.transform(point);
        projection.transform(point);
        return new double[] { point.x / point.w, point.y / point.w, point.z / point.w };
    }

    @Test
    void benchTopCentreSitsThreeQuartersAcross() {
        double[] ndc = project(TopView.DEFAULT, 0.5, 0, 0.5);
        assertEquals(SHIFT, ndc[0], EPSILON);
        assertEquals(0, ndc[1], EPSILON);
        // Three quarters of the way across a GUI 427 wide, half way down one 240 high
        assertEquals(427 * 0.75, TopView.guiX(ndc[0], 427), EPSILON);
        assertEquals(120, TopView.guiY(ndc[1], 240), EPSILON);
        // In front of the camera, inside the depth range
        assertEquals(true, ndc[2] > -1 && ndc[2] < 1);
    }

    @Test
    void authoredXIsRightAndZIsDown() {
        double[] centre = project(TopView.DEFAULT, 0.5, 0, 0.5);
        double[] east = project(TopView.DEFAULT, 0.75, 0, 0.5);
        double[] south = project(TopView.DEFAULT, 0.5, 0, 0.75);
        assertEquals(true, east[0] > centre[0]);
        assertEquals(centre[1], east[1], EPSILON);
        // NDC y is up, so +Z going down the screen is NDC y falling
        assertEquals(true, south[1] < centre[1]);
        assertEquals(centre[0], south[0], EPSILON);
        // Orthographic: height does not move a point across the screen
        double[] raised = project(TopView.DEFAULT, 0.75, 0.9, 0.75);
        double[] flat = project(TopView.DEFAULT, 0.75, 0, 0.75);
        assertEquals(flat[0], raised[0], EPSILON);
        assertEquals(flat[1], raised[1], EPSILON);
    }

    @Test
    void screenToPlateInvertsTheProjection() {
        TopView[] views = { TopView.DEFAULT, new TopView(0.9, 0.2, 0.31),
                TopView.DEFAULT.zoomAbout(0.7, -0.4, 0.5, ASPECT, SHIFT).pan(0.2, -0.3, ASPECT) };
        double[][] points = { { 0, 0 }, { 8, 8 }, { 3.25, 12.5 }, { -4, 20 }, { 15.9, 0.1 } };
        for (TopView view : views)
            for (double[] point : points) {
                double[] ndc = project(view, point[0] / 16, 0, point[1] / 16);
                double[] plate = view.toPlate(ndc[0], ndc[1], ASPECT, SHIFT);
                assertEquals(point[0], plate[0], EPSILON, view + " x");
                assertEquals(point[1], plate[1], EPSILON, view + " z");
                double[] back = view.toNdc(point[0], point[1], ASPECT, SHIFT);
                assertEquals(ndc[0], back[0], EPSILON);
                assertEquals(ndc[1], back[1], EPSILON);
            }
    }

    @Test
    void guiCoordinatesRoundTrip() {
        int width = 427, height = 240;
        double mouseX = 300.5, mouseY = 77.25;
        double ndcX = TopView.ndcX(mouseX, width), ndcY = TopView.ndcY(mouseY, height);
        assertEquals(mouseX, TopView.guiX(ndcX, width), EPSILON);
        assertEquals(mouseY, TopView.guiY(ndcY, height), EPSILON);
        assertEquals(1, TopView.ndcY(0, height), EPSILON);
        assertEquals(-1, TopView.ndcX(0, width), EPSILON);
    }

    @Test
    void zoomKeepsThePointUnderTheCursor() {
        double ndcX = 0.8, ndcY = -0.35;
        double[] before = TopView.DEFAULT.toPlate(ndcX, ndcY, ASPECT, SHIFT);
        TopView zoomed = TopView.DEFAULT.zoomAbout(ndcX, ndcY, 0.5, ASPECT, SHIFT);
        assertEquals(TopView.DEFAULT_HALF_HEIGHT * 0.5, zoomed.halfHeight(), EPSILON);
        double[] after = zoomed.toPlate(ndcX, ndcY, ASPECT, SHIFT);
        assertEquals(before[0], after[0], EPSILON);
        assertEquals(before[1], after[1], EPSILON);
    }

    @Test
    void panMovesThePlateWithTheCursor() {
        double[] grabbed = TopView.DEFAULT.toPlate(0.6, 0.1, ASPECT, SHIFT);
        TopView panned = TopView.DEFAULT.pan(0.15, -0.2, ASPECT);
        double[] under = panned.toPlate(0.75, -0.1, ASPECT, SHIFT);
        assertEquals(grabbed[0], under[0], EPSILON);
        assertEquals(grabbed[1], under[1], EPSILON);
    }
}
