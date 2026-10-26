package com.nectariich.create_flippies.client.display.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import com.nectariich.create_flippies.client.display.editor.EditorCamera.View;

/**
 * The display editor's camera, checked as numbers: the yaw, pitch and roll must make
 * the view Forge builds ({@code Rz(roll) * Rx(pitch) * Ry(yaw + 180)}) look along the
 * glass's inward normal with the viewport's +x to the right, and the mapping the
 * editor reads clicks with must agree exactly with where the orthographic projection
 * draws each viewport point, for glass facing any way, turned any way, and mirrored.
 *
 * Frames are built as the renderers build them: some turn, then pixels scaled to
 * blocks with +y negated (the viewport counts downward), so the frame's z is out of
 * the glass and the unmirrored frame is left-handed.
 */
class EditorCameraTest {

    private static final double EPSILON = 1e-4;

    private static final double ASPECT = 1920.0 / 1080.0;

    /** The view matrix Forge builds from the camera's angles (GameRenderer.renderLevel). */
    private static Matrix4f viewMatrix(View view) {
        return new Matrix4f().rotationZ((float) Math.toRadians(view.roll()))
                .rotateX((float) Math.toRadians(view.pitch()))
                .rotateY((float) Math.toRadians(view.yaw() + 180));
    }

    private static Matrix4f frame(Quaternionf turn, boolean mirrored, float width, float height) {
        return new Matrix4f().translation(0.3f, 0.6f, 0.2f).rotate(turn).scale(mirrored ? -1 / 16f : 1 / 16f,
                -1 / 16f, 1 / 16f).translate(-width / 2, -height / 2, 0);
    }

    /** Where a viewport point (pixels) of {@code pose}, at block origin {@code o}, is drawn, in NDC {x, y, z}. */
    private static double[] project(Matrix4f pose, double[] o, View view, double halfHeight, double x, double y) {
        Vector3f at = pose.transformPosition((float) x, (float) y, 0, new Vector3f());
        Vector4f point = new Vector4f((float) (o[0] + at.x - view.eye()[0]), (float) (o[1] + at.y - view.eye()[1]),
                (float) (o[2] + at.z - view.eye()[2]), 1);
        viewMatrix(view).transform(point);
        View.ortho(halfHeight, ASPECT).transform(point);
        return new double[] { point.x / point.w, point.y / point.w, point.z / point.w };
    }

    private static void assertVector(double x, double y, double z, Vector3f actual) {
        assertEquals(x, actual.x, EPSILON, "x of " + actual);
        assertEquals(y, actual.y, EPSILON, "y of " + actual);
        assertEquals(z, actual.z, EPSILON, "z of " + actual);
    }

    private static Quaternionf randomTurn(Random random) {
        return new Quaternionf().rotateXYZ(random.nextFloat() * 7, random.nextFloat() * 7, random.nextFloat() * 7);
    }

