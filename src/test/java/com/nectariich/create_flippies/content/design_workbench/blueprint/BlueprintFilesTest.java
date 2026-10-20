package com.nectariich.create_flippies.content.design_workbench.blueprint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The local files: an index written atomically and read back, blobs kept by hash, and packed tags read on a budget. */
class BlueprintFilesTest {

    @TempDir
    Path dir;

    @Test
    void writesAndReadsAnIndexFile() throws Exception {
        Path file = dir.resolve("blueprints").resolve("index.nbt");
        assertNull(BlueprintFiles.readNbt(file), "no file yet is not an error");

        BlueprintIndex index = new BlueprintIndex();
        index.put(BlueprintIndexTest.blueprint("Kept", "here"));
        BlueprintFiles.writeNbt(file, index.write());
        BlueprintFiles.writeNbt(file, index.write());
        assertEquals(index.write(), BlueprintIndex.read(BlueprintFiles.readNbt(file)).write());
        try (var listing = Files.list(file.getParent())) {
            assertEquals(1, listing.count(), "no temporary file is left behind");
        }
    }

    @Test
    void keepsBlobsByHash() throws Exception {
        byte[] bytes = { 1, 2, 3 };
        String hash = BlueprintIndexTest.MODEL;
        BlueprintFiles.writeBlob(dir, hash, bytes);
        assertArrayEquals(bytes, BlueprintFiles.readBlob(dir, hash));
        assertNull(BlueprintFiles.readBlob(dir, "ffffffffffffffffffffffffffffffff"));
        assertNull(BlueprintFiles.blobFile(dir, "../evil"));
        assertThrows(IOException.class, () -> BlueprintFiles.writeBlob(dir, "../evil", bytes));
    }

    @Test
    void unpackingStopsAtItsBudget() {
        CompoundTag big = new CompoundTag();
        big.putByteArray("Zeros", new byte[1 << 20]);
        byte[] packed = BlueprintFiles.pack(big);
        assertThrows(IOException.class, () -> BlueprintFiles.unpack(packed, 64 * 1024));
        assertThrows(IOException.class, () -> BlueprintFiles.unpack(new byte[] { 1, 2, 3 }, 1024));
    }
}
