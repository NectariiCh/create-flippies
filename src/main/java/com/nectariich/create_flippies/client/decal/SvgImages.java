package com.nectariich.create_flippies.client.decal;

import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * The raster pictures an SVG carries, read as far as a panel can paint them.
 *
 * A panel has no textures of its own to show a picture with: paint is baked into
 * the chunk mesh as flat coloured triangles against one white square of the block
 * atlas (see {@code PanelPaintQuads}). So a picture is painted the way the rest of
 * the drawing is, as colour: it is scaled down to a grid of cells no finer than a
 * panel can show, its colours are reduced to a small palette, and each run of cells
 * of one colour becomes a rectangle. What is stored and sent is then the same kind
 * of thing as for any drawing, checked by the server in the same way, and drawn by
 * the same code with the same clipping, lighting and tint.
 *
 * Only pictures embedded in the file as {@code data:} URIs, or kept in files beside
 * it, are read. Nothing is ever fetched from the network, and a file elsewhere on
 * the computer is never read, since a drawing from anywhere could otherwise name a
 * private picture and have it uploaded to the server.
 *
 * Runs on any thread and touches nothing of the game's.
 */
final class SvgImages {

    /** The largest side a picture is decoded at; a larger one is subsampled while it is read. */
    static final int MAX_DECODED = 1024;

    /** The largest side a picture may claim at all, which keeps a tiny file from asking for gigapixels. */
    static final int MAX_SOURCE_SIDE = 16_384;

    /** The most cells across either side of one picture. */
    static final int MAX_CELLS_SIDE = 256;

    /** The most cells all the pictures of one drawing may have between them. */
    static final int MAX_CELLS_TOTAL = 65_536;

    /** The most colours one picture is reduced to. */
    static final int MAX_COLOURS = 64;

    /** The most colours all the pictures of one drawing may use between them. */
    static final int MAX_COLOURS_TOTAL = 512;

    /** The most different pictures one drawing may hold. */
    static final int MAX_PICTURES = 32;

    /** The largest picture file read from beside the drawing. */
    static final long MAX_LINKED_BYTES = 4L * 1024 * 1024;

    /** The most bytes of picture files read from beside the drawing in all. */
    static final long MAX_LINKED_TOTAL = 16L * 1024 * 1024;

    /** Alpha, out of 255, at which a cell counts as painted: half, as for the rest of the drawing. */
    private static final double VISIBLE = 127.5;

    /** A decoded picture: ARGB, not premultiplied, a row at a time. */
    record Pixels(int width, int height, int[] argb) {}

    /**
     * A picture reduced to a palette: per cell the palette index, or minus one where
     * the cell is not painted.
     */
    record Quantised(int width, int height, int[] index, int[] palette, int opaque) {}

    private SvgImages() {}

    // References

    /** Whether a reference is a {@code data:} URI. */
    static boolean isData(String href) {
        return href.trim().regionMatches(true, 0, "data:", 0, 5);
    }

    /**
     * Whether a reference names anything that is not a file on this computer: a URL
     * with a scheme (other than {@code data:} and {@code file:}, and other than a
     * single letter, which is a Windows drive), or one starting {@code //}, which is
     * a URL on whatever host the page came from.
     */
    static boolean isRemote(String href) {
        String text = href.trim();
        if (text.startsWith("//") || text.startsWith("\\\\"))
            return true;
        int colon = text.indexOf(':');
        if (colon <= 1 || !text.substring(0, colon).matches("[A-Za-z][A-Za-z0-9+.-]*"))
            return false;
        String scheme = text.substring(0, colon).toLowerCase(Locale.ROOT);
        return !scheme.equals("data") && !scheme.equals("file");
    }

