package com.nectariich.create_flippies.content.display.element;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * How a fill is painted: a linear gradient spans its box corner to corner whatever its
 * angle, a radial one reaches its radius, and a bar's bands switch hard where they
 * touch and fade across a gap, in value units or in percent.
 */
class ElementPaintTest {

    private static final int RED = 0xFFFF0000, GREEN = 0xFF00FF00, BLUE = 0xFF0000FF;

    private static ElementPaint bands(boolean percent, ElementPaint.Band... bands) {
        return ElementPaint.SOLID.withType(ElementPaint.Type.BANDS).withBands(List.of(bands)).withPercent(percent);
    }

    @Test
    void aLinearGradientSpansItsBoxWhateverTheAngle() {
        ElementPaint across = ElementPaint.SOLID.withType(ElementPaint.Type.LINEAR);
        assertEquals(0, across.position(0, 0.5, 2), 1e-9);
        assertEquals(1, across.position(1, 0.5, 2), 1e-9);
        assertEquals(0.5, across.position(0.5, 0.2, 2), 1e-9);

        ElementPaint down = across.withAngle(90);
        assertEquals(0, down.position(0.3, 0, 2), 1e-9);
        assertEquals(1, down.position(0.3, 1, 2), 1e-9);

        // Diagonally the first and last corners are the ends
        ElementPaint diagonal = across.withAngle(45);
        assertEquals(0, diagonal.position(0, 0, 1), 1e-9);
        assertEquals(1, diagonal.position(1, 1, 1), 1e-9);
        ElementPaint back = across.withAngle(180);
        assertEquals(1, back.position(0, 0.5, 3), 1e-9);
    }

    @Test
    void aRadialGradientReachesItsRadius() {
        ElementPaint radial = ElementPaint.SOLID.withType(ElementPaint.Type.RADIAL).withCentre(500, 500, 500);
        assertEquals(0, radial.position(0.5, 0.5, 1), 1e-9);
        assertEquals(1, radial.position(1, 0.5, 1), 1e-9);
        assertEquals(1, radial.position(0.5, 0, 4), 1e-9, "measured in the box, so a wide box is an ellipse");
    }

    @Test
    void stopsAreSortedCappedAndBlended() {
        ElementPaint paint = ElementPaint.SOLID.withType(ElementPaint.Type.LINEAR).withStops(List.of(
                new ElementPaint.Stop(BLUE, 1000), new ElementPaint.Stop(RED, 0)));
        assertEquals(RED, paint.stops().get(0).colour());
        assertEquals(RED, paint.colourAt(-1));
        assertEquals(BLUE, paint.colourAt(2));
        assertEquals(0xFF800080, paint.colourAt(0.5));

        List<ElementPaint.Stop> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++)
            many.add(new ElementPaint.Stop(RED, i * 10));
        assertEquals(ElementPaint.MAX_STOPS, paint.withStops(many).stops().size());
    }

    @Test
    void touchingBandsSwitchHardAndAGapFades() {
        ElementPaint touching = bands(false, new ElementPaint.Band(RED, 0, 50), new ElementPaint.Band(GREEN, 50, 100));
        assertEquals(RED, touching.bandColour(-20, 0, 100, 0));
        assertEquals(RED, touching.bandColour(49.9, 0, 100, 0));
        assertEquals(GREEN, touching.bandColour(50.1, 0, 100, 0));
        assertEquals(GREEN, touching.bandColour(500, 0, 100, 0), "the last band reaches past its own top");

        ElementPaint gap = bands(false, new ElementPaint.Band(RED, 0, 40), new ElementPaint.Band(BLUE, 60, 100));
        assertEquals(RED, gap.bandColour(40, 0, 100, 0));
        assertEquals(0xFF800080, gap.bandColour(50, 0, 100, 0));
        assertEquals(BLUE, gap.bandColour(60, 0, 100, 0));
    }

    @Test
    void percentBandsReadTheValueAlongTheRange() {
        ElementPaint paint = bands(true, new ElementPaint.Band(RED, 0, 50), new ElementPaint.Band(GREEN, 50, 100));
        assertEquals(RED, paint.bandColour(240, 200, 300, 0));
        assertEquals(GREEN, paint.bandColour(260, 200, 300, 0));
    }

    @Test
    void bandsNeverOverlap() {
        ElementPaint paint = bands(false, new ElementPaint.Band(RED, 0, 60), new ElementPaint.Band(GREEN, 40, 50),
                new ElementPaint.Band(BLUE, 55, 100));
        List<ElementPaint.Band> held = paint.bands();
        for (int i = 1; i < held.size(); i++)
            assertTrue(held.get(i).lower() >= held.get(i - 1).upper(), "band " + i);
        assertEquals(60, held.get(1).lower());
        assertEquals(60, held.get(1).upper());
    }

    @Test
    void roundTripsAndWritesNothingForSolid() {
        assertTrue(ElementPaint.SOLID.write().isEmpty());
        ElementPaint paint = ElementPaint.SOLID.withType(ElementPaint.Type.RADIAL).withCentre(200, 700, 900)
                .withStops(List.of(new ElementPaint.Stop(RED, 100), new ElementPaint.Stop(BLUE, 900)))
                .withBands(List.of(new ElementPaint.Band(GREEN, -5, 7.5f))).withPercent(true).withAngle(-30);
        assertEquals(paint, ElementPaint.read(paint.write()));
        assertEquals(330, paint.angle());
    }
}
