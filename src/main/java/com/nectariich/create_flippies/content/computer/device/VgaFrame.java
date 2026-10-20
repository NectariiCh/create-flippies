package com.nectariich.create_flippies.content.computer.device;

/**
 * An immutable copy of what the display shows (spec 3.7), for tests and for a client's first
 * frame. {@code pixels} holds the visible VRAM bytes (PITCH * HEIGHT from START, wrapped); in text
 * mode {@code width} and {@code height} are 80 and 25 cells. The arrays are copied in and out.
 */
public record VgaFrame(int mode, int width, int height, int pitch, int bitsPerPixel,
                       int cursorColumn, int cursorRow, boolean cursorVisible, boolean blink,
                       boolean displayEnabled, byte[] pixels, int[] palette, long generation) {

    public VgaFrame {
        pixels = pixels.clone();
        palette = palette.clone();
    }

    @Override
    public byte[] pixels() {
        return pixels.clone();
    }

    @Override
    public int[] palette() {
        return palette.clone();
    }

    /** One visible byte without copying the array. */
    public int pixelByte(int index) {
        return pixels[index] & 0xFF;
    }

    /** One palette entry (0x00RRGGBB) without copying the array. */
    public int paletteEntry(int index) {
        return palette[index];
    }
}
