package com.nectariich.create_flippies.client.design;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.decal.ClientDecals;
import com.nectariich.create_flippies.content.decal.DecalArt;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignBundle;
import com.nectariich.create_flippies.content.design_workbench.DesignOcclusion;
import com.nectariich.create_flippies.content.design_workbench.DesignTransfers;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.RequestDesignPacket;

import dev.nectariich.hypergraphical.api.HypergraphicalAPI;
import dev.nectariich.hypergraphical.api.OcclusionSettings;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;

/**
 * The imported models this client has, by hash, and fetching the ones it has not.
 *
 * A model's files are handed to Hypergraphical as runtime files under
 * {@code create_flippies:designs/<hash>/}, so its {@code .obj}, {@code .mtl} and
 * textures find each other exactly as pack files would. A model is asked for once,
 * the first time something wants to draw it; models are content-addressed, so one
 * already here is good on any server. Font files for the text printed on designs
 * travel the same way and are handed to {@link DesignFonts}.
 *
 * Every design mesh has its ambient occlusion baked by Hypergraphical when it is
 * registered, since a model's own crevices darkening is most of what makes a small
 * imported part read as solid, and costs nothing per frame once baked.
 *
 * A design may ask for a bake of its own ({@link DesignOcclusion#ownBake}): a
 * different reach or ray count. The model's mesh is shared by every design made from
 * it, and a bake belongs to a mesh, so such a design draws a variant instead: a mesh
 * at {@code create_flippies:design_ao/<key>/<hash>.obj}, read from the model's own
 * files (so its materials and textures are the model's, loaded once), with a bake
 * rule for the {@code design_ao/<key>/} folder that bakes the way the key says. Two
 * designs on one model with different settings draw two meshes, each baked its own
 * way; two with the same settings share one. A variant nothing has drawn for a while
 * is dropped, and comes back on demand.
 *
 * Client thread only.
 */
public final class ClientDesigns {

    private static final String PREFIX = "designs/";

    /**
     * Hypergraphical's strong occlusion, meant for small detailed props: plenty of
     * rays, a reach of under a third of the model so crevices and the foot of a knob
     * darken while a broad plate stays bright, and the result pushed darker. Its
     * rays and reach are {@link DesignOcclusion#DEFAULT}'s, so a design at the
     * defaults draws the model's own mesh; each design's strength then deepens or
     * lightens it per instance. A variant bakes with this same power.
     */
    private static final OcclusionSettings OCCLUSION = OcclusionSettings.STRONG;

    /** Where the meshes baked a design's own way live, one folder per way of baking. */
    private static final String VARIANT_PREFIX = "design_ao/";

    /** How long a variant nothing draws stays loaded, and how often that is checked. */
    private static final long VARIANT_IDLE_MILLIS = 30_000, VARIANT_SWEEP_MILLIS = 5_000;

    private enum State { REQUESTED, READY, FAILED }

    private static final Map<String, State> STATES = new HashMap<>();
    private static final Map<String, ResourceLocation> MESHES = new HashMap<>();
    private static final Map<String, byte[][]> DOWNLOADS = new HashMap<>();

    /** The verified bytes of every blob this client installed, so a design made from them can be sent on. */
    private static final Map<String, byte[]> BLOBS = new HashMap<>();

    /** The bake keys whose rule Hypergraphical has, and every variant registered, with when it was last drawn. */
    private static final Set<String> BAKE_RULES = new HashSet<>();
    private static final Map<ResourceLocation, Long> VARIANTS = new HashMap<>();
    private static long nextSweep;

    private ClientDesigns() {}

    /**
     * Client setup: every mesh under the design prefix gets its occlusion baked. Has
     * to come before any design mesh is registered, which it does, since none is
     * until a world is joined.
     */
    public static void setup() {
        HypergraphicalAPI.setOcclusionBaking(CreateFlippies.MOD_ID, PREFIX, OCCLUSION);
    }

    /** The mesh for a model, or null while it is not here yet, in which case it is asked for. */
    public static ResourceLocation mesh(String hash) {
        ResourceLocation mesh = MESHES.get(hash);
        if (mesh != null)
            return mesh;
        if (!STATES.containsKey(hash) && ControlDesign.isHash(hash)) {
            STATES.put(hash, State.REQUESTED);
            ModPackets.sendToServer(new RequestDesignPacket(hash));
        }
        return null;
    }

    /**
     * The mesh a design draws its model with: the model's own mesh, or for a design
     * with a bake of its own, the variant baked that way. Null while the model is not
     * here yet, in which case it is asked for. Calling this marks the variant as drawn.
     */
    public static ResourceLocation mesh(String hash, DesignOcclusion occlusion) {
        ResourceLocation mesh = mesh(hash);
        return mesh == null ? null : baked(mesh, hash, occlusion);
    }

    /**
     * The mesh to draw {@code mesh}, the installed model {@code hash}, with: itself when
     * the occlusion needs no bake of its own, else its variant for these settings,
     * registered (with its folder's bake rule) the first time it is asked for.
     */
    public static ResourceLocation baked(ResourceLocation mesh, String hash, DesignOcclusion occlusion) {
        if (occlusion == null || !occlusion.ownBake() || !ControlDesign.isHash(hash))
            return mesh;
        String key = occlusion.bakeKey();
        if (BAKE_RULES.add(key))
            HypergraphicalAPI.setOcclusionBaking(CreateFlippies.MOD_ID, VARIANT_PREFIX + key + "/",
                    new OcclusionSettings(occlusion.rays(), occlusion.radius(), OCCLUSION.power()));
        ResourceLocation variant = CreateFlippies.asResource(VARIANT_PREFIX + key + "/" + hash + ".obj");
        if (VARIANTS.put(variant, Util.getMillis()) == null)
            HypergraphicalAPI.registerMeshVariant(variant, mesh);
        return variant;
    }

