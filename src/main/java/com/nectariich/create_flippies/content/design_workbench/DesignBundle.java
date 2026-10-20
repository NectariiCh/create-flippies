package com.nectariich.create_flippies.content.design_workbench;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/**
 * An imported model's files, as one blob: the {@code .obj}, its {@code .mtl}
 * libraries and their textures, under the names they reference each other by.
 *
 * The blob is what is stored, sent and hashed, so the hash names exactly these bytes:
 * the server checks an upload against it, and the same model imported twice is stored
 * once. Names are already sanitised to what a resource location allows, which is
 * what lets a client register the files as resources and have Hypergraphical resolve
 * {@code mtllib} and {@code map_Kd} between them unchanged.
 */
public record DesignBundle(String objName, Map<String, byte[]> files) {

    /** Larger than any reasonable control model, small enough to keep a server safe. */
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    public byte[] obj() {
        return files.get(objName);
    }

    public byte[] encode() throws IOException {
        CompoundTag tag = new CompoundTag();
        tag.putString("Obj", objName);
        CompoundTag files = new CompoundTag();
        this.files.forEach(files::putByteArray);
        tag.put("Files", files);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        return out.toByteArray();
    }

    public static DesignBundle decode(byte[] bytes) throws IOException {
        // Read with a size budget: an upload is checked by decoding it, and a small
        // blob that inflates to gigabytes must fail rather than take the server down
        CompoundTag tag = NbtIo.read(new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new ByteArrayInputStream(bytes)))), new NbtAccounter(MAX_BYTES * 2L));
        CompoundTag files = tag.getCompound("Files");
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String name : files.getAllKeys())
            if (files.contains(name, Tag.TAG_BYTE_ARRAY) && isSafeName(name))
                out.put(name, files.getByteArray(name));
        String obj = tag.getString("Obj");
        if (!out.containsKey(obj))
            throw new IOException("Design has no model file");
        return new DesignBundle(obj, Collections.unmodifiableMap(out));
    }

    /** A flat file name a resource location path accepts. */
    public static boolean isSafeName(String name) {
        return name.matches("[a-z0-9_.-]{1,96}") && !name.startsWith(".");
    }

    /** The first 32 hex digits of the SHA-256 of a blob. */
    public static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++)
                hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
