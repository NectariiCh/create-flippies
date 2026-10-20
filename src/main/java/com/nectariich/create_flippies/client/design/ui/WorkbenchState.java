package com.nectariich.create_flippies.client.design.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.nectariich.create_flippies.client.design.DesignGeometry;
import com.nectariich.create_flippies.content.design_workbench.BreakerIndicator;
import com.nectariich.create_flippies.content.design_workbench.BreakerMode;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignBundle;
import com.nectariich.create_flippies.content.design_workbench.DesignKind;
import com.nectariich.create_flippies.content.design_workbench.DesignMaterial;
import com.nectariich.create_flippies.content.design_workbench.DesignOcclusion;
import com.nectariich.create_flippies.content.design_workbench.DesignText;
import com.nectariich.create_flippies.content.design_workbench.annunciator.AnnunciatorDraft;
import com.nectariich.create_flippies.content.design_workbench.annunciator.AnnunciatorRack;
import com.nectariich.create_flippies.content.design_workbench.blueprint.Blueprint;
import com.nectariich.create_flippies.content.design_workbench.gauge.GaugeDial;
import com.nectariich.create_flippies.content.design_workbench.gauge.GaugeDraft;
import com.nectariich.create_flippies.content.design_workbench.gauge.ObjUvBounds;
import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorRating;

import dev.nectariich.hypergraphical.api.MeshInfo;
import dev.nectariich.hypergraphical.api.MeshUnit;
import dev.nectariich.hypergraphical.mesh.MeshBounds;
import net.minecraft.resources.ResourceLocation;

/**
 * Everything the Design Workbench is editing, apart from its widgets: the model, how
 * it is sized and placed, which parts move and light up, how they move, the text, and
 * the editor-only state (hidden parts, the previewed position, the selected text box).
 * The screen's widgets are rebuilt often (on scrolling, on changing tab); this is what
 * survives that, and what a design is built from.
 *
 * Sizes are held the way the player edits them, as a unit and a multiplier; the
 * design holds the product, {@link #scale()} pixels per model unit.
 *
 * The rack mode has a state of its own ({@link #forRack}): its model is not
 * imported but generated from {@link #rack} by the screen, written in authored
 * pixels, so it is held at one pixel per unit and no offset, and nothing moves.
 *
 * The gauge mode ({@link #forGauge}) is a control on an imported model like any
 * other, with {@link #gauge} on top: the face printed for it, the submesh the face
 * is drawn on, and how the needle (the moving parts) follows the reading.
 */
public final class WorkbenchState {

    public DesignKind kind = DesignKind.SWITCH_2P;

    // The model, once there is one. The blob is the encoded bundle, for uploading;
    // it can be missing for a blueprint whose model this client only has installed
    public String hash;
    public byte[] blob;
    public String modelName;
    public ResourceLocation mesh;
    public MeshInfo info;
    public final List<String> parts = new ArrayList<>();

    /** The parts that move together, in the model's part order when built. */
    public final Set<String> moving = new LinkedHashSet<>();

    /** Parts hidden in the preview so what is behind them can be seen. Never saved. */
    public final Set<String> hidden = new HashSet<>();

    /** Parts whose material elements are listed under them in the parts list. Never saved. */
    public final Set<String> expanded = new HashSet<>();

    /**
     * What lights up on a button: part names for whole parts and element keys
     * ({@link ControlDesign#elementKey}) for one material of a part, mixed, so a part
     * and some of its elements can both be picked. Kept whatever the kind, so trying a
     * switch and coming back to a button loses nothing, but only a button's design
     * has any.
     */
    public final Set<String> lights = new LinkedHashSet<>();

    /** Null means the guessed unit. */
    public MeshUnit unit;
    public MeshUnit guessedUnit = MeshUnit.METERS;
    public float multiplier = 1;
    public float offsetX, offsetY, offsetZ;

    public float[][] poses = ControlDesign.defaultPoses(kind);

    /** A switch's custom pivot in pixels, or null for the default. */
    public float[] pivot;

    public IndicatorAction action = IndicatorAction.SPRING_RETURN;
    public IndicatorRating rating = IndicatorRating.V24;

    /**
     * Which of a switch's positions spring back to rest, as a
     * {@code RotarySwitchSpring} mask. Ignored for a button, whose spring is its
     * {@link #action}.
     */
    public int springs;

