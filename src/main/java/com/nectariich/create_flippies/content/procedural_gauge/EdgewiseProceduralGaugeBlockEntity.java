package com.nectariich.create_flippies.content.procedural_gauge;

import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.registry.ModBlockEntities;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Edgewise Procedural Gauge: the edgewise counterpart of the Square Procedural
 * Gauge. A display link receiver with a procedurally printed edgewise face and the
 * same supply requirement: the pointer only responds while the terminals are fed
 * roughly the rated 5, 12 or 24 V. The face's scale min/max double as the expected
 * data range of the link.
 */
public class EdgewiseProceduralGaugeBlockEntity extends EdgewiseGaugeBlockEntity {

    /** Shared with {@link EdgewiseProceduralGaugeControl}, so both forms read alike. */
    public static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".edgewise_procedural_gauge.goggles.";

    /**
     * The edgewise voltmeters' plate layout, with the scale the Square Procedural
     * Gauge ships with and no units.
     */
    private static final EdgewiseFaceDefinition DEFAULT_FACE = defaultFace(0, 100);

    // May still be null inside the super constructor (buildCircuit runs there,
    // before this initializer) - always read through getSupply().
    private SupplyVoltage supply = SupplyVoltage.V12;

    private float value = 0;
    private boolean hasValue = false;
    private String lastText = "";

    public EdgewiseProceduralGaugeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.EDGEWISE_PROCEDURAL_GAUGE.get(), pos, state);
    }

    @Override
    protected float internalResistance() {
        return getSupply().loadResistance();
    }

    public SupplyVoltage getSupply() {
        return supply != null ? supply : SupplyVoltage.V12;
    }

    /** Whether the terminals currently see roughly the rated supply voltage. */
    public boolean isPowered() {
        return getSupply().accepts(measuredVoltage());
    }

    /**
     * Called server-side by the display target with the raw link text and, if
     * one was found in it, the extracted number.
     */
    public void updateFromLink(String rawText, Double number) {
        lastText = rawText;
        if (number != null) {
            value = number.floatValue();
            hasValue = true;
        }
        notifyUpdate();
    }

    /** Server-side: switch the rated supply voltage. */
    public void setSupply(SupplyVoltage supply) {
        this.supply = supply;
        if (connection != null)
            connection.setResistance(supply.loadResistance());
        notifyUpdate();
    }

    @Override
    public EdgewiseFaceDefinition defaultFaceDefinition() {
        return DEFAULT_FACE;
    }

    /** The printing this gauge ships with, in either of the forms it takes. */
    public static EdgewiseFaceDefinition defaultFace() {
        return DEFAULT_FACE;
    }

    private static EdgewiseFaceDefinition defaultFace(float min, float max) {
        GaugeFaceDefinition picked = GaugeFaceDefinition.autoScaled(min, max).build();
        return VoltmeterRange.V0_12.edgewiseFace().toBuilder(min, max)
                .units("")
                .majorTickInterval(picked.majorTickInterval)
                .minorTicksPerMajor(picked.minorTicksPerMajor)
                .labelFormat(picked.labelFormat)
                .build();
    }

    /** The link's value as progress along the face's scale; shared with the mounted form. */
    static float progress(EdgewiseFaceDefinition face, float value) {
        float span = face.maxValue - face.minValue;
        if (span == 0)
            return 0;
        return ProceduralGaugeBlockEntity.clampProgress((value - face.minValue) / span);
    }

    @Override
    protected float computeDialTarget() {
        if (!isPowered() || !hasValue)
            return 0;
        return progress(getFaceDefinition(), value);
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putString("Supply", getSupply().getSerializedName());
        tag.putFloat("Value", value);
        tag.putBoolean("HasValue", hasValue);
        tag.putString("LastText", lastText);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        supply = SupplyVoltage.byName(tag.getString("Supply"));
        value = tag.getFloat("Value");
        hasValue = tag.getBoolean("HasValue");
        lastText = tag.getString("LastText");
        if (connection != null)
            connection.setResistance(supply.loadResistance());
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        String prefix = SquareProceduralGaugeBlockEntity.GOGGLE_PREFIX;
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "header").withStyle(ChatFormatting.WHITE)));
        Component supplyState = isPowered()
                ? Component.translatable(prefix + "supply_ok").withStyle(ChatFormatting.GREEN)
                : Component.translatable(prefix + "supply_missing").withStyle(ChatFormatting.RED);
        tooltip.add(indent(Component.translatable(prefix + "supply",
                getSupply().label().copy().withStyle(ChatFormatting.AQUA), supplyState)
                .withStyle(ChatFormatting.GRAY)));
        // The display link's output verbatim, as on the Square Procedural Gauge
        if (lastText.isEmpty()) {
            tooltip.add(indent(Component.translatable(prefix + "no_data").withStyle(ChatFormatting.DARK_GRAY)));
            return true;
        }
        tooltip.add(indent(Component.literal(lastText).withStyle(ChatFormatting.WHITE)));
        return true;
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }
}