    @Test
    void anglesLookAlongForwardWithRightToTheRight() {
        Random random = new Random(7);
        float[][] cases = new float[64][];
        // Every axis-aligned facing with every quarter turn, including straight up and down
        Vector3f[] axes = { new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0), new Vector3f(0, 1, 0),
                new Vector3f(0, -1, 0), new Vector3f(0, 0, 1), new Vector3f(0, 0, -1) };
        int n = 0;
        for (Vector3f f : axes)
            for (Vector3f r : axes)
                if (Math.abs(f.dot(r)) < 0.5)
                    cases[n++] = new float[] { f.x, f.y, f.z, r.x, r.y, r.z };
        while (n < cases.length) {
            Quaternionf q = randomTurn(random);
            Vector3f f = q.transform(new Vector3f(0, 0, 1)), r = q.transform(new Vector3f(1, 0, 0));
            cases[n++] = new float[] { f.x, f.y, f.z, r.x, r.y, r.z };
        }
        for (float[] c : cases) {
            float[] angles = EditorCamera.angles(c[0], c[1], c[2], c[3], c[4], c[5], 33);
            Matrix4f view = new Matrix4f().rotationZ((float) Math.toRadians(angles[2]))
                    .rotateX((float) Math.toRadians(angles[1])).rotateY((float) Math.toRadians(angles[0] + 180));
            assertVector(0, 0, -1, view.transformDirection(new Vector3f(c[0], c[1], c[2])));
            assertVector(1, 0, 0, view.transformDirection(new Vector3f(c[3], c[4], c[5])));
            // And Camera.setRotation's own forward agrees: (-cos p sin y, -sin p, cos p cos y)
            double yaw = Math.toRadians(angles[0]), pitch = Math.toRadians(angles[1]);
            assertVector(c[0], c[1], c[2], new Vector3f((float) (-Math.cos(pitch) * Math.sin(yaw)),
                    (float) -Math.sin(pitch), (float) (Math.cos(pitch) * Math.cos(yaw))));
        }
    }

    @Test
    void straightDownKeepsThePreferredYaw() {
        float[] angles = EditorCamera.angles(0, -1, 0, 1, 0, 0, 57);
        assertEquals(57, angles[0], EPSILON);
        assertEquals(90, angles[1], EPSILON);
    }

    @Test
    void mappingMatchesTheProjectionForAnyFrame() {
        Random random = new Random(11);
        double[] origin = { 104, 65, -38 };
        for (int i = 0; i < 200; i++) {
            boolean mirrored = i % 3 == 0;
            float width = 4 + random.nextInt(60), height = 3 + random.nextInt(30);
            Matrix4f pose = frame(randomTurn(random), mirrored, width, height);
            View view = View.of(pose, origin[0], origin[1], origin[2], width, height, random.nextFloat() * 360);
            double halfHeight = 0.1 + random.nextDouble();

            double[] centre = project(pose, origin, view, halfHeight, width / 2, height / 2);
            assertEquals(0, centre[0], EPSILON);
            assertEquals(0, centre[1], EPSILON);
            // The glass is inside the depth range, just past the near plane
            assertTrue(centre[2] > -1 && centre[2] < -0.99, "depth " + centre[2]);

            // Viewport +x is screen right whether mirrored or not; +y is screen down unless mirrored
            double[] right = project(pose, origin, view, halfHeight, width / 2 + 1, height / 2);
            double[] down = project(pose, origin, view, halfHeight, width / 2, height / 2 + 1);
            assertTrue(right[0] > 0);
            assertEquals(0, right[1], EPSILON);
            assertEquals(0, down[0], EPSILON);
            assertTrue(mirrored ? down[1] > 0 : down[1] < 0);

            for (int j = 0; j < 5; j++) {
                double x = random.nextDouble() * width, y = random.nextDouble() * height;
                double[] drawn = project(pose, origin, view, halfHeight, x, y);
                double[] mapped = view.toNdc(x, y, halfHeight, ASPECT);
                assertEquals(drawn[0], mapped[0], EPSILON);
                assertEquals(drawn[1], mapped[1], EPSILON);
                double[] back = view.fromNdc(mapped[0], mapped[1], halfHeight, ASPECT);
                assertNotNull(back);
                assertEquals(x, back[0], EPSILON);
                assertEquals(y, back[1], EPSILON);
            }
        }
    }

    @Test
    void guiConversionsRoundTrip() {
        assertEquals(0, View.guiX(-1, 480), EPSILON);
        assertEquals(480, View.guiX(1, 480), EPSILON);
        assertEquals(0, View.guiY(1, 270), EPSILON);
        assertEquals(270, View.guiY(-1, 270), EPSILON);
        assertEquals(0.25, View.ndcX(View.guiX(0.25, 480), 480), EPSILON);
        assertEquals(-0.5, View.ndcY(View.guiY(-0.5, 270), 270), EPSILON);
    }

    @Test
    void fitPutsTheWholeDisplayInsideTheRoomAndTouchesItsNearerEdge() {
        Random random = new Random(3);
        int width = 480, height = 270;
        // Uneven sidebars: the left is wider, so the left edge limits the width
        int left = 140, right = 400, top = 20, bottom = 250;
        double margin = 8;
        for (int i = 0; i < 50; i++) {
            float w = 4 + random.nextInt(60), h = 3 + random.nextInt(30);
            Matrix4f pose = frame(randomTurn(random), i % 2 == 0, w, h);
            View view = View.of(pose, 0, 0, 0, w, h, 0);
            double zoom = view.fitHalfHeight(left, right, top, bottom, width, height, margin, ASPECT);
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE,
                    maxY = -Double.MAX_VALUE;
            for (double[] corner : new double[][] { { 0, 0 }, { w, 0 }, { 0, h }, { w, h } }) {
                double[] ndc = view.toNdc(corner[0], corner[1], zoom, ASPECT);
                double gx = View.guiX(ndc[0], width), gy = View.guiY(ndc[1], height);
                minX = Math.min(minX, gx);
                maxX = Math.max(maxX, gx);
                minY = Math.min(minY, gy);
                maxY = Math.max(maxY, gy);
            }
            // Centred, so the room on each axis is the nearer edge's: 240 - 140 across, 270 / 2 - 20 down
            double roomX = width / 2.0 - left - margin, roomY = height / 2.0 - top - margin;
            assertTrue(maxX - width / 2.0 <= roomX + EPSILON && width / 2.0 - minX <= roomX + EPSILON);
            assertTrue(maxY - height / 2.0 <= roomY + EPSILON && height / 2.0 - minY <= roomY + EPSILON);
            boolean touchesX = Math.abs(maxX - width / 2.0 - roomX) < 1e-3;
            boolean touchesY = Math.abs(maxY - height / 2.0 - roomY) < 1e-3;
            assertTrue(touchesX || touchesY, "fit is not tight");
        }
    }
}
