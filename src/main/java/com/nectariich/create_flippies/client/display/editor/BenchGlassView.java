package com.nectariich.create_flippies.client.display.editor;

import com.nectariich.create_flippies.client.design.DesignCamera;
import com.nectariich.create_flippies.client.design.DesignPreview;
import com.nectariich.create_flippies.content.display.element.Viewport;

/**
 * The display editor's camera on the Design Workbench: the workbench's own camera put
 * into {@link DesignCamera#setEditor editor mode}, looking straight down on the bench,
 * where the preview lays the screen design glass up with its viewport centred over
 * the middle of the bench ({@link DesignPreview#viewportToPlate}).
 *
 * A viewport point goes to the plate through the laid-flat pose, then to the screen
 * through the top view; both are exact, so the mapping is affine and its inverse is
 * read off three projected points. The top view's own conversions only hold once
 * the camera has settled, shift and all, so nothing maps before then.
 *
 * Zoom is the editor's top view half height; the view never pans.
 */
public final class BenchGlassView implements GlassView {

    /** The margin {@link #fitZoom} leaves round the display, in GUI pixels. */
    private static final double FIT_MARGIN = 8;

    @Override
    public void open() {
        DesignCamera.setEditor(true);
    }

    @Override
    public void close() {
        DesignCamera.setEditor(false);
    }

    @Override
    public boolean ready() {
        return DesignCamera.isEditor() && DesignCamera.topReady() && DesignPreview.viewportToPlate(0, 0) != null;
    }

    @Override
    public double[] toScreen(double vx, double vy, int width, int height) {
        if (!ready())
            return null;
        double[] plate = DesignPreview.viewportToPlate(vx / Viewport.UNITS_PER_PIXEL, vy / Viewport.UNITS_PER_PIXEL);
        return plate == null ? null : DesignCamera.plateToScreen(plate[0], plate[1], width, height);
    }

    @Override
    public double[] toViewport(double gx, double gy, int width, int height) {
        if (!ready())
            return null;
        // The screen point of the viewport's corner and of a pixel along each of its
        // axes give the affine map from viewport pixels to the screen; that is inverted
        double[] o = DesignPreview.viewportToPlate(0, 0), a = DesignPreview.viewportToPlate(1, 0),
                b = DesignPreview.viewportToPlate(0, 1);
        double[] plate = DesignCamera.screenToPlate(gx, gy, width, height);
        if (o == null || a == null || b == null || plate == null)
            return null;
        double ax = a[0] - o[0], az = a[1] - o[1], bx = b[0] - o[0], bz = b[1] - o[1];
        double det = ax * bz - bx * az;
        if (Math.abs(det) < 1e-12)
            return null;
        double px = plate[0] - o[0], pz = plate[1] - o[1];
        double x = (px * bz - bx * pz) / det, y = (ax * pz - px * az) / det;
        return new double[] { x * Viewport.UNITS_PER_PIXEL, y * Viewport.UNITS_PER_PIXEL };
    }

    @Override
    public double zoom() {
        return DesignCamera.editorZoom();
    }

    @Override
    public void setZoom(double halfHeight) {
        DesignCamera.editorZoom(halfHeight);
    }

    /**
     * The half height at which the laid-flat viewport's extent on the plate, about
     * the middle of the bench (the middle of the screen once the shift is gone),
     * reaches the nearer side of the rectangle less the margin, across or down,
     * whichever comes first. Measured from the viewport's corners, so a turned or
     * mirrored frame fits as drawn. Clamped as the top view clamps it.
     */
    @Override
    public double fitZoom(int left, int right, int top, int bottom, int width, int height) {
        double[] size = DesignPreview.viewportSize();
        if (size == null || width <= 0 || height <= 0)
            return zoom();
        // Half the viewport's extent across (plate x) and down (plate z), in blocks
        double extentX = 0, extentZ = 0;
        for (double[] corner : new double[][] { { 0, 0 }, { size[0], 0 }, { 0, size[1] }, { size[0], size[1] } }) {
            double[] plate = DesignPreview.viewportToPlate(corner[0], corner[1]);
            if (plate == null)
                return zoom();
            extentX = Math.max(extentX, Math.abs(plate[0] / 16 - 0.5));
            extentZ = Math.max(extentZ, Math.abs(plate[1] / 16 - 0.5));
        }
        // Room from the middle of the screen to each side of the rectangle, in GUI pixels
        double roomX = Math.min(width / 2.0 - left, right - width / 2.0) - FIT_MARGIN;
        double roomY = Math.min(height / 2.0 - top, bottom - height / 2.0) - FIT_MARGIN;
        if (roomX <= 0 || roomY <= 0)
            return DesignCamera.TopView.MAX_HALF_HEIGHT;
        // A block across is width / (2 * halfHeight * aspect) GUI pixels, and a block
        // down is height / (2 * halfHeight)
        double aspect = DesignCamera.windowAspect();
        double fit = Math.max(extentX * width / (2 * roomX * aspect), extentZ * height / (2 * roomY));
        return Math.max(DesignCamera.TopView.MIN_HALF_HEIGHT, Math.min(DesignCamera.TopView.MAX_HALF_HEIGHT, fit));
    }
}