    /**
     * A breaker's flag: its parts, never any of the {@link #moving} ones, its two
     * poses (counter-clockwise, then clockwise, as Euler degrees), its custom pivot
     * in pixels or null for the default, and what its contacts follow. Kept for
     * every kind but only built into a breaker, and back to the defaults whenever
     * the kind changes.
     */
    public final Set<String> indicatorParts = new LinkedHashSet<>();
    public float[][] indicatorPoses = BreakerIndicator.defaultPoses();
    public float[] indicatorPivot;
    public BreakerMode breakerMode = BreakerMode.THREE_POSITION;

    public final List<TextDraft> texts = new ArrayList<>();

    /**
     * How the model shows its ambient occlusion. Belongs to the design, not the model,
     * so it is kept when another model is imported, and two designs on one model can
     * each have their own.
     */
    public DesignOcclusion occlusion = DesignOcclusion.DEFAULT;

    /**
     * The material elements edited in the material editor, by element key
     * ({@link ControlDesign#elementKey}). Kept when another model is imported for the
     * elements whose part and material names it still has.
     */
    public final Map<String, DesignMaterial> materials = new TreeMap<>();

    /** The rack the model is generated from, in rack mode; null otherwise. */
    public AnnunciatorDraft rack;

    /** The gauge's face, the submesh it is drawn on and its needle's motion, in gauge mode; null otherwise. */
    public GaugeDraft gauge;

    /** The window being edited in rack mode, as an index into the rack's cells. */
    public int selectedWindow;

    /** The text box being edited, or -1. */
    public int selected = -1;

    /** The position the preview holds, or -1 to step through them all. */
    public int previewPosition = -1;

    /** The blueprint being edited, when the workbench was opened from one, and whether it was a local one. */
    public Blueprint editing;
    public boolean editingLocal;

    /**
     * A scale read from a blueprint whose model is still on its way: there is no
     * mesh to guess a unit from yet, so the multiplier is worked out when it comes.
     */
    public float pendingScale = Float.NaN;

    /** A state for the rack mode, starting from the default rack. */
    public static WorkbenchState forRack() {
        WorkbenchState state = new WorkbenchState();
        state.setKind(DesignKind.ANNUNCIATOR);
        state.rack = AnnunciatorDraft.of(AnnunciatorRack.defaults());
        state.holdAuthored();
        return state;
    }

    /** A state for the gauge mode, starting from the default rotary gauge, with no model yet. */
    public static WorkbenchState forGauge() {
        WorkbenchState state = new WorkbenchState();
        state.gauge = GaugeDraft.of(GaugeDial.defaults(false));
        state.setKind(DesignKind.GAUGE);
        return state;
    }

    /** A generated model is already in authored pixels: one pixel per unit, where it was written. */
    private void holdAuthored() {
        unit = MeshUnit.PIXELS;
        multiplier = 1;
        pendingScale = Float.NaN;
        offsetX = offsetY = offsetZ = 0;
    }

    /**
     * Whether there is enough to save: a model, and a moving part, or a rack that
     * can be built. A gauge also needs a scale that can be printed and a submesh to
     * print it on.
     */
    public boolean canSave() {
        if (info == null)
            return false;
        if (kind.isGauge())
            return !moving.isEmpty() && gaugeError() == null;
        return kind.isRack() ? rack != null && rack.build().error() == null : !moving.isEmpty();
    }

    /** What a gauge still lacks, as a translation key under the workbench's strings, or null when nothing. */
    public String gaugeError() {
        if (gauge == null)
            return "gauge.error.range";
        String error = gauge.build().error();
        if (error != null)
            return error;
        return gauge.face == null || !elementKeys().contains(gauge.face) ? "gauge.error.face" : null;
    }

    // ---------------------------------------------------------------- sizing

    public MeshUnit effectiveUnit() {
        return unit != null ? unit : guessedUnit;
    }

    /** Pixels per model unit. */
    public float scale() {
        return effectiveUnit().blocksPerUnit() * 16 * multiplier;
    }

    /** Scales the model so it is as wide as the front it replaces, then centres it. */
    public void fit() {
        if (info == null)
            return;
        MeshBounds bounds = info.bounds();
        float width = Math.max(bounds.sizeX(), bounds.sizeZ());
        if (width <= 0)
            return;
        multiplier = kind.frontWidth() / (width * effectiveUnit().blocksPerUnit() * 16);
        centre();
    }

