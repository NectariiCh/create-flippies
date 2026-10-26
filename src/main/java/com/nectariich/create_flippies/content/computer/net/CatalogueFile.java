package com.nectariich.create_flippies.content.computer.net;

import static com.nectariich.create_flippies.content.computer.net.DeflatedChunks.CHUNK;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * One open file of the catalogue (spec 12.4.3), read at any offset a 64 KiB block at a time so a
 * body streams without the whole file in memory. A deflated file inflates the chunk holding the
 * offset; a plain one keeps its stream open for the next block and reopens it to go back (a
 * retransmission from an earlier block). One reader per HTTP response; not thread-safe.
 */
public final class CatalogueFile implements Closeable {

    private final ContentSource source;
    private final String path;
    private final long size;
    private final boolean deflated;

    private byte[] block;
    private long blockIndex = -1;
    private int blockLength;
    /** The plain file's stream, positioned at the start of block {@link #streamBlock}. */
    private InputStream stream;
    private long streamBlock;

    CatalogueFile(ContentSource source, String path, long size, boolean deflated) {
        this.source = Objects.requireNonNull(source, "source");
        this.path = path;
        this.size = size;
        this.deflated = deflated;
    }

    /** The resource path, {@code host/dir/file}. */
    public String path() {
        return path;
    }

    public long size() {
        return size;
    }

    /** Stored as deflated chunks rather than plain. */
    public boolean deflated() {
        return deflated;
    }

    /**
     * Copies up to {@code length} bytes from {@code offset} into {@code dst}, never past the end of
     * the block holding {@code offset}; the count copied, 0 at or past the end of the file.
     */
    public int read(long offset, byte[] dst, int at, int length) throws IOException {
        Objects.checkFromIndexSize(at, length, dst.length);
        if (offset < 0)
            throw new IllegalArgumentException("negative offset " + offset);
        if (offset >= size || length == 0)
            return 0;
        load(offset / CHUNK);
        int in = (int) (offset % CHUNK);
        int n = Math.min(length, blockLength - in);
        System.arraycopy(block, in, dst, at, n);
        return n;
    }

    private void load(long index) throws IOException {
        if (index == blockIndex)
            return;
        if (block == null)
            block = new byte[CHUNK];
        blockIndex = -1;
        int expected = (int) Math.min(CHUNK, size - index * CHUNK);
        if (deflated) {
            String chunk = DeflatedChunks.chunkPath(path, index);
            InputStream in = source.open(chunk);
            if (in == null)
                throw new IOException(chunk + " is missing");
            DeflatedChunks.inflate(in, block, expected, chunk);
        } else {
            if (stream == null || streamBlock > index) {
                closeStream();
                stream = source.open(path);
                if (stream == null)
                    throw new IOException(path + " is gone");
                streamBlock = 0;
            }
            stream.skipNBytes((index - streamBlock) * CHUNK);
            streamBlock = index;
            if (stream.readNBytes(block, 0, expected) != expected)
                throw new IOException(path + " is shorter than its size " + size);
            streamBlock = index + 1;
        }
        blockIndex = index;
        blockLength = expected;
    }

    private void closeStream() throws IOException {
        if (stream != null)
            stream.close();
        stream = null;
    }

    /** Closes the stream and drops the block buffer; the file can still be read again after. */
    @Override
    public void close() throws IOException {
        block = null;
        blockIndex = -1;
        closeStream();
    }
}
