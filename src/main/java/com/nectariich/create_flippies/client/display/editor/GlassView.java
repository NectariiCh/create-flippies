package com.nectariich.create_flippies.client.display.editor;

/**
 * The camera the display editor looks through, and the mapping between the editor's
 * screen and the display's viewport that comes with it.
 *
 * Two of these exist: one flies the player's camera to face a display in the world
 * ({@code WorldGlassView}), the other takes the Design Workbench's camera over its
 * bench with the model laid glass-up ({@code BenchGlassView}). Either way the view is
 * orthographic once in place, so the mapping is affine and exact, and it is centred
 * on the middle of the whole screen, whatever the editor's sidebars are doing.
 *
 * Viewport coordinates are tenths of a pixel from the viewport's top left, +x right,
 * +y down, as elements store them. Screen coordinates are the GUI's, scaled, with the
 * screen's width and height.
 *
 * Zoom is the orthographic half height of the view in blocks: smaller is closer.
 */
public interface GlassView {

    /** Starts the flight in. */
    void open();

    /** Starts the flight back to wherever the camera came from. */
    void close();

    /** Whether the camera is in place and the view wholly orthographic, so the mapping holds. */
    boolean ready();

    /** The point of the screen over a viewport point, or null while not {@link #ready}. */
    double[] toScreen(double vx, double vy, int width, int height);

    /** The viewport point under a point of the screen, or null while not {@link #ready}. */
    double[] toViewport(double gx, double gy, int width, int height);

    /** The current zoom, as the view's half height in blocks. */
    double zoom();

    /** Sets the zoom, as the view's half height in blocks; the view stays centred on the display. */
    void setZoom(double halfHeight);

    /**
     * The zoom at which the whole display, centred on the middle of the screen, lies
     * inside the rectangle from {@code left} to {@code right} and {@code top} to
     * {@code bottom} (screen coordinates), with a small margin.
     */
    double fitZoom(int left, int right, int top, int bottom, int width, int height);
}
