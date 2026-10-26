package com.nectariich.create_flippies.client.decal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.nectariich.create_flippies.content.decal.DecalArt;

import org.junit.jupiter.api.Test;

/**
 * A display's picture keeps its gradients: linear and radial, with their units,
 * transforms, spreads and stop opacity, as a colour at every vertex of finely cut
 * triangles, within the display's triangle budget; and a panel decal still paints them
 * flat.
 */
class SvgShadingTest {

    private static final String SVG = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>";

    private static DecalArt shaded(String body) throws IOException {
        return SvgDecalImporter.parseShaded((SVG + body + "</svg>").getBytes(StandardCharsets.UTF_8), "test").art();
    }

    private static DecalArt.Layer shadedLayer(DecalArt art) {
        for (DecalArt.Layer layer : art.layers())
            if (layer.shades() != null)
                return layer;
        return null;
    }

    /** The colour of the vertex nearest (x, y), in the unit box. */
    private static int near(DecalArt.Layer layer, double x, double y) {
        int best = -1;
        double distance = Double.MAX_VALUE;
        float[] t = layer.triangles();
        for (int i = 0; i < t.length / 2; i++) {
            double d = Math.hypot(t[2 * i] - x, t[2 * i + 1] - y);
            if (d < distance) {
                distance = d;
                best = i;
            }
        }
        return layer.shades()[best];
    }

    private static int channel(int argb, int shift) {
        return (argb >> shift) & 0xFF;
    }

    @Test
    void aLinearGradientIsShadedAcrossItsShape() throws IOException {
        DecalArt art = shaded("<defs><linearGradient id='g'><stop offset='0' stop-color='#ff0000'/>"
                + "<stop offset='1' stop-color='#0000ff'/></linearGradient></defs>"
                + "<rect width='100' height='100' fill='url(#g)'/>");
        DecalArt.Layer layer = shadedLayer(art);
        assertNotNull(layer);
        assertTrue(layer.triangleCount() > 2, "cut finer than the rectangle's two triangles");
        assertEquals(0xFFFF0000, near(layer, 0, 0.5));
        assertEquals(0xFF0000FF, near(layer, 1, 0.5));
        int middle = near(layer, 0.5, 0.5);
        assertTrue(Math.abs(channel(middle, 16) - 128) < 12 && Math.abs(channel(middle, 0) - 128) < 12);
    }

    @Test
    void aRadialGradientWithStopOpacityAndAReflectingSpread() throws IOException {
        DecalArt art = shaded("<defs><radialGradient id='g' gradientUnits='userSpaceOnUse' cx='50' cy='50' r='25'"
                + " spreadMethod='reflect'><stop offset='0' stop-color='#ffffff'/>"
                + "<stop offset='1' stop-color='#000000' stop-opacity='0.5'/></radialGradient></defs>"
                + "<rect width='100' height='100' fill='url(#g)'/>");
        DecalArt.Layer layer = shadedLayer(art);
        int centre = near(layer, 0.5, 0.5);
        assertEquals(0xFF, centre >>> 24);
        assertTrue(channel(centre, 8) > 230);
        // Reflected: at twice the radius it is white again
        int corner = near(layer, 0.5 + 0.5 * Math.cos(0.3), 0.5 + 0.5 * Math.sin(0.3));
        assertTrue(channel(corner, 8) > 150, "reflected back toward the centre's colour");
    }

    @Test
    void aGradientTransformTurnsIt() throws IOException {
        DecalArt art = shaded("<defs><linearGradient id='g' gradientTransform='rotate(90 0.5 0.5)'>"
                + "<stop offset='0' stop-color='#ff0000'/><stop offset='1' stop-color='#0000ff'/>"
                + "</linearGradient></defs><rect width='100' height='100' fill='url(#g)'/>");
        DecalArt.Layer layer = shadedLayer(art);
        assertEquals(0xFFFF0000, near(layer, 0.5, 0));
        assertEquals(0xFF0000FF, near(layer, 0.5, 1));
    }

    @Test
    void stopsComeThroughAnHrefChain() throws IOException {
        DecalArt art = shaded("<defs><linearGradient id='base'><stop offset='0' stop-color='#00ff00'/>"
                + "<stop offset='1' stop-color='#000000'/></linearGradient>"
                + "<linearGradient id='g' href='#base' x1='1' x2='0'/></defs>"
                + "<rect width='100' height='100' fill='url(#g)'/>");
        DecalArt.Layer layer = shadedLayer(art);
        assertEquals(0xFF00FF00, near(layer, 1, 0.5));
    }

    @Test
    void shadingRoundTripsAndOnlyThenChangesTheFormat() throws IOException {
        DecalArt art = shaded("<defs><linearGradient id='g'><stop offset='0' stop-color='#ff0000'/>"
                + "<stop offset='1' stop-color='#0000ff'/></linearGradient></defs>"
                + "<rect width='50' height='100' fill='url(#g)'/><rect x='50' width='50' height='100' fill='#00ff00'/>");
        DecalArt read = DecalArt.decode(art.encode());
        DecalArt.Layer layer = shadedLayer(read);
        assertNotNull(layer);
        assertTrue(java.util.Arrays.equals(shadedLayer(art).shades(), layer.shades()));
        assertEquals(art.triangleCount(), read.triangleCount());

        DecalArt flat = SvgDecalImporter.parse((SVG + "<defs><linearGradient id='g'><stop offset='0' "
                + "stop-color='#ff0000'/><stop offset='1' stop-color='#0000ff'/></linearGradient></defs>"
                + "<rect width='100' height='100' fill='url(#g)'/></svg>").getBytes(StandardCharsets.UTF_8), "flat")
                .art();
        assertNull(shadedLayer(flat), "a panel decal paints it flat");
        assertEquals('1', (char) flat.encode()[7], "and stores it as before");
    }

    @Test
    void aDrawingPastTheDisplayBudgetIsRefused() {
        StringBuilder many = new StringBuilder();
        // Each circle is a few dozen triangles; this is far past twenty thousand
        for (int i = 0; i < 1500; i++)
            many.append("<circle cx='").append(i % 50 * 2 + 1).append("' cy='").append(i / 50 * 3 + 1)
                    .append("' r='0.9' fill='#").append(String.format("%06x", i * 2654435 & 0xFFFFFF)).append("'/>");
        assertThrows(IOException.class, () -> shaded(many.toString()));
    }
}
