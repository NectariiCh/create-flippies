package com.nectariich.create_flippies.content.computer.net;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes .flippack files for tests, laid out as spec 12.4.3 says: chunks and .debs stored, small text deflated. */
public final class PackFiles {

    private PackFiles() {
    }

    public static String manifest(String id, String version, String... hosts) {
        StringBuilder h = new StringBuilder();
        for (String host : hosts)
            h.append(h.length() == 0 ? "" : ", ").append('"').append(host).append('"');
        return "{\"format\": 1, \"id\": \"" + id + "\", \"version\": \"" + version + "\", \"title\": \"Pack " + id
                + "\", \"hosts\": [" + h + "]}";
    }

    /** A pack at {@code file} with {@code pack.json} = {@code json} and {@code files} (served path to bytes) under hosts/. */
    public static Path write(Path file, String json, Map<String, byte[]> files) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            if (json != null)
                put(zip, PackContent.MANIFEST, json.getBytes(StandardCharsets.UTF_8), false);
            for (var e : files.entrySet()) {
                String path = e.getKey();
                boolean stored = path.contains(".z/") || path.endsWith(".deb");
                put(zip, PackContent.HOSTS + path, e.getValue(), stored);
            }
        }
        return file;
    }

    public static Path write(Path file, String id, List<String> hosts, Map<String, byte[]> files) throws IOException {
        return write(file, manifest(id, "1", hosts.toArray(String[]::new)), files);
    }

    private static void put(ZipOutputStream zip, String name, byte[] data, boolean stored) throws IOException {
        ZipEntry e = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            e.setCrc(crc.getValue());
        }
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }
}
