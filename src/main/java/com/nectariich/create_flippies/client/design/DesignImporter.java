package com.nectariich.create_flippies.client.design;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.nectariich.create_flippies.content.design_workbench.DesignBundle;

import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * Reads a model from the player's disk into a {@link DesignBundle}: the {@code .obj},
 * every {@code .mtl} it names, and every texture those name.
 *
 * Files are looked for where the model says they are, relative to the file naming
 * them, and failing that by name among the files the player picked or dropped and
 * beside the {@code .obj}, since exporters write absolute paths from another machine
 * as often as not. Each file is renamed to something a resource location accepts and
 * the references are rewritten to match, all into one flat folder. Textures must be
 * PNG, which is all Minecraft can decode; anything else is left out with a warning
 * and the material falls back to its colour.
 */
public final class DesignImporter {

    private static final Set<String> TEXTURE_KEYS = Set.of("map_kd", "map_ka", "map_ks", "map_ke", "map_emissive",
            "map_pm", "map_metallic", "map_pr", "map_roughness", "norm", "map_bump", "bump", "map_normal", "map_d");

    public record Imported(DesignBundle bundle, String name, List<String> warnings) {}

    private DesignImporter() {}

    /**
     * Whether a file chooser is already up. tinyfiledialogs builds its answer in
     * static buffers, so two dialogs at once (a double click on Import, or Import
     * while a CSV picker is still open) can read each other's half-written strings
     * and crash the whole game in native code. One at a time is all a player can use
     * anyway, so a second request while one is open is ignored.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean PICKING =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Opens the system's file chooser off the render thread, which it would otherwise
     * freeze, and hands what was picked back on the client thread. Nothing comes back
     * if the player cancels.
     */
    public static void browse(Consumer<List<Path>> picked) {
        pick("Create Flippies model picker", "Import model", new String[] { "*.obj" }, "Wavefront OBJ (*.obj)",
                false, picked);
    }

    /** The same for a drawing to paint onto a panel with the screwdriver. */
    public static void browseSvg(Consumer<List<Path>> picked) {
        pick("Create Flippies SVG picker", "Import SVG", new String[] { "*.svg", "*.svgz" }, "SVG drawing (*.svg)",
                false, picked);
    }

    /**
     * The same for the CSVs a rack is described by, several at once (legends and
     * colours together).
     */
    public static void browseCsv(Consumer<List<Path>> picked) {
        pick("Create Flippies CSV picker", "Import rack CSV", new String[] { "*.csv", "*.txt" },
                "CSV (*.csv, *.txt)", true, picked);
    }

    private static void pick(String threadName, String title, String[] patterns, String description,
            boolean multiple, Consumer<List<Path>> picked) {
        if (!PICKING.compareAndSet(false, true))
            return;
        // A real starting folder rather than null: the macOS path through
        // tinyfiledialogs measures the default path, and the user's home is somewhere
        // every player has
        String start = System.getProperty("user.home", "") + java.io.File.separator;
        Thread thread = new Thread(() -> {
            String result;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(patterns.length);
                for (String pattern : patterns)
                    filters.put(stack.UTF8(pattern));
                filters.flip();
                result = TinyFileDialogs.tinyfd_openFileDialog(title, start, filters, description, multiple);
            } finally {
                PICKING.set(false);
            }
            if (result == null)
                return;
            List<Path> paths = new ArrayList<>();
            // Several files come back joined by '|'; one comes back as it is
            for (String path : multiple ? result.split("\\|") : new String[] { result })
                if (!path.isEmpty())
                    paths.add(Path.of(path));
            Minecraft.getInstance().execute(() -> picked.accept(paths));
        }, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    /** Reads text files a player picked or dropped: UTF-8, or Latin-1 for a file that is not. */
    public static List<String> readTexts(List<Path> paths) throws IOException {
        List<String> texts = new ArrayList<>();
        for (Path path : paths) {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > 1024 * 1024)
                throw new IOException(path.getFileName() + " is larger than 1 MB");
            try {
                texts.add(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString());
            } catch (java.nio.charset.CharacterCodingException e) {
                texts.add(new String(bytes, StandardCharsets.ISO_8859_1));
            }
        }
        return texts;
    }

