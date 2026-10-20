package com.nectariich.create_flippies.content.computer.bus;

/**
 * Composes 8, 16 and 32-bit values from byte arrays in either byte order, for the bus's slow
 * path. Callers have already checked that {@code index + width} lies inside the array.
 */
final class ByteArrays {

    private ByteArrays() {
    }

    static int load(byte[] a, int i, int width, boolean little) {
        switch (width) {
            case 1:
                return a[i] & 0xFF;
            case 2:
                return little
                        ? (a[i] & 0xFF) | (a[i + 1] & 0xFF) << 8
                        : (a[i] & 0xFF) << 8 | (a[i + 1] & 0xFF);
            default:
                return little
                        ? (a[i] & 0xFF) | (a[i + 1] & 0xFF) << 8 | (a[i + 2] & 0xFF) << 16 | (a[i + 3] & 0xFF) << 24
                        : (a[i] & 0xFF) << 24 | (a[i + 1] & 0xFF) << 16 | (a[i + 2] & 0xFF) << 8 | (a[i + 3] & 0xFF);
        }
    }

    static void store(byte[] a, int i, int width, int v, boolean little) {
        switch (width) {
            case 1:
                a[i] = (byte) v;
                break;
            case 2:
                if (little) {
                    a[i] = (byte) v;
                    a[i + 1] = (byte) (v >>> 8);
                } else {
                    a[i] = (byte) (v >>> 8);
                    a[i + 1] = (byte) v;
                }
                break;
            default:
                if (little) {
                    a[i] = (byte) v;
                    a[i + 1] = (byte) (v >>> 8);
                    a[i + 2] = (byte) (v >>> 16);
                    a[i + 3] = (byte) (v >>> 24);
                } else {
                    a[i] = (byte) (v >>> 24);
                    a[i + 1] = (byte) (v >>> 16);
                    a[i + 2] = (byte) (v >>> 8);
                    a[i + 3] = (byte) v;
                }
        }
    }

    /**
     * Sets the bit of every 4 KiB page that {@code [index, index + length)} touches in
     * {@code pages} (bit {@code page & 63} of word {@code page >>> 6}); pages past the bitmap are
     * ignored.
     */
    static void markPages(long[] pages, int index, int length) {
        if (pages == null || length <= 0)
            return;
        int first = index >>> 12;
        int last = (index + length - 1) >>> 12;
        for (int p = first; p <= last; p++) {
            int word = p >>> 6;
            if (word >= pages.length)
                return;
            pages[word] |= 1L << (p & 63);
        }
    }
}