    /** Puts the model on the mounting axis, standing on the plate. */
    public void centre() {
        if (info == null)
            return;
        MeshBounds bounds = info.bounds();
        float scale = scale();
        offsetX = 8 - scale * (bounds.minX() + bounds.maxX()) / 2;
        offsetZ = 8 - scale * (bounds.minZ() + bounds.maxZ()) / 2;
        offsetY = -scale * bounds.minY();
    }

    /**
     * Changes what the design is built on. The poses go back to the new kind's
     * defaults, since a switch's are angles and a button's are distances, and two
     * switches stop at different angles.
     */
    public void setKind(DesignKind kind) {
        if (kind == this.kind)
            return;
        this.kind = kind;
        poses = ControlDesign.defaultPoses(kind);
        // Rotary or edgewise is the gauge's face as much as its kind
        if (gauge != null && kind.isGauge())
            gauge.edgewise = kind.isEdgewise();
        // A bit names a position, and two kinds do not put their positions at the
        // same angles, so a mask is not carried from one to another
        springs = 0;
        resetIndicator();
        if (previewPosition >= poses.length)
            previewPosition = -1;
    }

    /** Puts a breaker's flag back to the defaults: no parts, the handle's quarter turns, the default pivot. */
    public void resetIndicator() {
        indicatorParts.clear();
        indicatorPoses = BreakerIndicator.defaultPoses();
        indicatorPivot = null;
        breakerMode = BreakerMode.THREE_POSITION;
    }

    // ---------------------------------------------------------------- model

    /**
     * Takes a freshly imported or downloaded model. The parts and what was chosen of
     * them are kept where the names still exist, so re-importing an edited model does
     * not undo the choices; the moving parts are guessed when none survive.
     */
    public void setModel(String hash, byte[] blob, String modelName, ResourceLocation mesh, MeshInfo info) {
        this.hash = hash;
        this.blob = blob;
        this.modelName = modelName;
        this.mesh = mesh;
        this.info = info;
        parts.clear();
        parts.addAll(info.parts().keySet());
        moving.retainAll(parts);
        indicatorParts.retainAll(parts);
        hidden.retainAll(parts);
        expanded.retainAll(parts);
        Set<String> elements = elementKeys();
        lights.removeIf(light -> !parts.contains(light) && !elements.contains(light));
        materials.keySet().retainAll(elements);
        if (moving.isEmpty() && !parts.isEmpty() && !kind.isGenerated())
            moving.add(kind.isGauge() ? guessNeedle(parts) : guessMoving(parts));
        if (gauge != null && gauge.face != null && !elements.contains(gauge.face))
            gauge.face = "";
        guessedUnit = MeshUnit.guess(info.bounds(), kind.frontWidth() / 16f);
        if (Float.isFinite(pendingScale)) {
            unit = null;
            multiplier = pendingScale / (guessedUnit.blocksPerUnit() * 16);
            pendingScale = Float.NaN;
        }
    }

    /** A part whose name says it moves, else the last part, which is where exporters put what was added last. */
    public static String guessMoving(List<String> parts) {
        String[] hints = { "handle", "knob", "lever", "button", "cap", "toggle", "moving" };
        for (String hint : hints)
            for (String part : parts)
                if (part.toLowerCase(Locale.ROOT).contains(hint))
                    return part;
        return parts.get(parts.size() - 1);
    }

    /** A part whose name says it is a gauge's needle, else what {@link #guessMoving} guesses. */
    public static String guessNeedle(List<String> parts) {
        for (String hint : new String[] { "needle", "pointer" })
            for (String part : parts)
                if (part.toLowerCase(Locale.ROOT).contains(hint))
                    return part;
        return guessMoving(parts);
    }

    // ---------------------------------------------------------------- gauge

    /**
     * Prints the gauge's face on one material element of the model, in place of
     * whatever had it. The face fills the element's UV bounds, read from the model
     * file when this client has it, else the whole texture square; an edgewise face
     * takes the element's shape so its printing is not stretched.
     */
    public void assignFace(String part, String material) {
        if (gauge == null || info == null)
            return;
        String key = ControlDesign.elementKey(part, material);
        float[] uv = null;
        if (blob != null) {
            try {
                DesignBundle bundle = DesignBundle.decode(blob);
                uv = ObjUvBounds.of(bundle.obj(), part, material);
            } catch (Exception e) {
                uv = null;
            }
        }
        gauge.face = key;
        gauge.uv = uv;
        for (MeshInfo.Element element : info.elements())
            if (ControlDesign.elementKey(element.part(), element.material()).equals(key)) {
                float aspect = aspect(element.bounds());
                if (aspect > 0)
                    gauge.faceAspect = aspect;
            }
    }

