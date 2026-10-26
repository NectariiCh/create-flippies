package com.nectariich.create_flippies.content.decal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * A picture that can be painted onto a panel: flat colours, already cut into
 * triangles, in a box of its own.
 *
 * What is stored is the finished mesh and not the SVG it came from, and that is
 * the decision everything else follows from. An SVG is a language (styles,
 * transforms, strokes, overlapping shapes painted in order), and every client
 * that drew one would have to interpret all of it the same way, every time the
 * chunk it is in is rebuilt. Interpreting it once, on the client that imported it,
 * and keeping only the answer means the world holds something no client can read
 * differently, the server can check by decoding it, and a chunk rebuild costs a
 * lookup and some arithmetic.
 *
 * The answer is a set of <em>layers</em>, one per colour, each a list of
 * triangles that do not overlap anything else in the picture. Overlap was
 * resolved at import by painting in order and keeping only what shows (see the
 * importer), which is what lets the whole picture lie in one plane without any
 * two of its parts fighting over the same pixel.
 *
 * Coordinates are fractions of the picture's own box, x across and y down as an
 * SVG has them, held as sixteen-bit integers. Sixty-five thousand steps across a
 * picture painted at most thirty-two blocks wide is a step of a hundred and
 * twenty-eighth of a pixel, which is finer than anything a panel can show, and it
 * halves the size of a detailed picture against floats.
 *
 * The blob is stored and fetched through the same content-addressed store as
 * imported models, under the same hash, and is told apart from them by its
 * {@link #MAGIC}.
 *
 * <h2>Pictures inside a drawing</h2>
 *
 * A raster picture embedded in the SVG arrives as {@link #pixels}: more layers of
 * the same kind, one per colour of the picture's reduced palette, each its cells
 * as rectangles cut in two. They obey the same rule as the rest, overlapping
 * nothing, and are drawn the same way. They are kept apart only so that a stamped
 * arrow can leave them out, since raising a photo cell by cell would make thousands
 * of walls of nothing anyone could read. A drawing without any is written exactly
 * as it was before pictures were read, under {@link #MAGIC}; one with some is
 * written under {@link #PIXEL_MAGIC} with the pixel layers after the others.
 *
 * <h2>Shaded layers</h2>
 *
 * A picture imported for a display keeps its gradients, which a flat colour per layer
 * cannot: such a layer carries a colour at each of its vertices as well
 * ({@link Layer#shades}), cut finely enough that blending across each triangle follows
 * the gradient, and its own colour is the average a flat drawing of it would use. Only
 * a picture with at least one is written under {@link #SHADED_MAGIC}, so every other
 * picture is stored exactly as before and the panel decal and the arrow keep drawing
 * any picture flat.
 */
public final class DecalArt {

    /** What every blob of this kind starts with, uncompressed, so it can be recognised without inflating it. */
    private static final byte[] MAGIC = { 'C', 'F', 'D', 'E', 'C', 'A', 'L', '1' };

    /** What a blob that also holds {@link #pixels} starts with. */
    private static final byte[] PIXEL_MAGIC = { 'C', 'F', 'D', 'E', 'C', 'A', 'L', '2' };

    /**
     * What a blob with a colour at every vertex of some layer starts with: a display's
     * picture with its gradients kept. Pixel layers always follow, perhaps none.
     */
    private static final byte[] SHADED_MAGIC = { 'C', 'F', 'D', 'E', 'C', 'A', 'L', '3' };

    /**
     * The most triangles one picture may hold. A detailed logo is a few thousand;
     * this is room for a dense illustration and a ceiling on what a chunk rebuild
     * can be asked to clip.
     */
    public static final int MAX_TRIANGLES = 200_000;

    /**
     * The most triangles a picture drawn on a display may hold, its shading included:
     * a display draws its elements every frame rather than once into a chunk.
     */
    public static final int MAX_DISPLAY_TRIANGLES = 20_000;

    /** The most colours one picture may use, its pixel layers included; a gradient flattened to bands is the usual way to reach it. */
    public static final int MAX_LAYERS = 1024;

    /** The longest name a picture keeps, which is what the tool shows the player. */
    public static final int MAX_NAME = 48;

    /** Steps of the coordinate lattice, which is the full unsigned short. */
    private static final int STEPS = 0xFFFF;

    /** How far a blob may inflate, checked as it is read so a small upload cannot become a large one. */
    private static final int MAX_INFLATED = 16 + 4 * MAX_NAME + 16 + MAX_LAYERS * 9 + MAX_TRIANGLES * 24;

    /**
     * One colour of the picture, or one shaded region of it.
     *
     * @param colour    opaque ARGB; for a shaded layer its average, which is what a
     *                  panel decal or an arrow, drawn flat, paints it in
     * @param triangles three vertices per triangle, each an x and a y, as fractions of
     *                  the picture's box
     * @param shades    null for a flat layer, else an ARGB colour for every vertex,
     *                  which is how a gradient is kept: blended across each triangle
     */
    public record Layer(int colour, float[] triangles, int[] shades) {

        public Layer(int colour, float[] triangles) {
            this(colour, triangles, null);
        }

        public int triangleCount() {
            return triangles.length / 6;
        }
    }

    private final String name;
    private final float width;
    private final float height;
    private final List<Layer> layers;
    private final List<Layer> pixels;

    public DecalArt(String name, float width, float height, List<Layer> layers) {
        this(name, width, height, layers, List.of());
    }

    public DecalArt(String name, float width, float height, List<Layer> layers, List<Layer> pixels) {
        this.name = sanitiseName(name);
        this.width = width;
        this.height = height;
        this.layers = List.copyOf(layers);
        this.pixels = List.copyOf(pixels);
    }

    public String name() {
        return name;
    }

    /** The picture's width, in whatever units it was drawn in; only its ratio to {@link #height} means anything. */
    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public List<Layer> layers() {
        return layers;
    }

    /** The layers that came from raster pictures, painted with the rest; see the class comment. */
    public List<Layer> pixels() {
        return pixels;
    }

    public int triangleCount() {
        int count = 0;
        for (Layer layer : layers)
            count += layer.triangleCount();
        for (Layer layer : pixels)
            count += layer.triangleCount();
        return count;
    }

    /** Whether a stored blob is one of these rather than a model or a font. */
    public static boolean isDecal(byte[] blob) {
        return startsWith(blob, MAGIC) || startsWith(blob, PIXEL_MAGIC) || startsWith(blob, SHADED_MAGIC);
    }

    private boolean shaded() {
        for (Layer layer : layers)
            if (layer.shades() != null)
                return true;
        return false;
    }

    private static boolean startsWith(byte[] blob, byte[] magic) {
        if (blob == null || blob.length < magic.length)
            return false;
        for (int i = 0; i < magic.length; i++)
            if (blob[i] != magic[i])
                return false;
        return true;
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean shaded = shaded();
        bytes.write(shaded ? SHADED_MAGIC : pixels.isEmpty() ? MAGIC : PIXEL_MAGIC);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, deflater))) {
            out.writeUTF(name);
            out.writeFloat(width);
            out.writeFloat(height);
            write(layers, out, shaded);
            if (shaded || !pixels.isEmpty())
                write(pixels, out, shaded);
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    private static void write(List<Layer> layers, DataOutputStream out, boolean shaded) throws IOException {
        out.writeInt(layers.size());
        for (Layer layer : layers) {
            out.writeInt(layer.colour());
            out.writeInt(layer.triangleCount());
            for (float coordinate : layer.triangles())
                out.writeShort(quantise(coordinate));
            if (!shaded)
                continue;
            out.writeBoolean(layer.shades() != null);
            if (layer.shades() != null)
                for (int i = 0; i < layer.triangleCount() * 3; i++)
                    out.writeInt(layer.shades()[i]);
        }
    }

    /**
     * Reads a blob back, or throws if it is not a picture this build can draw.
     *
     * Everything a client could lie about is checked here, because this is also how
     * the server decides whether an upload is worth keeping: the counts against
     * their ceilings, the box against being empty or not a number, and the inflated
     * size against a budget, so that a few kilobytes of upload cannot become
     * gigabytes of anything.
     */
    public static DecalArt decode(byte[] blob) throws IOException {
        if (!isDecal(blob))
            throw new IOException("Not a decal");
        InputStream raw = new InflaterInputStream(
                new ByteArrayInputStream(blob, MAGIC.length, blob.length - MAGIC.length));
        try (DataInputStream in = new DataInputStream(new Budget(raw, MAX_INFLATED))) {
            String name = in.readUTF();
            float width = in.readFloat();
            float height = in.readFloat();
            if (!(width > 0) || !(height > 0) || !Float.isFinite(width) || !Float.isFinite(height))
                throw new IOException("Decal has no size");
            int[] totals = new int[2];
            boolean shaded = startsWith(blob, SHADED_MAGIC);
            List<Layer> layers = read(in, totals, shaded);
            List<Layer> pixels = shaded || startsWith(blob, PIXEL_MAGIC) ? read(in, totals, shaded) : List.of();
            return new DecalArt(name, width, height, layers, pixels);
        }
    }

    /** One list of layers, counting colours and triangles into {@code totals} against their ceilings. */
    private static List<Layer> read(DataInputStream in, int[] totals, boolean shaded) throws IOException {
        int layerCount = in.readInt();
        if (layerCount < 0 || layerCount > MAX_LAYERS - totals[0])
            throw new IOException("Decal has " + (totals[0] + layerCount) + " colours");
        totals[0] += layerCount;
        List<Layer> layers = new ArrayList<>(layerCount);
        for (int i = 0; i < layerCount; i++) {
            int colour = in.readInt() | 0xFF000000;
            int count = in.readInt();
            if (count < 0 || count > MAX_TRIANGLES - totals[1])
                throw new IOException("Decal has too many triangles");
            totals[1] += count;
            float[] triangles = new float[count * 6];
            for (int j = 0; j < triangles.length; j++)
                triangles[j] = (in.readShort() & 0xFFFF) / (float) STEPS;
            int[] shades = null;
            if (shaded && in.readBoolean()) {
                shades = new int[count * 3];
                for (int j = 0; j < shades.length; j++)
                    shades[j] = in.readInt();
            }
            layers.add(new Layer(colour, triangles, shades));
        }
        return layers;
    }

    /** A picture's name as it is safe to keep and show: printable, and not too long. */
    public static String sanitiseName(String name) {
        if (name == null)
            return "";
        String cleaned = name.replaceAll("[^\\p{L}\\p{N} ._()\\-]", "").trim();
        return cleaned.length() > MAX_NAME ? cleaned.substring(0, MAX_NAME) : cleaned;
    }

    private static int quantise(float fraction) {
        float clamped = fraction < 0 ? 0 : fraction > 1 ? 1 : fraction;
        return Math.round(clamped * STEPS);
    }

    /** A stream that refuses to give up more than a set number of bytes. */
    private static final class Budget extends InputStream {

        private final InputStream in;
        private long left;

        Budget(InputStream in, long budget) {
            this.in = in;
            this.left = budget;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0)
                throw new IOException("Decal inflates past its budget");
            int read = in.read();
            if (read >= 0)
                left--;
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0)
                throw new IOException("Decal inflates past its budget");
            int read = in.read(buffer, offset, (int) Math.min(length, left));
            if (read > 0)
                left -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    @Override
    public String toString() {
        return "DecalArt[" + name + ", " + width + "x" + height + ", " + (layers.size() + pixels.size())
                + " colours, " + triangleCount() + " triangles]";
    }
}