    public static Imported load(List<Path> paths) throws IOException {
        Path obj = null;
        Map<String, Path> byName = new HashMap<>();
        for (Path path : paths) {
            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (obj == null && lower.endsWith(".obj"))
                obj = path;
            byName.put(lower, path);
        }
        if (obj == null)
            throw new IOException("No .obj file among the files given");
        // Best effort: macOS refuses to list protected folders such as Downloads to a
        // process that was not granted them, even when it may read the file the player
        // picked there. Without the listing, references still resolve by their path.
        try (var siblings = Files.list(obj.toAbsolutePath().getParent())) {
            siblings.forEach(path -> byName.putIfAbsent(path.getFileName().toString().toLowerCase(Locale.ROOT), path));
        } catch (IOException | SecurityException ignored) {
        }

        Importing importing = new Importing(byName);
        String objName = importing.name(obj);
        importing.files.put(objName, importing.rewriteObj(obj));

        int total = importing.files.values().stream().mapToInt(bytes -> bytes.length).sum();
        if (total > DesignBundle.MAX_BYTES)
            throw new IOException("The model and its textures come to " + total / 1024 / 1024
                    + " MB; the limit is " + DesignBundle.MAX_BYTES / 1024 / 1024 + " MB");
        String display = obj.getFileName().toString();
        display = display.substring(0, display.length() - 4);
        return new Imported(new DesignBundle(objName, importing.files), display, importing.warnings);
    }

    private static final class Importing {
        final Map<String, Path> byName;
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final Map<Path, String> names = new HashMap<>();
        final List<String> warnings = new ArrayList<>();

        Importing(Map<String, Path> byName) {
            this.byName = byName;
        }

        /** The flat, resource-safe name a file is stored under, the same every time it is asked for. */
        String name(Path path) {
            Path key = path.toAbsolutePath().normalize();
            String existing = names.get(key);
            if (existing != null)
                return existing;
            String base = sanitize(path.getFileName().toString());
            String name = base;
            for (int i = 2; files.containsKey(name) || names.containsValue(name); i++) {
                int dot = base.lastIndexOf('.');
                name = dot > 0 ? base.substring(0, dot) + "_" + i + base.substring(dot) : base + "_" + i;
            }
            names.put(key, name);
            return name;
        }

        /** The file a reference means, or null when it is nowhere to be found. */
        Path find(Path from, String reference) {
            String cleaned = reference.replace('\\', '/');
            Path direct = from.toAbsolutePath().getParent().resolve(cleaned).normalize();
            if (Files.isRegularFile(direct))
                return direct;
            String file = cleaned.substring(cleaned.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            Path found = byName.get(file);
            return found != null && Files.isRegularFile(found) ? found : null;
        }

        byte[] rewriteObj(Path obj) throws IOException {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(obj, StandardCharsets.ISO_8859_1)) {
                String trimmed = line.trim();
                if (!trimmed.toLowerCase(Locale.ROOT).startsWith("mtllib")) {
                    out.add(line);
                    continue;
                }
                String reference = trimmed.substring(6).trim();
                Path mtl = find(obj, reference);
                if (mtl == null) {
                    warnings.add("Missing material library " + reference);
                    continue;
                }
                String name = name(mtl);
                if (!files.containsKey(name))
                    files.put(name, rewriteMtl(mtl));
                out.add("mtllib " + name);
            }
            return String.join("\n", out).getBytes(StandardCharsets.ISO_8859_1);
        }

        byte[] rewriteMtl(Path mtl) throws IOException {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(mtl, StandardCharsets.ISO_8859_1)) {
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < 2 || !TEXTURE_KEYS.contains(tokens[0].toLowerCase(Locale.ROOT))) {
                    out.add(line);
                    continue;
                }
                String reference = texturePath(tokens);
                Path texture = reference.isEmpty() ? null : find(mtl, reference);
                if (texture == null) {
                    warnings.add("Missing texture " + reference);
                    continue;
                }
                if (!texture.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
                    warnings.add("Skipped " + texture.getFileName() + ": only PNG textures are supported");
                    continue;
                }
                String name = name(texture);
                if (!files.containsKey(name))
                    files.put(name, Files.readAllBytes(texture));
                out.add(tokens[0] + " " + name);
            }
            return String.join("\n", out).getBytes(StandardCharsets.ISO_8859_1);
        }

        /** The file named after a map statement's options, which are a dash and their numbers or on/off. */
        private static String texturePath(String[] tokens) {
            int i = 1;
            while (i < tokens.length && tokens[i].startsWith("-") && tokens[i].length() > 1
                    && !Character.isDigit(tokens[i].charAt(1))) {
                i++;
                while (i < tokens.length && (isNumber(tokens[i]) || tokens[i].equals("on") || tokens[i].equals("off")))
                    i++;
            }
            return String.join(" ", java.util.Arrays.copyOfRange(tokens, Math.min(i, tokens.length), tokens.length));
        }

        private static boolean isNumber(String token) {
            try {
                Double.parseDouble(token);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    static String sanitize(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray())
            out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' ? c : '_');
        String name = out.toString();
        while (name.startsWith("."))
            name = "_" + name.substring(1);
        if (name.length() > 80)
            name = name.substring(name.length() - 80);
        return name.isEmpty() ? "file" : name;
    }
}