    /** Takes the face off the model; it is printed on nothing until another element is given it. */
    public void clearFace() {
        if (gauge != null)
            gauge.face = "";
    }

    /** Whether the gauge's face is printed on this element. */
    public boolean isFace(String part, String material) {
        return gauge != null && kind.isGauge() && ControlDesign.elementKey(part, material).equals(gauge.face);
    }

    /**
     * A face's height over its width, seen square on: its thinnest axis is taken as
     * the one it faces along, and of the other two, x (or z, when x is the thinnest)
     * is across. 0 for a face with no size.
     */
    static float aspect(MeshBounds bounds) {
        float x = bounds.sizeX(), y = bounds.sizeY(), z = bounds.sizeZ();
        float across, down;
        if (y <= x && y <= z) {
            across = x;
            down = z;
        } else if (z <= x && z <= y) {
            across = x;
            down = y;
        } else {
            across = z;
            down = y;
        }
        return across > 0 && down > 0 ? down / across : 0;
    }

    /** The face element's box in authored pixels, or null when the gauge has no face on this model. */
    public double[] faceBox() {
        if (gauge == null || info == null || gauge.face == null)
            return null;
        for (MeshInfo.Element element : info.elements())
            if (ControlDesign.elementKey(element.part(), element.material()).equals(gauge.face))
                return DesignGeometry.authored(element.bounds(), scale(), offsetX, offsetY, offsetZ);
        return null;
    }

    /** The poses the needle or moving parts are drawn with: a rotary gauge's follow its dial. */
    public float[][] effectivePoses() {
        if (kind.isGauge() && !kind.isEdgewise() && gauge != null)
            return gauge.build().rotaryPoses();
        return poses;
    }

    /** Whether the moving parts slide rather than turn: a button, or an edgewise gauge's sliding needle. */
    public boolean slides() {
        return kind.isButton() || kind.isEdgewise() && gauge != null && gauge.slide;
    }

    /** Every element key the model has. */
    public Set<String> elementKeys() {
        Set<String> keys = new LinkedHashSet<>();
        if (info != null)
            for (MeshInfo.Element element : info.elements())
                keys.add(ControlDesign.elementKey(element.part(), element.material()));
        return keys;
    }

    /** The material elements of one part, in file order. */
    public List<MeshInfo.Element> elementsOf(String part) {
        List<MeshInfo.Element> out = new ArrayList<>();
        if (info != null)
            for (MeshInfo.Element element : info.elements())
                if (element.part().equals(part))
                    out.add(element);
        return out;
    }

    /** The parts drawn in the preview: all but the hidden ones. */
    public Set<String> visibleParts() {
        Set<String> visible = new LinkedHashSet<>(parts);
        visible.removeAll(hidden);
        return visible;
    }

    /** Whether a part lights up, as a whole or by any of its elements. */
    public boolean lightsUp(String part) {
        if (!kind.isButton())
            return false;
        if (lights.contains(part))
            return true;
        String prefix = part + "#";
        for (String key : lights)
            if (key.startsWith(prefix))
                return true;
        return false;
    }

    // ---------------------------------------------------------------- design

    public DesignGeometry.Boxes boxes() {
        if (kind.isBreaker())
            return DesignGeometry.compute(kind, info, movingSet(), scale(), offsetX, offsetY, offsetZ, poses, pivot,
                    indicatorParts, indicatorPoses, indicatorPivot);
        if (kind.isGauge())
            return DesignGeometry.compute(kind, slides(), info, movingSet(), scale(), offsetX, offsetY, offsetZ,
                    effectivePoses(), pivot);
        return DesignGeometry.compute(kind, info, movingSet(), scale(), offsetX, offsetY, offsetZ, poses, pivot);
    }

    /** The flag as a breaker design holds it, its parts in the model's order; null for any other kind. */
    private BreakerIndicator indicator() {
        if (!kind.isBreaker())
            return null;
        List<String> ordered = new ArrayList<>();
        for (String part : parts)
            if (indicatorParts.contains(part) && !moving.contains(part))
                ordered.add(part);
        float[][] poseCopy = new float[indicatorPoses.length][];
        for (int i = 0; i < indicatorPoses.length; i++)
            poseCopy[i] = indicatorPoses[i].clone();
        return new BreakerIndicator(ordered, poseCopy, indicatorPivot == null ? null : indicatorPivot.clone(),
                breakerMode);
    }

