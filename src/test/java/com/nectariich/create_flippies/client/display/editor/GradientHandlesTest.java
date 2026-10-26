package com.nectariich.create_flippies.client.display.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ElementPaint;
import com.nectariich.create_flippies.content.display.element.ScreenElement;

import org.junit.jupiter.api.Test;

/**
 * The glass's gradient handles sit where the renderer paints the gradient: a bar's
 * fill is laid out in its turned frame, so a linear gradient at angle 0 on a bar
 * turned a quarter runs up the glass, not across it.
 */
class GradientHandlesTest {

    static final double EPS = 1e-9;

    static ScreenElement bar(int quarters, ElementPaint paint) {
        return ScreenElement.create(0, ElementKind.BAR, 100, 200, 100, 1000).toBuilder().quarters(quarters)
                .paint(paint).build();
    }

    static ElementPaint linear(int angle) {
        return ElementPaint.SOLID.withType(ElementPaint.Type.LINEAR).withAngle(angle);
    }

    @Test
    void unturnedBarRunsAcrossItsBox() {
        // 100 wide, 1000 tall, centre (150, 700)
        double[][] handles = GlassTools.gradientHandles(bar(0, linear(0)));
        assertArrayEquals(new double[] { 100, 700 }, handles[0], EPS);
        assertArrayEquals(new double[] { 200, 700 }, handles[1], EPS);
    }

    @Test
    void quarterTurnedBarRunsUpTheGlass() {
        // Along the bar is the box's height once turned; the renderer turns by minus a quarter
        double[][] handles = GlassTools.gradientHandles(bar(1, linear(0)));
        assertArrayEquals(new double[] { 150, 1200 }, handles[0], EPS);
        assertArrayEquals(new double[] { 150, 200 }, handles[1], EPS);
    }

    @Test
    void paintFrameRoundTripsForEveryTurn() {
        for (int quarters = 0; quarters < 4; quarters++) {
            ScreenElement element = bar(quarters, linear(30));
            double[] glass = GlassTools.fromPaintFrame(element, 12, 34);
            assertArrayEquals(new double[] { 12, 34 }, GlassTools.toPaintFrame(element, glass[0], glass[1]), EPS);
        }
    }

    @Test
    void radialCentreFollowsTheTurn() {
        ElementPaint radial = ElementPaint.SOLID.withType(ElementPaint.Type.RADIAL).withCentre(0, 0, 500);
        // The paint frame's top left corner, which a quarter turn carries to the box's bottom left
        double[][] handles = GlassTools.gradientHandles(bar(1, radial));
        assertArrayEquals(new double[] { 100, 1200 }, handles[0], EPS);
    }
}
