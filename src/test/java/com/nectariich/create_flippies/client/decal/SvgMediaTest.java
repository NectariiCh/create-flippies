package com.nectariich.create_flippies.client.decal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import com.nectariich.create_flippies.content.decal.DecalArt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SvgMediaTest {

    private static final String SVG = "<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink'"
            + " viewBox='0 0 100 100'>";

    private static final String XHTML = "xmlns='http://www.w3.org/1999/xhtml'";

    // Fixtures

    /** A PNG of the given size, the left half red and the right half blue, as bytes. */
    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y, x < width / 2 ? 0xFFFF0000 : 0xFF0000FF);
        return encode(image, "png");
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out), "no writer for " + format);
        return out.toByteArray();
    }

    private static String dataUri(byte[] png) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    private static SvgDecalImporter.Result read(String body) throws IOException {
        return read(body, false);
    }

    private static SvgDecalImporter.Result read(String body, boolean transparent) throws IOException {
        return SvgDecalImporter.parse((SVG + body + "</svg>").getBytes(StandardCharsets.UTF_8), "test", transparent);
    }

    private static double area(List<DecalArt.Layer> layers) {
        double sum = 0;
        for (DecalArt.Layer layer : layers) {
            float[] t = layer.triangles();
            for (int i = 0; i < t.length; i += 6)
                sum += Math.abs((t[i + 2] - t[i]) * (t[i + 5] - t[i + 1]) - (t[i + 4] - t[i]) * (t[i + 3] - t[i + 1]))
                        / 2;
        }
        return sum;
    }

    /** minX, minY, maxX, maxY of every vertex of the layers. */
    private static double[] bounds(List<DecalArt.Layer> layers) {
        double[] box = { Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
        for (DecalArt.Layer layer : layers) {
            float[] t = layer.triangles();
            for (int i = 0; i < t.length; i += 2) {
                box[0] = Math.min(box[0], t[i]);
                box[1] = Math.min(box[1], t[i + 1]);
                box[2] = Math.max(box[2], t[i]);
                box[3] = Math.max(box[3], t[i + 1]);
            }
        }
        return box;
    }

    /** The store's hash, as {@code DesignBundle.hash} takes it: the first sixteen bytes of SHA-256, in hex. */
    private static String hash(byte[] blob) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob);
        StringBuilder hex = new StringBuilder(32);
        for (int i = 0; i < 16; i++)
            hex.append(String.format("%02x", digest[i]));
        return hex.toString();
    }

    private static DecalArt.Layer pixelLayer(DecalArt art, int rgb) {
        for (DecalArt.Layer layer : art.pixels())
            if ((layer.colour() & 0xFFFFFF) == rgb)
                return layer;
        throw new AssertionError("No pixel layer of colour " + Integer.toHexString(rgb) + " in " + art);
    }

    // Data URIs

    @Test
    void base64DataUriDecodesEvenWithLineBreaks() throws IOException {
        byte[] bytes = { 1, 2, 3, (byte) 250, 0, 64 };
        String encoded = Base64.getEncoder().encodeToString(bytes);
        String uri = "data:image/png;base64," + encoded.substring(0, 3) + "\n  " + encoded.substring(3);
        assertArrayEquals(bytes, SvgImages.dataUri(uri));
    }

    @Test
    void percentEncodedDataUriDecodes() throws IOException {
        assertArrayEquals(new byte[] { 'a', ' ', (byte) 0xFF, 'b' }, SvgImages.dataUri("data:,a%20%FFb"));
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), SvgImages.dataUri("DATA:text/plain,hi"));
    }

    @Test
    void badDataUrisAreRefused() {
        assertThrows(IOException.class, () -> SvgImages.dataUri("data:image/png;base64,***not base64***"));
        assertThrows(IOException.class, () -> SvgImages.dataUri("data:image/png;base64"));
        assertThrows(IOException.class, () -> SvgImages.dataUri("data:,bad%zzescape"));
        assertThrows(IOException.class, () -> SvgImages.dataUri("data:,cut%2"));
        assertThrows(IOException.class, () -> SvgImages.dataUri("data:image/svg+xml;base64,PHN2Zy8+"));
        assertThrows(IOException.class, () -> SvgImages.decode(new byte[] { 1, 2, 3, 4, 5 }));
    }

    @Test
    void aBrokenPictureIsLeftOutWithAWarning() throws IOException {
        SvgDecalImporter.Result result = read("<rect width='10' height='10'/>"
                + "<image href='data:image/png;base64,AAAA' width='50' height='50'/>");
        assertTrue(result.art().pixels().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("picture was left out")), result.warnings()
                .toString());
    }

    // References

    @Test
    void networkReferencesAreRecognisedAndNeverFetched() throws IOException {
        assertTrue(SvgImages.isRemote("https://example.com/a.png"));
        assertTrue(SvgImages.isRemote("http://example.com/a.png"));
        assertTrue(SvgImages.isRemote("//example.com/a.png"));
        assertTrue(SvgImages.isRemote("ftp://example.com/a.png"));
        assertFalse(SvgImages.isRemote("data:image/png;base64,AAAA"));
        assertFalse(SvgImages.isRemote("pictures/a.png"));
        assertFalse(SvgImages.isRemote("C:\\pictures\\a.png"));
        assertFalse(SvgImages.isRemote("file:///tmp/a.png"));

        // An address no machine answers: were it fetched, the import would stall or fail
        SvgDecalImporter.Result result = read("<rect width='10' height='10'/>"
                + "<image href='https://192.0.2.1/a.png' width='50' height='50'/>"
                + "<image xlink:href='http://192.0.2.1/b.png' width='50' height='50'/>");
        assertTrue(result.art().pixels().isEmpty());
        assertTrue(result.warnings().contains("Pictures on the web are never downloaded; embed them in the file instead"));
    }

    @Test
    void linkedFilesAreReadOnlyFromBesideTheDrawing(@TempDir Path folder) throws IOException {
        Path drawing = folder.resolve("art");
        Files.createDirectories(drawing.resolve("pictures"));
        Files.write(drawing.resolve("pictures/inside file.png"), png(4, 4));
        Files.write(folder.resolve("outside.png"), png(4, 4));
        Path svg = drawing.resolve("drawing.svg");
        Files.writeString(svg, SVG + "<image href='pictures/inside%20file.png' width='50' height='50'/></svg>");
        DecalArt art = SvgDecalImporter.read(svg, false).art();
        assertEquals(2, art.pixels().size());

        Files.writeString(svg, SVG + "<rect width='1' height='1'/><image href='../outside.png' width='50' height='50'/>"
                + "<image href='" + folder.resolve("outside.png").toAbsolutePath() + "' width='50' height='50'/></svg>");
        SvgDecalImporter.Result refused = SvgDecalImporter.read(svg, false);
        assertTrue(refused.art().pixels().isEmpty());
        assertTrue(refused.warnings().stream().anyMatch(w -> w.contains("outside the drawing's own folder")));

        assertThrows(IOException.class, () -> SvgImages.linked("../outside.png", drawing));
        assertEquals(drawing.resolve("pictures/inside file.png").toRealPath(),
                SvgImages.linked("pictures/inside%20file.png", drawing).toRealPath());
    }

    @Test
    void linkedPicturesNeedAFile() throws IOException {
        SvgDecalImporter.Result result = read("<rect width='1' height='1'/><image href='a.png' width='5' height='5'/>");
        assertTrue(result.warnings().contains("Linked pictures can only be read from a file on this computer"));
    }

    // Placement

    private static Point2D at(AffineTransform transform, double x, double y) {
        return transform.transform(new Point2D.Double(x, y), null);
    }

    @Test
    void preserveAspectRatioPlacesThePicture() {
        // A 100 by 50 picture in a 100 square at 10, 20
        AffineTransform meet = SvgImages.placement(10, 20, 100, 100, 100, 50, null);
        assertEquals(new Point2D.Double(10, 45), at(meet, 0, 0));
        assertEquals(new Point2D.Double(110, 95), at(meet, 100, 50));

        AffineTransform slice = SvgImages.placement(10, 20, 100, 100, 100, 50, "xMidYMid slice");
        assertEquals(new Point2D.Double(-40, 20), at(slice, 0, 0));
        assertEquals(new Point2D.Double(160, 120), at(slice, 100, 50));

        AffineTransform none = SvgImages.placement(10, 20, 100, 100, 100, 50, "none");
        assertEquals(new Point2D.Double(110, 120), at(none, 100, 50));

        AffineTransform corner = SvgImages.placement(0, 0, 100, 100, 100, 50, "xMinYMax meet");
        assertEquals(new Point2D.Double(0, 50), at(corner, 0, 0));

        AffineTransform tall = SvgImages.placement(0, 0, 100, 100, 50, 100, "xMaxYMin meet");
        assertEquals(new Point2D.Double(50, 0), at(tall, 0, 0));
    }

    @Test
    void aPictureLandsWhereItsAttributesAndTransformsSay() throws IOException {
        String uri = dataUri(png(20, 10));
        // Fitted whole in a 40 square: 40 by 20, centred down; then moved by the group
        DecalArt art = read("<g transform='translate(50 0)'><image x='0' y='0' width='40' height='40' href='" + uri
                + "'/></g>").art();
        double[] box = bounds(art.pixels());
        assertEquals(0.5, box[0], 1e-3);
        assertEquals(0.1, box[1], 1e-3);
        assertEquals(0.9, box[2], 1e-3);
        assertEquals(0.3, box[3], 1e-3);
        assertEquals(0.4 * 0.2 / 2, area(List.of(pixelLayer(art, 0xFF0000))), 1e-4);
        assertEquals(0.4 * 0.2 / 2, area(List.of(pixelLayer(art, 0x0000FF))), 1e-4);

        // Sliced into a square: the middle of the picture fills it, clipped to it
        DecalArt sliced = read("<image width='100' height='100' preserveAspectRatio='xMidYMid slice' href='" + uri
                + "'/>").art();
        assertEquals(1, area(sliced.pixels()), 1e-3);
        assertEquals(0.5, area(List.of(pixelLayer(sliced, 0xFF0000))), 1e-3);

        // Rotated a quarter turn about the middle, used twice by reference
        DecalArt turned = read("<defs><image id='p' width='100' height='50' href='" + uri + "'/></defs>"
                + "<use href='#p' transform='rotate(90 50 50)'/>").art();
        double[] turnedBox = bounds(turned.pixels());
        assertEquals(0.5, turnedBox[0], 1e-3);
        assertEquals(1, turnedBox[2], 1e-3);
        assertEquals(0, turnedBox[1], 1e-3);
        assertEquals(1, turnedBox[3], 1e-3);
    }

    @Test
    void aClipPathAndOpacityApplyToPictures() throws IOException {
        String uri = dataUri(png(10, 10));
        DecalArt clipped = read("<clipPath id='c'><rect width='50' height='100'/></clipPath>"
                + "<image width='100' height='100' clip-path='url(#c)' href='" + uri + "'/>").art();
        assertEquals(0.5, area(clipped.pixels()), 1e-3);
        assertEquals(1, clipped.pixels().size());

        assertThrows(IOException.class, () -> read("<image width='100' height='100' opacity='0.3' href='" + uri
                + "'/>"));
        assertEquals(1, area(read("<image width='100' height='100' opacity='0.7' href='" + uri + "'/>").art()
                .pixels()), 1e-3);
    }

    @Test
    void picturesAndShapesNeverOverlap() throws IOException {
        String uri = dataUri(png(10, 10));
        // A shape over the picture cuts it
        DecalArt over = read("<image width='100' height='100' href='" + uri + "'/>"
                + "<rect x='25' y='25' width='50' height='50' fill='lime'/>").art();
        assertEquals(0.75, area(over.pixels()), 2e-3);
        assertEquals(0.25, area(over.layers()), 2e-3);
        // A picture over a shape cuts that
        DecalArt under = read("<rect width='100' height='100' fill='lime'/>"
                + "<image x='50' width='50' height='100' preserveAspectRatio='none' href='" + uri + "'/>").art();
        assertEquals(0.5, area(under.pixels()), 2e-3);
        assertEquals(0.5, area(under.layers()), 2e-3);
    }

    // Size caps

    @Test
    void largePicturesAreDecodedSmallAndPaintedSmaller() throws IOException {
        SvgImages.Pixels pixels = SvgImages.decode(png(2500, 1250));
        assertTrue(pixels.width() <= SvgImages.MAX_DECODED && pixels.height() <= SvgImages.MAX_DECODED);
        assertEquals(2.0, pixels.width() / (double) pixels.height(), 0.01);

        assertArrayEquals(new int[] { 16, 8 }, SvgImages.cellGrid(16, 8, SvgImages.MAX_CELLS_TOTAL));
        assertArrayEquals(new int[] { 256, 128 }, SvgImages.cellGrid(1024, 512, SvgImages.MAX_CELLS_TOTAL));
        int[] squeezed = SvgImages.cellGrid(1000, 1000, 400);
        assertTrue(squeezed[0] * squeezed[1] <= 400);
        assertNull(SvgImages.cellGrid(10, 10, 0));

        SvgDecalImporter.Result result = read("<image width='100' height='50' href='" + dataUri(png(2048, 1024))
                + "'/>");
        assertEquals(0.5, area(result.art().pixels()), 1e-3);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("cells across")));
        assertTrue(result.art().triangleCount() < 1000, result.art().toString());
    }

    @Test
    void scalingAveragesAndIgnoresTransparentColour() {
        SvgImages.Pixels source = new SvgImages.Pixels(2, 2,
                new int[] { 0xFFFF0000, 0x0000FF00, 0xFFFF0000, 0xFF0000FF });
        SvgImages.Pixels one = SvgImages.scaled(source, 1, 1);
        int argb = one.argb()[0];
        assertEquals(191, argb >>> 24, 1);
        assertEquals(0xAA, (argb >> 16) & 0xFF, 1);
        assertEquals(0, (argb >> 8) & 0xFF);
        assertEquals(0x55, argb & 0xFF, 1);
    }

    @Test
    void pixelArtKeepsItsColoursAndPhotosAreReduced() {
        SvgImages.Quantised art = SvgImages.quantise(new SvgImages.Pixels(3, 1,
                new int[] { 0xFF123456, 0x10FFFFFF, 0xFF654321 }), 1, 64);
        assertArrayEquals(new int[] { 0x123456, 0x654321 }, art.palette());
        assertArrayEquals(new int[] { 0, -1, 1 }, art.index());
        assertEquals(2, art.opaque());

        int[] noise = new int[64 * 64];
        java.util.Random random = new java.util.Random(1);
        for (int i = 0; i < noise.length; i++)
            noise[i] = 0xFF000000 | random.nextInt(0x1000000);
        SvgImages.Quantised photo = SvgImages.quantise(new SvgImages.Pixels(64, 64, noise), 1, 16);
        assertEquals(16, photo.palette().length);
        for (int index : photo.index())
            assertTrue(index >= 0 && index < 16);
    }

    @Test
    void rectanglesCoverEveryPaintedCellOnce() {
        int[] index = { 0, 0, 1, -1, 0, 0, 1, 1, -1, -1, 1, 1 };
        List<int[]> rects = SvgImages.rects(index, 4, 3);
        int[] covered = new int[index.length];
        for (int[] rect : rects)
            for (int y = rect[1]; y < rect[1] + rect[3]; y++)
                for (int x = rect[0]; x < rect[0] + rect[2]; x++) {
                    covered[y * 4 + x]++;
                    assertEquals(index[y * 4 + x], rect[4]);
                }
        for (int i = 0; i < index.length; i++)
            assertEquals(index[i] >= 0 ? 1 : 0, covered[i]);
        // The top left two by two block is one rectangle
        assertTrue(rects.stream().anyMatch(r -> r[0] == 0 && r[1] == 0 && r[2] == 2 && r[3] == 2));
    }

    @Test
    void otherFormatsDecode() throws IOException {
        BufferedImage rgb = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
        for (String format : new String[] { "jpeg", "bmp", "gif" }) {
            SvgImages.Pixels pixels = SvgImages.decode(encode(rgb, format));
            assertEquals(8, pixels.width(), format);
            assertEquals(4, pixels.height(), format);
        }
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
        IOException refused = assertThrows(IOException.class, () -> SvgImages.decode(webp));
        assertTrue(refused.getMessage().contains("WebP"));
    }

    // Dynamic content

    @Test
    void dynamicContentIsTakenOutAndTheStaticDrawingKept() throws IOException {
        SvgDecalImporter.Result result = read("<script>document.body.remove()</script>"
                + "<rect x='0' width='50' height='100' fill='red' onclick='go()' onload='go()'>"
                + "<animate attributeName='x' from='0' to='50' dur='1s' fill='freeze'/>"
                + "<set attributeName='fill' to='blue'/></rect>"
                + "<animateTransform attributeName='transform' type='rotate'/>"
                + "<foreignObject width='100' height='100'><div " + XHTML + ">"
                + "<iframe src='https://example.com'/><video src='a.mp4'/><canvas/><button>Press</button>"
                + "<script>x()</script></div></foreignObject>");
        assertEquals(1, result.art().layers().size());
        DecalArt.Layer red = result.art().layers().get(0);
        assertEquals(0xFF0000, red.colour() & 0xFFFFFF);
        assertEquals(0.5, bounds(List.of(red))[2], 1e-3);
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("Scripts, animations")));
    }

    @Test
    void strippingFindsHandlersAnimationsAndFrames() throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        org.w3c.dom.Document document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                (SVG + "<rect onclick='a()' width='1' height='1'/><animate/><iframe/></svg>")
                        .getBytes(StandardCharsets.UTF_8)));
        assertTrue(SvgDecalImporter.stripDynamic(document.getDocumentElement()));
        assertEquals(0, document.getElementsByTagNameNS("*", "animate").getLength());
        assertEquals(0, document.getElementsByTagNameNS("*", "iframe").getLength());
        assertFalse(((org.w3c.dom.Element) document.getElementsByTagNameNS("*", "rect").item(0))
                .hasAttribute("onclick"));

        org.w3c.dom.Document plain = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                (SVG + "<rect width='1' height='1'/></svg>").getBytes(StandardCharsets.UTF_8)));
        assertFalse(SvgDecalImporter.stripDynamic(plain.getDocumentElement()));
    }

    // HTML

    @Test
    void htmlTextIsLaidOutInsideItsBox() throws IOException {
        DecalArt art = read("<foreignObject x='10' y='20' width='80' height='60'><div " + XHTML
                + " style='font-size:10px;color:#00f'>Hello there</div></foreignObject>").art();
        assertEquals(1, art.layers().size());
        assertEquals(0x0000FF, art.layers().get(0).colour() & 0xFFFFFF);
        double[] box = bounds(art.layers());
        assertTrue(box[0] >= 0.1 - 1e-3 && box[0] < 0.12, "left " + box[0]);
        assertTrue(box[1] >= 0.2 && box[1] < 0.24, "top " + box[1]);
        assertTrue(box[3] <= 0.2 + 0.12 + 1e-3, "one line " + box[3]);
        assertTrue(box[2] < 0.9, "right " + box[2]);
    }

    @Test
    void htmlTextWrapsAlignsAndClips() throws IOException {
        String words = "word word word word word word word word word word word word";
        double[] wrapped = bounds(read("<foreignObject width='40' height='100'><p " + XHTML
                + " style='margin:0;font-size:10px'>" + words + "</p></foreignObject>").art().layers());
        assertTrue(wrapped[2] <= 0.4 + 1e-3, "wraps within its width " + wrapped[2]);
        assertTrue(wrapped[3] > 0.3, "more than one line " + wrapped[3]);

        double[] centred = bounds(read("<foreignObject width='100' height='20'><div " + XHTML
                + " style='text-align:center;font-size:10px'>Mid</div></foreignObject>").art().layers());
        assertEquals(0.5, (centred[0] + centred[2]) / 2, 0.02);

        double[] clipped = bounds(read("<foreignObject width='100' height='5'><div " + XHTML
                + " style='font-size:20px'>Tall</div></foreignObject>").art().layers());
        assertTrue(clipped[3] <= 0.05 + 1e-3, "clipped to its box " + clipped[3]);
    }

    @Test
    void htmlBlocksPaintTheirBackgroundsBordersAndLists() throws IOException {
        DecalArt art = read("<foreignObject width='100' height='100'><div " + XHTML
                + " style='background:#0f0;border:5px solid #f00;height:40px;padding:0'>"
                + "<ul style='margin:0;color:#00f'><li>One</li><li>Two</li></ul></div></foreignObject>").art();
        double green = 0;
        double red = 0;
        double blue = 0;
        for (DecalArt.Layer layer : art.layers()) {
            int rgb = layer.colour() & 0xFFFFFF;
            double here = area(List.of(layer));
            if (rgb == 0x00FF00)
                green += here;
            else if (rgb == 0xFF0000)
                red += here;
            else if (rgb == 0x0000FF)
                blue += here;
        }
        // 100 by 50 with a five pixel border all round
        assertEquals(1.0 * 0.5 - 0.9 * 0.4, red, 2e-3);
        assertTrue(green > 0.3 && green < 0.36, "background less the text " + green);
        assertTrue(blue > 0, "list text and bullets");
        assertTrue(bounds(art.layers())[0] < 0.4 && bounds(List.of(art.layers().stream()
                .filter(l -> (l.colour() & 0xFFFFFF) == 0x0000FF).findFirst().orElseThrow()))[0] < 0.45);
    }

    @Test
    void htmlImagesArePictures() throws IOException {
        DecalArt art = read("<foreignObject width='100' height='100'><div " + XHTML + ">"
                + "<img src='" + dataUri(png(4, 4)) + "' width='50' height='50'/></div></foreignObject>").art();
        assertEquals(2, art.pixels().size());
        assertEquals(0.25, area(art.pixels()), 1e-3);
    }

    @Test
    void aSwitchWithHtmlDrawsOnlyTheHtml() throws IOException {
        DecalArt art = read("<switch><foreignObject requiredExtensions='http://www.w3.org/1999/xhtml'"
                + " width='100' height='100'><div " + XHTML + " style='color:#00f'>Label</div></foreignObject>"
                + "<text x='0' y='50' fill='#f00'>Fallback</text></switch>").art();
        assertEquals(1, art.layers().size());
        assertEquals(0x0000FF, art.layers().get(0).colour() & 0xFFFFFF);
    }

    // Background

    @Test
    void aPictureCoveringTheFrameAtTheBottomIsTheBackground() throws IOException {
        String body = "<image width='100' height='100' preserveAspectRatio='none' href='" + dataUri(png(8, 8)) + "'/>"
                + "<circle cx='50' cy='50' r='20' fill='lime'/>";
        DecalArt kept = read(body, false).art();
        assertFalse(kept.pixels().isEmpty());
        DecalArt clear = read(body, true).art();
        assertTrue(clear.pixels().isEmpty());
        assertEquals(1, clear.layers().size());

        // A picture over the artwork is part of it, however large
        DecalArt over = read("<circle cx='50' cy='50' r='20' fill='lime'/><image width='100' height='100'"
                + " preserveAspectRatio='none' href='" + dataUri(png(8, 8)) + "'/>", true).art();
        assertEquals(1, area(over.pixels()), 1e-3);
    }

    // Storage

    @Test
    void picturesTravelInTheSameBlobAndHashTheSameEveryTime() throws Exception {
        DecalArt art = read("<rect width='50' height='50' fill='lime'/><image x='50' width='50' height='50' href='"
                + dataUri(png(6, 6)) + "'/>").art();
        byte[] blob = art.encode();
        assertTrue(DecalArt.isDecal(blob));
        assertEquals("CFDECAL2", new String(blob, 0, 8, StandardCharsets.ISO_8859_1));
        DecalArt back = DecalArt.decode(blob);
        assertEquals(art.layers().size(), back.layers().size());
        assertEquals(art.pixels().size(), back.pixels().size());
        assertEquals(art.triangleCount(), back.triangleCount());
        assertEquals(area(art.pixels()), area(back.pixels()), 1e-4);
        byte[] again = back.encode();
        assertArrayEquals(blob, again);
        assertEquals(hash(blob), hash(again));
        assertTrue(blob.length < 8 * 1024 * 1024);
    }

    @Test
    void aBlobClaimingTooManyColoursIsRefused() throws IOException {
        List<DecalArt.Layer> many = new java.util.ArrayList<>();
        for (int i = 0; i < DecalArt.MAX_LAYERS; i++)
            many.add(new DecalArt.Layer(0xFF000000 | i, new float[6]));
        byte[] blob = new DecalArt("x", 1, 1, many, List.of(new DecalArt.Layer(0xFFFFFFFF, new float[6]))).encode();
        assertThrows(IOException.class, () -> DecalArt.decode(blob));
    }

    /**
     * Hashes of a vector-only drawing as it was stored before pictures and HTML were
     * read, taken from the importer as it then was: the same drawing must still come
     * to exactly the same bytes.
     */
    @Test
    void vectorOnlyDrawingsStoreExactlyWhatTheyDidBefore() throws Exception {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 40 30'><rect width='40' height='30' fill='#eee'/>"
                + "<circle cx='12' cy='15' r='8' fill='#c00' stroke='#000' stroke-width='2'/>"
                + "<path d='M20 5 L35 25 L20 25 Z' fill='blue' opacity='0.8' transform='rotate(10 25 15)'/>"
                + "<clipPath id='c'><rect x='0' y='0' width='20' height='30'/></clipPath>"
                + "<ellipse cx='20' cy='20' rx='10' ry='4' fill='green' clip-path='url(#c)'/>"
                + "<script>alert(1)</script><rect x='2' y='2' width='5' height='5' fill='orange' onclick='x()'>"
                + "<animate attributeName='x' from='2' to='30' dur='2s'/></rect></svg>";
        byte[] opaque = SvgDecalImporter.parse(svg.getBytes(StandardCharsets.UTF_8), "golden", false).art().encode();
        byte[] clear = SvgDecalImporter.parse(svg.getBytes(StandardCharsets.UTF_8), "golden", true).art().encode();
        assertEquals("CFDECAL1", new String(opaque, 0, 8, StandardCharsets.ISO_8859_1));
        assertEquals(2347, opaque.length);
        assertEquals("62e6b3f538bb74d00b6fb2d94713bc7a", hash(opaque));
        assertEquals(1816, clear.length);
        assertEquals("bd3b7b46b88414603ed92a1988f3e612", hash(clear));
        assertNotEquals(hash(opaque), hash(clear));
    }
}
