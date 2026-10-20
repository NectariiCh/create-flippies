package com.nectariich.create_flippies.content.design_workbench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The world's imported models, one file per hash under
 * {@code <world>/data/create_flippies_designs}.
 *
 * Kept as files beside the save rather than inside a saved-data compound, because a
 * model is megabytes the world never needs in memory: it is read only when a client
 * asks for one it has not seen. Nothing is ever deleted, since an item or a placed
 * control anywhere in the world may still name a model.
 */
public final class DesignStore {

    private DesignStore() {}

    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("create_flippies_designs");
    }

    private static Path file(MinecraftServer server, String hash) {
        if (!ControlDesign.isHash(hash))
            throw new IllegalArgumentException("Not a design hash: " + hash);
        return dir(server).resolve(hash + ".bin");
    }

    public static boolean has(MinecraftServer server, String hash) {
        return ControlDesign.isHash(hash) && Files.isRegularFile(file(server, hash));
    }

    /** The stored blob, or null when there is none. */
    public static byte[] get(MinecraftServer server, String hash) {
        if (!has(server, hash))
            return null;
        try {
            return Files.readAllBytes(file(server, hash));
        } catch (IOException e) {
            CreateFlippies.LOGGER.error("Could not read design {}", hash, e);
            return null;
        }
    }

    /** Stores a blob under its hash; written aside and moved in, so a crash leaves no half file. */
    public static boolean put(MinecraftServer server, String hash, byte[] bytes) {
        if (has(server, hash))
            return true;
        try {
            Files.createDirectories(dir(server));
            Path target = file(server, hash);
            Path temp = target.resolveSibling(hash + ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            CreateFlippies.LOGGER.error("Could not store design {}", hash, e);
            return false;
        }
    }
}
