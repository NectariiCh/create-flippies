package com.nectariich.create_flippies.client.decal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.nectariich.create_flippies.content.decal.DecalArt;

import org.junit.jupiter.api.Test;

class SvgDecalImporterTest {

    private static final double TOLERANCE = 1e-3;

    private static SvgDecalImporter.Result read(String svg) throws IOException {
        return SvgDecalImporter.parse(svg.getBytes(StandardCharsets.UTF_8), "test");
    }

    /** Area of a layer's triangles, as a fraction of the picture's box. */
    private static double area(DecalArt.Layer layer) {
        float[] t = layer.triangles();
        double sum = 0;
        for (int i = 0; i < t.length; i += 6)
            sum += Math.abs((t[i + 2] - t[i]) * (t[i + 5] - t[i + 1]) - (t[i + 4] - t[i]) * (t[i + 3] - t[i + 1])) / 2;
        return sum;
    }

    private static DecalArt.Layer layer(DecalArt art, int rgb) {
        for (DecalArt.Layer layer : art.layers())
            if ((layer.colour() & 0xFFFFFF) == rgb)
                return layer;
        throw new AssertionError("No layer of colour " + Integer.toHexString(rgb) + " in " + art);
    }

    @Test
    void aFilledRectangleCoversTheBox() throws IOException {
        DecalArt art = read("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 20'>"
                + "<rect width='10' height='20' fill='red'/></svg>").art();
        assertEquals(10, art.width(), TOLERANCE);
        assertEquals(20, art.height(), TOLERANCE);
        assertEquals(1, art.layers().size());
        assertEquals(1, area(layer(art, 0xFF0000)), TOLERANCE);
    }

    @Test
    void aShapeOnTopCutsTheOneBeneath() throws IOException {
        DecalArt art = read("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'>"
                + "<rect width='10' height='10' fill='#00f'/><circle cx='5' cy='5' r='3' fill='#f00'/></svg>").art();
        double circle = Math.PI * 9 / 100;
        assertEquals(circle, area(layer(art, 0xFF0000)), 2e-3);
        assertEquals(1 - circle, area(layer(art, 0x0000FF)), 2e-3);
    }

    @Test
    void evenOddLeavesTheHoleEmpty() throws IOException {
        DecalArt art = read("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'>"
                + "<path fill-rule='evenodd' d='M0 0H10V10H0Z M2 2H8V8H2Z'/></svg>").art();
        assertEquals(1 - 0.36, area(layer(art, 0x000000)), TOLERANCE);
    }

    @Test
    void nonZeroFillsTheSameWindingTwice() throws IOException {
        DecalArt art = read("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'>"
                + "<path d='M0 0H10V10H0Z M2 2H8V8H2Z'/></svg>").art();
        assertEquals(1, area(layer(art, 0x000000)), TOLERANCE);
    }

    @Test
    void aStrokeIsAsWideAsItSays() throws IOException {
        DecalArt art = read("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'>"
                + "<line x1='0' y1='5' x2='10' y2='5' stroke='lime' stroke-width='2'/></svg>").art();
        assertEquals(0.2, area(layer(art, 0x00FF00)), TOLERANCE);
    }

    @Test
    void arcsMakeACircle() throws IOException {
        DecalArt art = read("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'>"
                + "<path d='M1 5a4 4 0 1 0 8 0a4 4 0 1 0-8 0z' fill='black'/></svg>").art();
        assertEquals(Math.PI * 16 / 100, area(layer(art, 0x000000)), 2e-3);
    }

    @Test
    void stylesheetsTransformsAndUseAreFollowed() throws IOException {
        DecalArt art = read("<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink'"
                + " viewBox='0 0 10 10'><style>.a { fill: #123456 } g > .b { fill: rgb(255, 0, 0) }</style>"
                + "<defs><rect id='r' width='2' height='2'/></defs>"
                + "<g transform='translate(5 0)'><use xlink:href='#r' class='a'/></g>"
                + "<g><rect class='b' x='0' y='8' width='10' height='2'/></g></svg>").art();
        assertEquals(0.04, area(layer(art, 0x123456)), TOLERANCE);
        assertEquals(0.2, area(layer(art, 0xFF0000)), TOLERANCE);
    }

    @Test
    void aClipPathTrimsWhatItClips() throws IOException {
        DecalArt art = read("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'>"
                + "<clipPath id='c'><rect width='5' height='10'/></clipPath>"
                + "<rect width='10' height='10' fill='white' clip-path='url(#c)'/></svg>").art();
        assertEquals(0.5, area(layer(art, 0xFFFFFF)), TOLERANCE);
    }

    @Test
    void halfSeeThroughPaintIsMixedIn() throws IOException {
        DecalArt art = read("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'>"
                + "<rect width='10' height='10' fill='#0000ff'/>"
                + "<rect width='5' height='10' fill='#ff0000' fill-opacity='0.5'/></svg>").art();
        assertEquals(0.5, area(layer(art, 0x800080)), TOLERANCE);
        assertEquals(0.5, area(layer(art, 0x0000FF)), TOLERANCE);
    }

