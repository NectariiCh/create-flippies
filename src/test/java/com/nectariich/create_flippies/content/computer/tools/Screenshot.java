package com.nectariich.create_flippies.content.computer.tools;

import com.nectariich.create_flippies.content.computer.device.VgaDisplay;
import com.nectariich.create_flippies.content.computer.device.VgaFrame;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * What a display shows, saved to a file for the headless runner (spec 12.9). Graphics modes
 * become a PNG at the mode's resolution: 8 bpp through the palette, 4 bpp with the high nibble
 * the left pixel, black while the display is off (CTRL bit 0 clear). The codebase has no 8x16
 * font of its own (the CRT draws text with the game's font sheet, which is not here), so text
 * mode is saved as text instead: one line per row, characters through code page 437, control
 * characters and NUL as spaces, trailing spaces trimmed.
 */
final class Screenshot {

    private Screenshot() {
    }

    /**
     * Saves {@code frame}: a graphics mode to {@code path} as PNG, text mode to {@code path} with
     * its extension changed to ".txt". Returns the file written.
     */
    static Path save(VgaFrame frame, Path path) throws IOException {
        if (frame.mode() == VgaDisplay.MODE_TEXT) {
            Path txt = withExtension(path, ".txt");
            Files.writeString(txt, text(frame), StandardCharsets.UTF_8);
            return txt;
        }
        if (!ImageIO.write(image(frame), "png", path.toFile()))
            throw new IOException("no PNG writer in this Java runtime");
        return path;
    }

    /** A graphics mode's pixels as 0xRRGGBB. */
    static BufferedImage image(VgaFrame frame) {
        int w = frame.width(), h = frame.height();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        if (!frame.displayEnabled())
            return img;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int base = y * frame.pitch();
            for (int x = 0; x < w; x++) {
                int index = frame.bitsPerPixel() == 4
                        ? (frame.pixelByte(base + (x >> 1)) >>> ((x & 1) == 0 ? 4 : 0)) & 0xF
                        : frame.pixelByte(base + x);
                row[x] = frame.paletteEntry(index) & 0xFFFFFF;
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        return img;
    }

    /** Text mode's characters, rows ending in '\n'; attributes are dropped. */
    static String text(VgaFrame frame) {
        Charset cp437 = Charset.isSupported("IBM437") ? Charset.forName("IBM437") : StandardCharsets.ISO_8859_1;
        StringBuilder out = new StringBuilder();
        byte[] cells = new byte[frame.width()];
        for (int r = 0; r < frame.height(); r++) {
            for (int c = 0; c < frame.width(); c++) {
                int ch = frame.pixelByte(r * frame.pitch() + c * 2);
                cells[c] = (byte) (ch < 0x20 || ch == 0x7F ? ' ' : ch);
            }
            out.append(new String(cells, cp437).stripTrailing()).append('\n');
        }
        return out.toString();
    }

    private static Path withExtension(Path path, String extension) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return path.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + extension);
    }
}