    /** The bytes a {@code data:} URI holds, base64 or percent-encoded. */
    static byte[] dataUri(String uri) throws IOException {
        String text = uri.trim();
        if (!isData(text))
            throw new IOException("Not a data URI");
        int comma = text.indexOf(',');
        if (comma < 0)
            throw new IOException("An embedded image has no data");
        String header = text.substring(5, comma).trim();
        String data = text.substring(comma + 1);
        String type = header.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.equals("image/svg+xml"))
            throw new IOException("SVG pictures inside an SVG are not imported");
        boolean base64 = false;
        for (String part : header.split(";"))
            if (part.trim().equalsIgnoreCase("base64"))
                base64 = true;
        if (!base64)
            return percentDecoded(data);
        if (data.indexOf('%') >= 0)
            data = new String(percentDecoded(data), StandardCharsets.ISO_8859_1);
        String cleaned = data.replaceAll("\\s+", "");
        try {
            boolean urlSafe = cleaned.indexOf('-') >= 0 || cleaned.indexOf('_') >= 0;
            return (urlSafe ? Base64.getUrlDecoder() : Base64.getDecoder()).decode(cleaned);
        } catch (IllegalArgumentException e) {
            throw new IOException("An embedded image's data is not valid base64");
        }
    }

    private static byte[] percentDecoded(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '%') {
                out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                continue;
            }
            if (i + 2 >= text.length())
                throw new IOException("An embedded image's data is cut short");
            int high = Character.digit(text.charAt(i + 1), 16);
            int low = Character.digit(text.charAt(i + 2), 16);
            if (high < 0 || low < 0)
                throw new IOException("An embedded image's data has a bad escape");
            out.write(high << 4 | low);
            i += 2;
        }
        return out.toByteArray();
    }

    /**
     * The file a relative reference names, beside the drawing or in a folder under
     * it. Anything that leads out of that folder, by {@code ..}, an absolute path or
     * a link, is refused.
     */
    static Path linked(String href, Path folder) throws IOException {
        String text = href.trim();
        Path named;
        try {
            if (text.regionMatches(true, 0, "file:", 0, 5)) {
                named = Path.of(URI.create(text));
            } else {
                int cut = text.length();
                for (char stop : new char[] { '?', '#' })
                    if (text.indexOf(stop) >= 0)
                        cut = Math.min(cut, text.indexOf(stop));
                named = Path.of(new String(percentDecoded(text.substring(0, cut)), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("An image's file name could not be read");
        }
        Path base = folder.toAbsolutePath().normalize();
        Path resolved = (named.isAbsolute() ? named : base.resolve(named)).normalize();
        if (!resolved.startsWith(base))
            throw new IOException("Images outside the drawing's own folder are not read");
        if (!Files.isRegularFile(resolved))
            throw new IOException("A linked image file is missing");
        if (!resolved.toRealPath().startsWith(base.toRealPath()))
            throw new IOException("Images outside the drawing's own folder are not read");
        if (Files.size(resolved) > MAX_LINKED_BYTES)
            throw new IOException("A linked image is larger than 4 MB");
        return resolved;
    }

    // Decoding

    /**
     * A picture file as pixels: PNG, JPEG, GIF (its first frame), BMP, or whatever
     * else this Java can read. One larger than {@link #MAX_DECODED} on a side is
     * subsampled while it is decoded, so a large photo never costs its full size in
     * memory.
     */
    static Pixels decode(byte[] bytes) throws IOException {
        if (bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.ISO_8859_1).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.ISO_8859_1).equals("WEBP"))
            throw new IOException("WebP images cannot be read; convert them to PNG");
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext())
                throw new IOException("An embedded image is in a format that cannot be read");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0)
                    throw new IOException("An embedded image is empty");
                if (width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE)
                    throw new IOException("An embedded image is more than " + MAX_SOURCE_SIDE + " pixels across");
                ImageReadParam param = reader.getDefaultReadParam();
                int step = (Math.max(width, height) + MAX_DECODED - 1) / MAX_DECODED;
                if (step > 1)
                    param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage image = reader.read(0, param);
                int w = image.getWidth();
                int h = image.getHeight();
                return new Pixels(w, h, image.getRGB(0, 0, w, h, null, 0, w));
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("An embedded image could not be decoded");
        }
    }

    /** Whether a picture file holds more than one frame, which is an animation. */
    static boolean animated(byte[] bytes) {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext())
                return false;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, false, true);
                return reader.getNumImages(true) > 1;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // Placement

    /**
     * Where a picture's pixels go in the coordinates of the element that places it:
     * the transform that takes pixel (0, 0) to (1, 1) of a picture {@code iw} by
     * {@code ih} into the viewport at {@code x, y}, {@code w} by {@code h}, as
     * {@code preserveAspectRatio} says (by default centred and fitted whole). The
     * viewport also clips the picture, which matters for {@code slice}.
     */
    static AffineTransform placement(double x, double y, double w, double h, double iw, double ih, String aspect) {
        AffineTransform transform = AffineTransform.getTranslateInstance(x, y);
        double scaleX = w / iw;
        double scaleY = h / ih;
        String mode = aspect == null ? "xmidymid meet" : aspect.trim().toLowerCase(Locale.ROOT);
        if (mode.startsWith("defer"))
            mode = mode.substring(5).trim();
        if (!mode.startsWith("none")) {
            double scale = mode.contains("slice") ? Math.max(scaleX, scaleY) : Math.min(scaleX, scaleY);
            double alignX = mode.contains("xmin") ? 0 : mode.contains("xmax") ? 1 : 0.5;
            double alignY = mode.contains("ymin") ? 0 : mode.contains("ymax") ? 1 : 0.5;
            transform.translate((w - iw * scale) * alignX, (h - ih * scale) * alignY);
            scaleX = scale;
            scaleY = scale;
        }
        transform.scale(scaleX, scaleY);
        return transform;
    }

    /**
     * How many cells across and down a picture is painted in: its own pixels where
     * there are few enough, else fewer, keeping its shape, within
     * {@link #MAX_CELLS_SIDE} and the {@code budget} of cells left to the drawing.
     * Null when the budget is spent.
     */
    static int[] cellGrid(int width, int height, int budget) {
        if (budget <= 0)
            return null;
        double scale = Math.min(1, MAX_CELLS_SIDE / (double) Math.max(width, height));
        scale = Math.min(scale, Math.sqrt(budget / ((double) width * height)));
        int across = Math.max(1, (int) Math.floor(width * scale));
        int down = Math.max(1, (int) Math.floor(height * scale));
        return across * down > budget ? null : new int[] { across, down };
    }

    /**
     * A picture at a smaller size, each new pixel the average of what it covers,
     * weighted by alpha so that a transparent pixel's colour does not bleed into its
     * neighbours.
     */
    static Pixels scaled(Pixels source, int width, int height) {
        if (width == source.width() && height == source.height())
            return source;
        int[] out = new int[width * height];
        double stepX = source.width() / (double) width;
        double stepY = source.height() / (double) height;
        for (int ty = 0; ty < height; ty++) {
            double y0 = ty * stepY;
            double y1 = y0 + stepY;
            for (int tx = 0; tx < width; tx++) {
                double x0 = tx * stepX;
                double x1 = x0 + stepX;
                double a = 0;
                double r = 0;
                double g = 0;
                double b = 0;
                double covered = 0;
                for (int sy = (int) y0; sy < Math.min(source.height(), Math.ceil(y1)); sy++) {
                    double wy = Math.min(y1, sy + 1) - Math.max(y0, sy);
                    for (int sx = (int) x0; sx < Math.min(source.width(), Math.ceil(x1)); sx++) {
                        double weight = wy * (Math.min(x1, sx + 1) - Math.max(x0, sx));
                        if (weight <= 0)
                            continue;
                        int argb = source.argb()[sy * source.width() + sx];
                        double alpha = (argb >>> 24) * weight;
                        a += alpha;
                        r += ((argb >> 16) & 0xFF) * alpha;
                        g += ((argb >> 8) & 0xFF) * alpha;
                        b += (argb & 0xFF) * alpha;
                        covered += weight;
                    }
                }
                if (a <= 0 || covered <= 0)
                    continue;
                int alpha = (int) Math.round(a / covered);
                out[ty * width + tx] = alpha << 24 | (int) Math.round(r / a) << 16 | (int) Math.round(g / a) << 8
                        | (int) Math.round(b / a);
            }
        }
        return new Pixels(width, height, out);
    }

    // Colours

    /**
     * A picture's cells reduced to at most {@code colours} colours, its alpha first
     * multiplied by {@code opacity}. A cell at least half opaque is painted in its
     * colour as it is, not mixed with what lies under it; any other is left out.
     *
     * A picture with no more colours than that, which is any pixel art, keeps them
     * exactly. Any other is cut down by median cut: the colours are split, again and
     * again, at the middle of whichever group spans most of one channel, and each
     * group is painted in its average.
     */
    static Quantised quantise(Pixels cells, double opacity, int colours) {
        colours = Math.max(1, colours);
        int count = cells.width() * cells.height();
        int[] index = new int[count];
        Arrays.fill(index, -1);
        int[] order = new int[count];
        int opaque = 0;
        Map<Integer, Integer> exact = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int argb = cells.argb()[i];
            if ((argb >>> 24) * opacity < VISIBLE)
                continue;
            order[opaque++] = i;
            if (exact.size() <= colours)
                exact.putIfAbsent(argb & 0xFFFFFF, exact.size());
        }
        if (exact.size() <= colours) {
            int[] palette = new int[exact.size()];
            exact.forEach((rgb, at) -> palette[at] = rgb);
            for (int k = 0; k < opaque; k++)
                index[order[k]] = exact.get(cells.argb()[order[k]] & 0xFFFFFF);
            return new Quantised(cells.width(), cells.height(), index, palette, opaque);
        }

        List<int[]> boxes = new ArrayList<>();
        boxes.add(new int[] { 0, opaque });
        long[] keys = new long[opaque];
        while (boxes.size() < colours) {
            int widest = -1;
            int widestChannel = 0;
            int widestRange = 0;
            for (int bi = 0; bi < boxes.size(); bi++) {
                int[] box = boxes.get(bi);
                if (box[1] - box[0] < 2)
                    continue;
                for (int channel = 0; channel < 3; channel++) {
                    int low = 255;
                    int high = 0;
                    for (int k = box[0]; k < box[1]; k++) {
                        int value = channel(cells.argb()[order[k]], channel);
                        low = Math.min(low, value);
                        high = Math.max(high, value);
                    }
                    if (high - low > widestRange) {
                        widestRange = high - low;
                        widest = bi;
                        widestChannel = channel;
                    }
                }
            }
            if (widest < 0)
                break;
            int[] box = boxes.get(widest);
            for (int k = box[0]; k < box[1]; k++)
                keys[k] = (long) channel(cells.argb()[order[k]], widestChannel) << 32 | order[k];
            Arrays.sort(keys, box[0], box[1]);
            for (int k = box[0]; k < box[1]; k++)
                order[k] = (int) keys[k];
            int middle = (box[0] + box[1]) / 2;
            boxes.set(widest, new int[] { box[0], middle });
            boxes.add(new int[] { middle, box[1] });
        }

        int[] palette = new int[boxes.size()];
        for (int bi = 0; bi < boxes.size(); bi++) {
            int[] box = boxes.get(bi);
            long r = 0;
            long g = 0;
            long b = 0;
            for (int k = box[0]; k < box[1]; k++) {
                int argb = cells.argb()[order[k]];
                r += (argb >> 16) & 0xFF;
                g += (argb >> 8) & 0xFF;
                b += argb & 0xFF;
                index[order[k]] = bi;
            }
            int n = Math.max(1, box[1] - box[0]);
            palette[bi] = (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        }
        return new Quantised(cells.width(), cells.height(), index, palette, opaque);
    }

    private static int channel(int argb, int channel) {
        return (argb >> (16 - 8 * channel)) & 0xFF;
    }

    // Rectangles

    /**
     * The painted cells as rectangles of one colour each, as {@code x, y, width,
     * height, palette index}: runs along each row, and runs that repeat exactly on
     * the rows below joined into one. Two triangles a rectangle is what a picture
     * costs, so this is what keeps a logo with flat areas cheap.
     */
    static List<int[]> rects(int[] index, int width, int height) {
        List<int[]> out = new ArrayList<>();
        Map<Long, int[]> open = new HashMap<>();
        for (int y = 0; y < height; y++) {
            Map<Long, int[]> next = new HashMap<>();
            int x = 0;
            while (x < width) {
                int colour = index[y * width + x];
                int end = x + 1;
                while (end < width && index[y * width + end] == colour)
                    end++;
                if (colour >= 0) {
                    long key = (long) x << 40 | (long) end << 20 | colour;
                    int[] rect = open.get(key);
                    if (rect == null) {
                        rect = new int[] { x, y, end - x, 0, colour };
                        out.add(rect);
                    }
                    rect[3]++;
                    next.put(key, rect);
                }
                x = end;
            }
            open = next;
        }
        return out;
    }

    /** The painted cells as one outline, in cell units. */
    static Path2D.Double outline(Quantised picture) {
        int[] mask = new int[picture.index().length];
        for (int i = 0; i < mask.length; i++)
            mask[i] = picture.index()[i] >= 0 ? 0 : -1;
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (int[] rect : rects(mask, picture.width(), picture.height()))
            path.append(new Rectangle2D.Double(rect[0], rect[1], rect[2], rect[3]), false);
        return path;
    }
}
