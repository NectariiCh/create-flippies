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
 * Square Procedural Gauge: a display link receiver like the Universal Gauge, but
 * with a procedurally printed dial face and a supply requirement — the needle
 * only responds while the terminals are fed roughly the rated 5, 12 or 24 V.
 * The dial's scale min/max double as the expected data range of the link.
 */
public class SquareProceduralGaugeBlockEntity extends ProceduralGaugeBlockEntity {

    /** Shared with {@link SquareProceduralGaugeControl}, so both forms read alike. */
    public static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".procedural_gauge.goggles.";

    private static final GaugeFaceDefinition DEFAULT_FACE = GaugeFaceDefinition.autoScaled(0, 100)
            .displayName("Square")
            .build();

    // May still be null inside the super constructor (buildCircuit runs there,
    // before this initializer) — always read through getSupply().
    private SupplyVoltage supply = SupplyVoltage.V12;

    private float value = 0;
    private boolean hasValue = false;
    private String lastText = "";

    public SquareProceduralGaugeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SQUARE_PROCEDURAL_GAUGE.get(), pos, state);
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
    public GaugeFaceDefinition defaultFaceDefinition() {
        return DEFAULT_FACE;
    }

    /** The printing this gauge ships with, in either of the forms it takes. */
    public static GaugeFaceDefinition defaultFace() {
        return DEFAULT_FACE;
    }

    @Override
    protected float computeDialTarget() {
        if (!isPowered() || !hasValue)
            return 0;
        GaugeFaceDefinition face = getFaceDefinition();
        float span = face.maxValue - face.minValue;
        if (span == 0)
            return 0;
        return clampProgress((value - face.minValue) / span);
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putString("Supply", supply.getSerializedName());
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
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "header").withStyle(ChatFormatting.WHITE)));
        Component supplyState = isPowered()
                ? Component.translatable(GOGGLE_PREFIX + "supply_ok").withStyle(ChatFormatting.GREEN)
                : Component.translatable(GOGGLE_PREFIX + "supply_missing").withStyle(ChatFormatting.RED);
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "supply",
                supply.label().copy().withStyle(ChatFormatting.AQUA), supplyState)
                .withStyle(ChatFormatting.GRAY)));
        // The display link's output verbatim, nothing else: no label, and no
        // restating the number pulled out of it or the range, both of which the
        // dial face already shows.
        if (lastText.isEmpty()) {
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "no_data")
                    .withStyle(ChatFormatting.DARK_GRAY)));
            return true;
        }
        tooltip.add(indent(Component.literal(lastText).withStyle(ChatFormatting.WHITE)));
        return true;
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }
}
