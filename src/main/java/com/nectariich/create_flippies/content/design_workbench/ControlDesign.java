package com.nectariich.create_flippies.content.design_workbench;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.nectariich.create_flippies.content.design_workbench.annunciator.AnnunciatorRack;
import com.nectariich.create_flippies.content.design_workbench.gauge.GaugeDial;
import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorLamp;
import com.nectariich.create_flippies.content.indicator.IndicatorRating;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchSpring;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * Everything a designed control is beyond the control it is built on: which model
 * its front is, how that model sits, which parts of it move and how, which parts
 * light up, and what is printed on it.
 *
 * The model itself is only named here, by the hash of its files, so an item or a
 * panel full of these stays small; the files live once in the world's design store.
 * Lengths are in authored pixels, the space every control is drawn and boxed in:
 * the model's own units times {@link #scale}, moved by the offset, with the mounting
 * plate at y = 0 and the front above it.
 *
 * The two boxes are worked out on the client that made the design, from the model's
 * bounds, because only a client can read the model: {@link #front} is everything in
 * front of the plate (for a switch, everywhere its moving parts sweep), and becomes
 * the front of the control's footprint; {@link #moving} is the box of the moving
 * parts at rest, which is where a press lands.
 *
 * The record holds arrays, which compare by identity, so two designs are compared
 * by their {@link #write() tags} instead of with {@code equals}.
 *
 * @param model       hex hash of the model's files in the design store
 * @param name        what the player called it: the blueprint's name once made from one
 * @param movingParts the model parts that turn or press, all together as one body
 * @param lights      what glows while the lamp is on, only ever on a button: each
 *                    either a part name, for the whole part, or an
 *                    {@link #elementKey element key}, for the faces of one part
 *                    drawn with one material. A part and some of its elements may
 *                    both be listed
 * @param scale       pixels per model unit
 * @param poses       one {x, y, z} per position: Euler degrees for a switch, a
 *                    translation in pixels for a button (released first, then pressed)
 * @param pivot       where a switch turns about, in pixels, or null for
 *                    {@link DesignMotion#DEFAULT_PIVOT}
 * @param action      what pressing a button does; {@link IndicatorAction#NO_CLICK}
 *                    makes it a pure indicator
 * @param texts       the text printed on the control
 * @param springs     which of a switch's positions spring back to rest, as a
 *                    {@link RotarySwitchSpring} mask; always 0 for a button
 * @param rack        what an annunciator rack's model and legends were made from;
 *                    null for every other kind, never null for a rack
 * @param indicator   a breaker control switch's latching flag and what its
 *                    contacts follow; null for every other kind, never null for a
 *                    breaker
 * @param occlusion   how the model shows its ambient occlusion; never null, the
 *                    defaults for a design made before it could be set
 * @param materials   the material elements drawn differently from the model's own
 *                    materials, by {@link #elementKey element key}; empty for a design
 *                    that changes none
 * @param gauge       what a designed gauge measures, the face printed for it and the
 *                    submesh it is drawn on, and how its needle (the moving parts)
 *                    follows the reading; null for every other kind, never null for
 *                    a gauge, and always the face its kind prints. A rotary gauge's
 *                    poses are always the ones its dial implies
 */
public record ControlDesign(DesignKind kind, String model, String name, List<String> movingParts,
        List<String> lights, float scale, float offsetX, float offsetY, float offsetZ,
        float[][] poses, float[] pivot, double[] front, double[] moving, IndicatorAction action,
        IndicatorRating rating, List<DesignText> texts, int springs, AnnunciatorRack rack,
        BreakerIndicator indicator, DesignOcclusion occlusion, Map<String, DesignMaterial> materials,
        GaugeDial gauge) {

    public static final String TAG = "Design";

    /** Far enough out that a front can reach the edge of the reach budget and no further. */
    static final double MIN_COORD = -8, MAX_COORD = 24, MAX_FRONT = 16;

    private static final double[] DEFAULT_FRONT = { 6, 0, 6, 10, 2, 10 };

    /** How much of a design a client may send, so a tag stays a sensible size. */
    public static final int MAX_MOVING_PARTS = 64, MAX_LIGHTS = 64, MAX_TEXTS = 32, MAX_MATERIALS = 128;

    static final int MAX_PART = 128;

    private static final int MAX_NAME = 64, MAX_ELEMENT = 2 * MAX_PART + 1;

    /** How far a switch may turn about any axis, in degrees, and a button may travel, in pixels. */
    static final float MAX_ANGLE = 180;

    private static final float MAX_TRAVEL = 8, MAX_NEEDLE_TRAVEL = 16;

    /**
     * The lists are copied so a design cannot change under whoever holds it, and a
     * missing list reads as an empty one.
     */
    public ControlDesign {
        movingParts = movingParts == null ? List.of() : List.copyOf(movingParts);
        lights = lights == null ? List.of() : List.copyOf(lights);
        texts = texts == null ? List.of() : List.copyOf(texts);
        // Only a switch has detents to spring from, and only the ones its kind allows.
        // A breaker springs back from both sides whatever it was given
        if (kind != null && kind.isBreaker())
            springs = -1;
        springs = kind == null ? 0 : RotarySwitchSpring.sanitise(kind.rotary(), springs);
        // Only a rack has one, and a rack always does
        if (kind == null || !kind.isRack())
            rack = null;
        else if (rack == null)
            rack = AnnunciatorRack.defaults();
        // Only a breaker has one, and a breaker always does, never sharing a part
        // with the handle
        if (kind == null || !kind.isBreaker())
            indicator = null;
        else
            indicator = (indicator == null ? BreakerIndicator.defaults() : indicator).without(movingParts);
        if (occlusion == null)
            occlusion = DesignOcclusion.DEFAULT;
        // Sorted, so the same edits always write the same tag
        materials = materials == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(materials));
        // Only a gauge has one, and a gauge always has one with the face its kind prints
        if (kind == null || !kind.isGauge())
            gauge = null;
        else
            gauge = gauge == null ? GaugeDial.defaults(kind.isEdgewise()) : gauge.withKind(kind.isEdgewise());
        // A rotary needle turns across its dial's sweep, whatever poses were given
        if (gauge != null && !gauge.isEdgewise())
            poses = gauge.rotaryPoses();
    }

    /** A design of any kind but a gauge, which is every design made before there were gauges. */
    public ControlDesign(DesignKind kind, String model, String name, List<String> movingParts,
            List<String> lights, float scale, float offsetX, float offsetY, float offsetZ,
            float[][] poses, float[] pivot, double[] front, double[] moving, IndicatorAction action,
            IndicatorRating rating, List<DesignText> texts, int springs, AnnunciatorRack rack,
            BreakerIndicator indicator, DesignOcclusion occlusion, Map<String, DesignMaterial> materials) {
        this(kind, model, name, movingParts, lights, scale, offsetX, offsetY, offsetZ, poses, pivot,
                front, moving, action, rating, texts, springs, rack, indicator, occlusion, materials, null);
    }

    /** A design whose model is drawn with its own materials, which is every design made before they could be edited. */
    public ControlDesign(DesignKind kind, String model, String name, List<String> movingParts,
            List<String> lights, float scale, float offsetX, float offsetY, float offsetZ,
            float[][] poses, float[] pivot, double[] front, double[] moving, IndicatorAction action,
            IndicatorRating rating, List<DesignText> texts, int springs, AnnunciatorRack rack,
            BreakerIndicator indicator, DesignOcclusion occlusion) {
        this(kind, model, name, movingParts, lights, scale, offsetX, offsetY, offsetZ, poses, pivot,
                front, moving, action, rating, texts, springs, rack, indicator, occlusion, null);
    }

    /** A design with the default occlusion, which is every design made before it could be set. */
    public ControlDesign(DesignKind kind, String model, String name, List<String> movingParts,
            List<String> lights, float scale, float offsetX, float offsetY, float offsetZ,
            float[][] poses, float[] pivot, double[] front, double[] moving, IndicatorAction action,
            IndicatorRating rating, List<DesignText> texts, int springs, AnnunciatorRack rack,
            BreakerIndicator indicator) {
        this(kind, model, name, movingParts, lights, scale, offsetX, offsetY, offsetZ, poses, pivot,
                front, moving, action, rating, texts, springs, rack, indicator, null);
    }

    /**
     * A design of any kind but the rack, which is every design made before there were
     * racks. A breaker made this way gets the default flag.
     */
    public ControlDesign(DesignKind kind, String model, String name, List<String> movingParts,
            List<String> lights, float scale, float offsetX, float offsetY, float offsetZ,
            float[][] poses, float[] pivot, double[] front, double[] moving, IndicatorAction action,
            IndicatorRating rating, List<DesignText> texts, int springs) {
        this(kind, model, name, movingParts, lights, scale, offsetX, offsetY, offsetZ, poses, pivot,
                front, moving, action, rating, texts, springs, null, null);
    }

    public static boolean isHash(String model) {
        return model != null && model.matches("[0-9a-f]{32}");
    }

    /**
     * How a material element of a part is named in {@link #lights}. Neither a part nor
     * a material name can hold a "#", which starts a comment in OBJ and MTL files, so
     * a light with one is always an element key and one without is always a part.
     */
    public static String elementKey(String part, String material) {
        return part + "#" + material;
    }

    /**
     * Where the moving parts start out at each position for a kind: a switch's
     * handle turned about the mounting axis to the angles its rotary switch uses, so
     * a design that sets nothing turns exactly as the built-in one does, and a button
     * sunk by {@link IndicatorLamp#PRESS_DEPTH} when pressed.
     */
    public static float[][] defaultPoses(DesignKind kind) {
        // A rack does not move: one position, where the model puts it
        if (kind.isGenerated())
            return new float[][] { { 0, 0, 0 } };
        // A gauge's needle stands at the minimum, then at the maximum. A rotary one's
        // come from its dial (GaugeDial.rotaryPoses); an edgewise one's are set
        if (kind.isGauge())
            return new float[][] { { 0, 0, 0 }, { 0, 0, 0 } };
        RotarySwitchKind rotary = kind.rotary();
        if (rotary == null)
            return new float[][] { { 0, 0, 0 }, { 0, (float) -IndicatorLamp.PRESS_DEPTH, 0 } };
        float[][] poses = new float[rotary.positions()][];
        for (int i = 0; i < poses.length; i++)
            poses[i] = new float[] { 0, rotary.handleAngle(i), 0 };
        return poses;
    }

    /**
     * How many positions a kind has: its switch's, released and pressed for the
     * button, the minimum and the maximum for a gauge, one for a rack.
     */
    public static int positions(DesignKind kind) {
        return defaultPoses(kind).length;
    }

    /**
     * Whether the moving parts move in a straight line between poses (a button, or
     * an edgewise gauge's sliding needle) rather than turning about the pivot.
     */
    public boolean slides() {
        return kind.isButton() || gauge != null && gauge.slides();
    }

    /** How many positions this design has, one per pose. */
    public int positions() {
        return poses.length;
    }

    /** A rack's legends, laid out as text; none for any other kind. Printed after {@link #texts}. */
    public List<DesignText> legends() {
        return rack == null ? List.of() : rack.legends();
    }

    /** Whether anything is printed on this design: its text boxes, or a rack's legends. */
    public boolean hasPrint() {
        return !texts.isEmpty() || !legends().isEmpty();
    }

    /** The font files this design's text uses, each once, by design store hash. */
    public Set<String> fontHashes() {
        Set<String> hashes = new LinkedHashSet<>();
        for (DesignText text : texts) {
            String hash = text.fontHash();
            if (hash != null)
                hashes.add(hash);
        }
        return hashes;
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Kind", kind.id());
        tag.putString("Model", model);
        tag.putString("Name", name);
        tag.put("Moving", strings(movingParts));
        tag.put("Lights", strings(lights));
        tag.putFloat("Scale", scale);
        tag.putFloat("OffsetX", offsetX);
        tag.putFloat("OffsetY", offsetY);
        tag.putFloat("OffsetZ", offsetZ);
        ListTag flat = new ListTag();
        for (float[] pose : poses)
            for (float value : pose)
                flat.add(FloatTag.valueOf(value));
        tag.put("Poses", flat);
        if (pivot != null) {
            ListTag list = new ListTag();
            for (float value : pivot)
                list.add(FloatTag.valueOf(value));
            tag.put("Pivot", list);
        }
        tag.put("Front", box(front));
        tag.put("MovingBox", box(moving));
        tag.putString("Action", action.id());
        tag.putString("Rating", rating.id());
        ListTag texts = new ListTag();
        for (DesignText text : this.texts)
            texts.add(text.write());
        tag.put("Texts", texts);
        // Left out when there are none, so a design made before there were springs
        // writes exactly the tag it always did
        if (springs != 0)
            tag.putInt("Springs", springs);
        if (rack != null)
            tag.put(AnnunciatorRack.TAG, rack.write());
        // Only a breaker has a flag, so every other kind writes exactly the tag it did
        // before there were breakers
        if (indicator != null)
            tag.put(BreakerIndicator.TAG, indicator.write());
        // Left out at the defaults, so a design that never touched its occlusion writes
        // exactly the tag it did before there was any to set
        if (!occlusion.isDefault())
            tag.put(DesignOcclusion.TAG, occlusion.write());
        // Left out when nothing was edited, likewise
        if (!materials.isEmpty()) {
            CompoundTag edited = new CompoundTag();
            materials.forEach((key, material) -> edited.put(key, material.write()));
            tag.put("Materials", edited);
        }
        if (gauge != null)
            tag.put(GaugeDial.TAG, gauge.write());
        return tag;
    }

    /**
     * Reads a design and makes it safe to build a control from, whoever wrote it. The
     * boxes come from a client, so they are clamped to a sane range here rather than
     * trusted; the host still refuses one that reaches too far.
     *
     * Designs written before there were several moving parts kept the one part as
     * {@code Handle} and its box as {@code Moving}, and had no poses: those read as a
     * single moving part, the same box, and the kind's default poses, so a control
     * already on a panel keeps looking and turning as it did. A design with no
     * {@code Springs} springs nowhere, and a mask that names positions its kind does
     * not have, or its rest position, loses those bits. A breaker springs back from
     * both sides whatever its tag says, and one with no flag gets the default one.
     * One with no {@code Occlusion} shows the default occlusion, and settings out of
     * range are held to it.
     *
     * Designs written while a button lit either whole parts or material elements,
     * never both, said which in {@code Indication}; its lights were part names or
     * element keys accordingly, which read the same as a mixed list, so the key is
     * simply ignored now. One with no {@code Materials} draws its model's own; an
     * edited element whose key is not a sensible element key is dropped.
     *
     * A gauge with no {@code GaugeDial} is the default gauge of its kind. One saved
     * while gauges had a generated case has a {@code Gauge} tag instead: its quantity
     * and face are kept ({@link GaugeDial#readLegacy}), the case it was given stays
     * its model, and it has no face submesh or needle until edited.
     */
    public static ControlDesign read(CompoundTag tag) {
        DesignKind kind = DesignKind.of(tag.getString("Kind"));
        GaugeDial gauge = null;
        if (kind.isGauge() && tag.contains(GaugeDial.TAG, Tag.TAG_COMPOUND))
            gauge = GaugeDial.read(tag.getCompound(GaugeDial.TAG), kind.isEdgewise());
        else if (kind.isGauge() && tag.contains(GaugeDial.LEGACY_TAG, Tag.TAG_COMPOUND))
            gauge = GaugeDial.readLegacy(tag.getCompound(GaugeDial.LEGACY_TAG), kind.isEdgewise());
        boolean slides = kind.isButton() || gauge != null && gauge.slides();
        float scale = tag.getFloat("Scale");
        if (!Float.isFinite(scale) || scale <= 0)
            scale = 1;
        double[] front = clamp(readBox(tag.getList("Front", Tag.TAG_DOUBLE)), DEFAULT_FRONT);
        boolean old = !tag.contains("MovingBox", Tag.TAG_LIST);
        double[] moving = clamp(readBox(tag.getList(old ? "Moving" : "MovingBox", Tag.TAG_DOUBLE)), front);

        List<String> movingParts = readStrings(tag.getList("Moving", Tag.TAG_STRING), MAX_MOVING_PARTS, MAX_PART);
        if (movingParts.isEmpty() && tag.contains("Handle", Tag.TAG_STRING))
            movingParts = List.of(limit(tag.getString("Handle"), MAX_PART));

        IndicatorAction action = IndicatorAction.of(tag.getString("Action"), IndicatorAction.SPRING_RETURN);
        List<DesignText> texts = new ArrayList<>();
        ListTag textTags = tag.getList("Texts", Tag.TAG_COMPOUND);
        for (int i = 0; i < textTags.size() && texts.size() < MAX_TEXTS; i++)
            texts.add(DesignText.read(textTags.getCompound(i)));

        return new ControlDesign(kind, tag.getString("Model"), limit(tag.getString("Name").trim(), MAX_NAME),
                movingParts, readStrings(tag.getList("Lights", Tag.TAG_STRING), MAX_LIGHTS, MAX_ELEMENT), scale,
                finite(tag.getFloat("OffsetX")), finite(tag.getFloat("OffsetY")), finite(tag.getFloat("OffsetZ")),
                readPoses(kind, slides, tag.getList("Poses", Tag.TAG_FLOAT)), readPivot(tag.getList("Pivot", Tag.TAG_FLOAT)),
                front, moving, action, IndicatorRating.of(tag.getString("Rating"), IndicatorRating.V24), texts,
                tag.getInt("Springs"), kind.isRack() && tag.contains(AnnunciatorRack.TAG, Tag.TAG_COMPOUND)
                        ? AnnunciatorRack.read(tag.getCompound(AnnunciatorRack.TAG))
                        : null,
                kind.isBreaker() && tag.contains(BreakerIndicator.TAG, Tag.TAG_COMPOUND)
                        ? BreakerIndicator.read(tag.getCompound(BreakerIndicator.TAG))
                        : null,
                tag.contains(DesignOcclusion.TAG, Tag.TAG_COMPOUND)
                        ? DesignOcclusion.read(tag.getCompound(DesignOcclusion.TAG))
                        : null,
                readMaterials(tag.getCompound("Materials")), gauge);
    }

    /** At most {@link #MAX_MATERIALS} edited elements, each under a key that names a part and a material. */
    private static Map<String, DesignMaterial> readMaterials(CompoundTag tag) {
        Map<String, DesignMaterial> out = new TreeMap<>();
        for (String key : tag.getAllKeys()) {
            if (out.size() >= MAX_MATERIALS)
                break;
            int split = key.indexOf('#');
            if (split <= 0 || split == key.length() - 1 || key.length() > MAX_ELEMENT
                    || !tag.contains(key, Tag.TAG_COMPOUND))
                continue;
            out.put(key, DesignMaterial.read(tag.getCompound(key)));
        }
        return out;
    }

    /** How one element is drawn when it was edited, or null to draw it with the model's own material. */
    public DesignMaterial material(String part, String material) {
        return materials.get(elementKey(part, material));
    }

    /** The design an item carries, or null for an item with none. */
    public static ControlDesign of(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG, Tag.TAG_COMPOUND))
            return null;
        ControlDesign design = read(tag.getCompound(TAG));
        return isHash(design.model()) ? design : null;
    }

    public ItemStack applyTo(ItemStack stack) {
        stack.getOrCreateTag().put(TAG, write());
        return stack;
    }

    public RotarySwitchKind rotary() {
        return kind.rotary();
    }

    /**
     * One pose per position of the kind, each finite and in range: a switch turns at
     * most half a turn either way about any axis, a button travels at most half a
     * block, and a sliding gauge needle a block. Missing poses are the kind's
     * defaults and extra ones are dropped, so a design read for one kind always has
     * exactly that kind's positions.
     */
    private static float[][] readPoses(DesignKind kind, boolean slides, ListTag flat) {
        float[][] poses = defaultPoses(kind);
        float limit = !slides ? MAX_ANGLE : kind.isGauge() ? MAX_NEEDLE_TRAVEL : MAX_TRAVEL;
        for (int i = 0; i < poses.length; i++) {
            if (flat.size() < (i + 1) * 3)
                break;
            for (int axis = 0; axis < 3; axis++) {
                float value = flat.getFloat(i * 3 + axis);
                poses[i][axis] = Float.isFinite(value) ? Mth.clamp(value, -limit, limit) : 0;
            }
        }
        return poses;
    }

    /** The pivot held to the same reach as the boxes, or null when there is none to read. */
    private static float[] readPivot(ListTag list) {
        if (list.size() != 3)
            return null;
        float[] pivot = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            float value = list.getFloat(axis);
            pivot[axis] = Float.isFinite(value) ? (float) Mth.clamp(value, MIN_COORD, MAX_COORD)
                    : DesignMotion.DEFAULT_PIVOT[axis];
        }
        return pivot;
    }

    private static ListTag strings(List<String> values) {
        ListTag list = new ListTag();
        for (String value : values)
            list.add(StringTag.valueOf(value));
        return list;
    }

    /** At most {@code count} distinct strings, each cut to {@code length}, in order. */
    private static List<String> readStrings(ListTag list, int count, int length) {
        Set<String> values = new LinkedHashSet<>();
        for (int i = 0; i < list.size() && values.size() < count; i++)
            values.add(limit(list.getString(i), length));
        return List.copyOf(values);
    }

    private static ListTag box(double[] box) {
        ListTag list = new ListTag();
        for (double value : box)
            list.add(DoubleTag.valueOf(value));
        return list;
    }

    private static double[] readBox(ListTag list) {
        if (list.size() != 6)
            return null;
        double[] box = new double[6];
        for (int i = 0; i < 6; i++)
            box[i] = list.getDouble(i);
        return box;
    }

    /**
     * Inside the range a front can sensibly occupy, in front of the plate, ordered
     * and at least a sliver thick on every axis; the fallback when there is nothing
     * usable at all.
     */
    private static double[] clamp(double[] box, double[] fallback) {
        if (box == null)
            return fallback.clone();
        double[] out = new double[6];
        for (int axis = 0; axis < 3; axis++) {
            double low = Math.min(box[axis], box[axis + 3]);
            double high = Math.max(box[axis], box[axis + 3]);
            if (!Double.isFinite(low) || !Double.isFinite(high))
                return fallback.clone();
            double min = axis == 1 ? 0 : MIN_COORD;
            double max = axis == 1 ? MAX_FRONT : MAX_COORD;
            low = Mth.clamp(low, min, max);
            high = Mth.clamp(high, min, max);
            if (high - low < 0.25)
                high = Math.min(max, low + 0.25);
            out[axis] = low;
            out[axis + 3] = high;
        }
        return out;
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? Mth.clamp(value, -64, 64) : 0;
    }

    private static String limit(String text, int length) {
        return text.length() > length ? text.substring(0, length) : text;
    }
}
