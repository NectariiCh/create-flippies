package com.nectariich.create_flippies.content.procedural_gauge;

import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.registry.ModBlockEntities;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Edgewise Procedural Voltmeter: the voltage between its two grid terminals,
 * through the same high internal resistance as the Square voltmeter, shown by a
 * pointer sliding along a procedurally printed edgewise face.
 *
 * As on the Square voltmeter, the printed face can be relabelled freely from its
 * menu while the electrical range cannot: the pointer always shows the measured
 * voltage normalized over the variant's range.
 */
public class EdgewiseVoltmeterBlockEntity extends EdgewiseGaugeBlockEntity {

    /** Shared with {@link EdgewiseVoltmeterControl}, so both forms read alike. */
    public static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".edgewise_voltmeter.goggles.";

    public EdgewiseVoltmeterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.EDGEWISE_PROCEDURAL_VOLTMETER.get(), pos, state);
    }

    @Override
    protected float internalResistance() {
        return ProceduralVoltmeterBlockEntity.INTERNAL_RESISTANCE;
    }

    /** The hardware measurement range, fixed by which block variant this is. */
    public VoltmeterRange getRange() {
        return getBlockState().getBlock() instanceof EdgewiseVoltmeterBlock block
                ? block.getRange()
                : VoltmeterRange.V0_12;
    }

    @Override
    public EdgewiseFaceDefinition defaultFaceDefinition() {
        return getRange().edgewiseFace();
    }

    @Override
    protected float computeDialTarget() {
        VoltmeterRange range = getRange();
        float span = range.max() - range.min();
        if (span == 0)
            return 0;
        return ProceduralGaugeBlockEntity.clampProgress((measuredVoltage() - range.min()) / span);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        String prefix = ProceduralVoltmeterBlockEntity.GOGGLE_PREFIX;
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "header").withStyle(ChatFormatting.WHITE)));
        tooltip.add(indent(Component.translatable(prefix + "voltage",
                Component.literal(String.format("%.2f", measuredVoltage())).withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY)));
        tooltip.add(indent(Component.translatable(prefix + "range",
                getRange().label().copy().withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY)));
        if (hasCustomFace())
            tooltip.add(indent(Component.translatable(prefix + "custom_face").withStyle(ChatFormatting.DARK_GRAY)));
        return true;
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }
}
