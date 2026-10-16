package com.nectariich.create_flippies.content.procedural_gauge;

import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.registry.ModBlockEntities;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Square Procedural Voltmeter: measures the voltage between its two grid
 * terminals through a high internal resistance and displays it on a
 * procedurally printed dial. The electrical range is fixed per block variant;
 * the printed face can be relabeled freely from its menu — the needle always
 * shows the measured voltage normalized over the variant's range.
 */
public class ProceduralVoltmeterBlockEntity extends ProceduralGaugeBlockEntity {

    /**
     * Both shared with {@link ProceduralVoltmeterControl}, so that a voltmeter
     * mounted inside a host block measures and reads identically to one placed as a
     * block of its own.
     */
    public static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".procedural_voltmeter.goggles.";
    public static final float INTERNAL_RESISTANCE = 1_000_000f;

    public ProceduralVoltmeterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SQUARE_PROCEDURAL_VOLTMETER.get(), pos, state);
    }

    @Override
    protected float internalResistance() {
        return INTERNAL_RESISTANCE;
    }

    /** The hardware measurement range, fixed by which block variant this is. */
    public VoltmeterRange getRange() {
        return getBlockState().getBlock() instanceof ProceduralVoltmeterBlock block
                ? block.getRange()
                : VoltmeterRange.V0_12;
    }

    @Override
    public GaugeFaceDefinition defaultFaceDefinition() {
        return getRange().face();
    }

    @Override
    protected float computeDialTarget() {
        VoltmeterRange range = getRange();
        float span = range.max() - range.min();
        if (span == 0)
            return 0;
        return clampProgress((measuredVoltage() - range.min()) / span);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "header").withStyle(ChatFormatting.WHITE)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "voltage",
                Component.literal(String.format("%.2f", measuredVoltage())).withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "range",
                getRange().label().copy().withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY)));
        if (hasCustomFace())
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "custom_face")
                    .withStyle(ChatFormatting.DARK_GRAY)));
        return true;
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }
}