    /** Marks a mesh as drawn this frame, so a variant is not dropped while it is on screen. */
    public static void touch(ResourceLocation mesh) {
        if (mesh != null)
            VARIANTS.computeIfPresent(mesh, (k, v) -> Util.getMillis());
    }

    /**
     * Every few seconds, drops the variants nothing has drawn for a while, freeing their
     * meshes and bakes. Their rules stay: a rule is only a line in a list, and a variant
     * drawn again later is baked by it again (from Hypergraphical's cache, if it still
     * has the bake).
     */
    public static void sweepVariants() {
        long now = Util.getMillis();
        if (now < nextSweep)
            return;
        nextSweep = now + VARIANT_SWEEP_MILLIS;
        for (Iterator<Map.Entry<ResourceLocation, Long>> it = VARIANTS.entrySet().iterator(); it.hasNext();) {
            Map.Entry<ResourceLocation, Long> entry = it.next();
            if (now - entry.getValue() > VARIANT_IDLE_MILLIS) {
                HypergraphicalAPI.removeMeshVariant(entry.getKey());
                it.remove();
            }
        }
    }

    /**
     * Asks the server for a font file this client does not have, once. It arrives
     * through {@link #receive} like a model and goes to {@link DesignFonts}.
     */
    public static void ensureFont(String hash) {
        if (DesignFonts.bytes(hash) != null || STATES.containsKey(hash) || !ControlDesign.isHash(hash))
            return;
        STATES.put(hash, State.REQUESTED);
        ModPackets.sendToServer(new RequestDesignPacket(hash));
    }

    /**
     * Asks the server for a picture painted on a panel that this client does not
     * have, once. It arrives through {@link #receive} like a model and goes to
     * {@link ClientDecals}.
     */
    public static void ensureDecal(String hash) {
        if (ClientDecals.art(hash) != null || STATES.containsKey(hash) || !ControlDesign.isHash(hash))
            return;
        STATES.put(hash, State.REQUESTED);
        ModPackets.sendToServer(new RequestDesignPacket(hash));
    }

    /**
     * The bytes of a blob this client has, model or font, or null. A model counts
     * once it was installed from its bytes ({@link #install(String, byte[])} or a
     * download); one installed from an already decoded bundle has none kept.
     */
    public static byte[] blob(String hash) {
        byte[] blob = BLOBS.get(hash);
        return blob != null ? blob : DesignFonts.bytes(hash);
    }

    /**
     * Makes a blob drawable here from its raw bytes, a model bundle, a font file or a
     * painted picture, and keeps a model's bytes. Returns the model's mesh, or null for
     * anything else or for bytes that are none of them (or do not match the hash).
     */
    public static ResourceLocation install(String hash, byte[] blob) {
        try {
            if (!DesignBundle.hash(blob).equals(hash))
                throw new IllegalStateException("hash mismatch");
            if (DecalArt.isDecal(blob)) {
                ClientDecals.install(hash, DecalArt.decode(blob));
                STATES.put(hash, State.READY);
                return null;
            }
            if (DesignTransfers.isFont(blob)) {
                DesignFonts.install(hash, blob);
                STATES.put(hash, State.READY);
                return null;
            }
            ResourceLocation mesh = install(hash, DesignBundle.decode(blob));
            BLOBS.put(hash, blob);
            return mesh;
        } catch (Exception e) {
            CreateFlippies.LOGGER.warn("Could not load design {}: {}", hash, e.toString());
            STATES.put(hash, State.FAILED);
            return null;
        }
    }

    /** Makes a model drawable here, from files this client imported or was sent. */
    public static ResourceLocation install(String hash, DesignBundle bundle) {
        String base = PREFIX + hash + "/";
        HypergraphicalAPI.removeRuntimeFiles(CreateFlippies.MOD_ID, base);
        bundle.files().forEach((name, bytes) ->
                HypergraphicalAPI.registerRuntimeFile(CreateFlippies.asResource(base + name), bytes));
        ResourceLocation mesh = CreateFlippies.asResource(base + bundle.objName());
        MESHES.put(hash, mesh);
        STATES.put(hash, State.READY);
        return mesh;
    }

    /** One chunk of a model or font file the server is sending. */
    public static void receive(String hash, int index, int total, byte[] bytes) {
        if (STATES.get(hash) != State.REQUESTED || total <= 0 || index < 0 || index >= total)
            return;
        byte[][] chunks = DOWNLOADS.computeIfAbsent(hash, k -> new byte[total][]);
        if (chunks.length != total)
            return;
        chunks[index] = bytes;
        for (byte[] chunk : chunks)
            if (chunk == null)
                return;
        DOWNLOADS.remove(hash);

        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] chunk : chunks)
            joined.writeBytes(chunk);
        install(hash, joined.toByteArray());
    }

    /**
     * Leaving a server forgets what was asked of it but not what arrived: a model
     * that never came may be on the next server, and one that did is the same
     * everywhere.
     */
    public static void onLogout() {
        STATES.values().removeIf(state -> state != State.READY);
        DOWNLOADS.clear();
    }
}