    private Set<String> movingSet() {
        Set<String> ordered = new LinkedHashSet<>();
        for (String part : parts)
            if (moving.contains(part))
                ordered.add(part);
        return ordered;
    }

    /**
     * What lights up, as the design names it, in the model's order: each part, then
     * its elements. Nothing for a switch.
     */
    private List<String> builtLights() {
        if (!kind.isButton())
            return List.of();
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (lights.contains(part))
                out.add(part);
            for (MeshInfo.Element element : elementsOf(part)) {
                String key = ControlDesign.elementKey(element.part(), element.material());
                if (lights.contains(key))
                    out.add(key);
            }
        }
        return out;
    }

    /** The edited elements the model still has. */
    private Map<String, DesignMaterial> builtMaterials() {
        Map<String, DesignMaterial> out = new TreeMap<>(materials);
        out.keySet().retainAll(elementKeys());
        return out;
    }

    public List<DesignText> builtTexts() {
        List<DesignText> out = new ArrayList<>();
        for (TextDraft draft : texts)
            out.add(draft.build());
        return out;
    }

    /**
     * The design as it stands, under a name, with its boxes worked out from the
     * model, read back through {@link ControlDesign#read} so it is exactly what a
     * save will hold. Null without a model.
     */
    public ControlDesign design(String name) {
        if (info == null || hash == null)
            return null;
        DesignGeometry.Boxes boxes = boxes();
        float[][] poseCopy = new float[poses.length][];
        for (int i = 0; i < poses.length; i++)
            poseCopy[i] = poses[i].clone();
        // A gauge's barrel and studs behind the plate are its rear, which the
        // footprint adds as it does a switch's contacts
        GaugeDial builtGauge = kind.isGauge() && gauge != null ? gauge.build() : null;
        ControlDesign raw = new ControlDesign(kind, hash, name == null ? "" : name, new ArrayList<>(movingSet()),
                builtLights(), scale(), offsetX, offsetY, offsetZ, poseCopy,
                pivot == null || slides() ? null : pivot.clone(), boxes.front(), boxes.moving(),
                kind.isButton() ? action : IndicatorAction.SPRING_RETURN, rating, builtTexts(),
                kind.isButton() ? 0 : springs, rack == null ? null : rack.build(), indicator(), occlusion,
                builtMaterials(), builtGauge);
        return ControlDesign.read(raw.write());
    }

    /**
     * Restores everything a blueprint holds. The model itself is installed by the
     * caller; until it is, the scale waits in {@link #pendingScale}.
     */
    public void load(Blueprint blueprint, boolean local) {
        ControlDesign design = blueprint.design();
        editing = blueprint;
        editingLocal = local;
        kind = design.kind();
        hash = design.model();
        modelName = blueprint.name();
        moving.clear();
        moving.addAll(design.movingParts());
        lights.clear();
        lights.addAll(design.lights());
        offsetX = design.offsetX();
        offsetY = design.offsetY();
        offsetZ = design.offsetZ();
        poses = new float[design.poses().length][];
        for (int i = 0; i < poses.length; i++)
            poses[i] = design.poses()[i].clone();
        pivot = design.pivot() == null ? null : design.pivot().clone();
        action = design.action();
        rating = design.rating();
        springs = design.springs();
        resetIndicator();
        if (design.indicator() != null) {
            indicatorParts.addAll(design.indicator().parts());
            indicatorPoses = new float[design.indicator().poses().length][];
            for (int i = 0; i < indicatorPoses.length; i++)
                indicatorPoses[i] = design.indicator().poses()[i].clone();
            indicatorPivot = design.indicator().pivot() == null ? null : design.indicator().pivot().clone();
            breakerMode = design.indicator().mode();
        }
        occlusion = design.occlusion();
        materials.clear();
        materials.putAll(design.materials());
        texts.clear();
        for (DesignText text : design.texts())
            texts.add(TextDraft.of(text));
        selected = -1;
        previewPosition = -1;
        unit = null;
        multiplier = 1;
        pendingScale = design.scale();
        if (design.rack() != null) {
            rack = AnnunciatorDraft.of(design.rack());
            selectedWindow = 0;
            holdAuthored();
        }
        if (design.gauge() != null)
            gauge = GaugeDraft.of(design.gauge());
    }

    public TextDraft selectedText() {
        return selected >= 0 && selected < texts.size() ? texts.get(selected) : null;
    }
}