    @Test
    void customPropertiesAndLightDarkAreResolved() throws IOException {
        SvgDecalImporter.Result result = read("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'>"
                + "<style>:root { --brand: #00ff00 }</style>"
                + "<rect width='10' height='2' fill='var(--brand)'/>"
                + "<rect y='2' width='10' height='2' fill='var(--missing, #0000ff)'/>"
                + "<rect y='4' width='10' height='2' fill='light-dark(#ff0000, #ffffff)'/>"
                + "<rect y='6' width='10' height='2' fill='var(--missing)'/></svg>");
        DecalArt art = result.art();
        assertEquals(0.2, area(layer(art, 0x00FF00)), TOLERANCE);
        assertEquals(0.2, area(layer(art, 0x0000FF)), TOLERANCE);
        assertEquals(0.2, area(layer(art, 0xFF0000)), TOLERANCE);
        // Nowhere declared and no fallback: black, as a browser would, and said so
        assertEquals(0.2, area(layer(art, 0x000000)), TOLERANCE);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("colours")), result.warnings().toString());
    }

    @Test
    void anythingOutsideTheFrameIsDropped() throws IOException {
        DecalArt art = read("<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'>"
                + "<rect x='5' y='5' width='20' height='20' fill='black'/></svg>").art();
        assertEquals(0.25, area(layer(art, 0x000000)), TOLERANCE);
    }

    /** The box a layer's triangles span, as {@code minX, minY, maxX, maxY} fractions of the picture's box. */
    private static double[] bounds(DecalArt.Layer layer) {
        float[] t = layer.triangles();
        double[] box = { Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
        for (int i = 0; i < t.length; i += 2) {
            box[0] = Math.min(box[0], t[i]);
            box[1] = Math.min(box[1], t[i + 1]);
            box[2] = Math.max(box[2], t[i]);
            box[3] = Math.max(box[3], t[i + 1]);
        }
        return box;
    }

    private static final String SVG = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>";

    @Test
    void textIsDrawnAsItsGlyphs() throws IOException {
        SvgDecalImporter.Result result = read(SVG
                + "<text x='10' y='50' font-family='sans-serif' font-size='20' fill='#ff0000'>Hi</text></svg>");
        DecalArt.Layer layer = layer(result.art(), 0xFF0000);
        double[] box = bounds(layer);
        assertTrue(area(layer) > 0.005, "area " + area(layer));
        // On the baseline at y 50, from x 10, about as tall as the font's capitals
        assertTrue(box[0] >= 0.1 && box[0] < 0.13, "left " + box[0]);
        assertEquals(0.5, box[3], 0.01);
        assertTrue(box[1] > 0.3 && box[1] < 0.4, "top " + box[1]);
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
    }

    @Test
    void anchoredTextIsCentredOnItsPosition() throws IOException {
        DecalArt art = read(SVG + "<text x='50' y='50' font-size='20' text-anchor='middle'>MMMM</text></svg>").art();
        double[] box = bounds(layer(art, 0x000000));
        assertEquals(0.5, (box[0] + box[2]) / 2, 0.01);
        DecalArt end = read(SVG + "<text x='90' y='50' font-size='20' style='text-anchor:end'>MMMM</text></svg>")
                .art();
        // Short of the anchor by the last glyph's side bearing
        double right = bounds(layer(end, 0x000000))[2];
        assertTrue(right <= 0.9 && right > 0.86, "right " + right);
    }

    @Test
    void tspansTakeTheirOwnPositionsAndStyles() throws IOException {
        // As Illustrator writes it: a transform on the text, a class sheet, one tspan a line
        DecalArt art = read(SVG + "<style>.st0{fill:#00FF00;font-family:'ArialMT', sans-serif;font-size:10px;}"
                + ".st1{fill:#0000FF;}</style>"
                + "<text transform='matrix(1 0 0 1 10 20)' class='st0'>\n  <tspan x='0' y='0'>Title</tspan>\n"
                + "  <tspan x='0' y='60' class='st1'>Bottom</tspan>\n</text></svg>").art();
        double[] top = bounds(layer(art, 0x00FF00));
        double[] bottom = bounds(layer(art, 0x0000FF));
        assertEquals(0.2, top[3], 0.01);
        assertEquals(0.8, bottom[3], 0.03);
        assertEquals(0.1, top[0], 0.01);
        assertEquals(0.1, bottom[0], 0.01);
    }

    @Test
    void spacesCollapseUnlessPreserved() throws IOException {
        String text = "<text x='0' y='50' font-size='10'>A      B</text></svg>";
        double collapsed = bounds(layer(read(SVG + text).art(), 0x000000))[2];
        double kept = bounds(layer(read(SVG.replace("<svg ", "<svg xml:space='preserve' ") + text).art(),
                0x000000))[2];
        assertTrue(kept > collapsed + 0.05, collapsed + " " + kept);
    }

    @Test
    void positionListsPlaceEachLetter() throws IOException {
        DecalArt art = read(SVG + "<text x='10 80' y='50' font-size='10' fill='#ff0000'>II</text></svg>").art();
        double[] box = bounds(layer(art, 0xFF0000));
        assertTrue(box[0] < 0.15 && box[2] > 0.8, box[0] + " " + box[2]);
    }

    @Test
    void aMissingFontIsReplacedAndSaidSo() throws IOException {
        SvgDecalImporter.Result result = read(SVG
                + "<text y='50' font-family='No Such Font Anywhere' font-size='20'>x</text></svg>");
        assertTrue(area(layer(result.art(), 0x000000)) > 0);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("fonts")), result.warnings().toString());
    }

    @Test
    void textAlongAPathIsReported() throws IOException {
        SvgDecalImporter.Result result = read(SVG + "<rect width='1' height='1'/><path id='p' d='M0 0H100'/>"
                + "<text><textPath href='#p'>hi</textPath></text></svg>");
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("Text along")), result.warnings().toString());
    }

    @Test
    void transparentLeavesOutAFullFrameBackground() throws IOException {
        String svg = SVG + "<rect width='100' height='100' fill='white'/>"
                + "<rect x='25' y='25' width='50' height='50' fill='red'/></svg>";
        DecalArt kept = read(svg).art();
        assertEquals(0.75, area(layer(kept, 0xFFFFFF)), TOLERANCE);
        DecalArt clear = SvgDecalImporter.parse(svg.getBytes(StandardCharsets.UTF_8), "test", true).art();
        assertEquals(1, clear.layers().size());
        assertEquals(0.25, area(layer(clear, 0xFF0000)), TOLERANCE);
        assertEquals(100, clear.width(), TOLERANCE);
    }

    @Test
    void transparentFollowsAFigmaFrame() throws IOException {
        // A frame clipped to itself, filled, then a stroke round it that is kept
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='100' height='50' viewBox='0 0 100 50'>"
                + "<g clip-path='url(#c)'><rect width='100' height='50' fill='#F5F5F5'/>"
                + "<rect x='-10' y='-10' width='120' height='70' fill='#EEEEEE' fill-opacity='0.5'/>"
                + "<circle cx='50' cy='25' r='10' fill='#00f'/></g>"
                + "<defs><clipPath id='c'><rect width='100' height='50'/></clipPath></defs></svg>";
        DecalArt clear = SvgDecalImporter.parse(svg.getBytes(StandardCharsets.UTF_8), "test", true).art();
        assertEquals(1, clear.layers().size());
        assertEquals(Math.PI * 100 / 5000, area(layer(clear, 0x0000FF)), 2e-3);
    }

    @Test
    void transparentKeepsWhatDoesNotCoverTheFrameOrLiesOnTop() throws IOException {
        String svg = SVG + "<rect x='5' y='5' width='90' height='90' fill='white'/>"
                + "<rect width='100' height='100' fill='red' fill-opacity='0.6'/></svg>";
        DecalArt art = SvgDecalImporter.parse(svg.getBytes(StandardCharsets.UTF_8), "test", true).art();
        assertTrue(art.layers().size() == 2, art.layers().toString());
    }

    @Test
    void aDrawingThatIsAllBackgroundIsRefusedWhenTransparent() {
        byte[] svg = (SVG + "<rect width='100' height='100' fill='white'/></svg>").getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> SvgDecalImporter.parse(svg, "test", true));
    }

    @Test
    void aFileWithNothingVisibleIsRefused() {
        assertThrows(IOException.class, () -> read("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'>"
                + "<rect width='10' height='10' fill='none'/></svg>"));
        assertThrows(IOException.class, () -> read("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'>"
                + "<g opacity='0.2'><rect width='10' height='10'/></g></svg>"));
        assertThrows(IOException.class, () -> read("not xml"));
    }

    @Test
    void anExternalDoctypeIsNotFetched() throws IOException {
        DecalArt art = read("<?xml version='1.0'?><!DOCTYPE svg PUBLIC '-//W3C//DTD SVG 1.1//EN'"
                + " 'http://invalid.invalid/svg11.dtd'><svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1 1'>"
                + "<rect width='1' height='1'/></svg>").art();
        assertEquals(1, area(layer(art, 0x000000)), TOLERANCE);
    }

    @Test
    void theArtSurvivesEncoding() throws IOException {
        DecalArt art = read("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'>"
                + "<rect width='10' height='10' fill='#00f'/><circle cx='5' cy='5' r='3' fill='#f00'/></svg>").art();
        byte[] blob = art.encode();
        assertTrue(DecalArt.isDecal(blob));
        DecalArt back = DecalArt.decode(blob);
        assertEquals(art.name(), back.name());
        assertEquals(art.triangleCount(), back.triangleCount());
        assertEquals(area(layer(art, 0xFF0000)), area(layer(back, 0xFF0000)), 1e-4);
    }

    @Test
    void garbageIsNotADecal() {
        assertThrows(IOException.class, () -> DecalArt.decode(new byte[] { 1, 2, 3 }));
        byte[] fake = "CFDECAL1 and then nonsense".getBytes(StandardCharsets.US_ASCII);
        assertTrue(DecalArt.isDecal(fake));
        assertThrows(IOException.class, () -> DecalArt.decode(fake));
    }
}
