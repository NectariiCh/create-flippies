package com.nectariich.create_flippies.content.procedural_gauge;

import com.nectariich.create_flippies.registry.ModBlockEntities;

import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Edgewise Procedural Voltmeter: the edgewise gauge reading the voltage across its
 * own terminals. Each measurement range is its own block, as the Square voltmeters'
 * are; the geometry, terminals and face menu are {@link EdgewiseGaugeBlock}'s.
 */
public class EdgewiseVoltmeterBlock extends EdgewiseGaugeBlock<EdgewiseVoltmeterBlockEntity> {

    private final VoltmeterRange range;

    public EdgewiseVoltmeterBlock(VoltmeterRange range, Properties properties) {
        super(properties);
        this.range = range;
    }

    /** The fixed measurement range of this block variant. */
    public VoltmeterRange getRange() {
        return range;
    }

    @Override
    public Class<EdgewiseVoltmeterBlockEntity> getBlockEntityClass() {
        return EdgewiseVoltmeterBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends EdgewiseVoltmeterBlockEntity> getBlockEntityType() {
        return ModBlockEntities.EDGEWISE_PROCEDURAL_VOLTMETER.get();
    }
}
