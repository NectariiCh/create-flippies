package com.nectariich.create_flippies.content.procedural_gauge;

import com.nectariich.create_flippies.registry.ModBlockEntities;

import net.minecraft.world.level.block.entity.BlockEntityType;

public class ProceduralVoltmeterBlock extends ProceduralGaugeBlock<ProceduralVoltmeterBlockEntity> {

    private final VoltmeterRange range;

    public ProceduralVoltmeterBlock(VoltmeterRange range, Properties properties) {
        super(properties);
        this.range = range;
    }

    /** The fixed measurement range of this block variant. */
    public VoltmeterRange getRange() {
        return range;
    }

    @Override
    public Class<ProceduralVoltmeterBlockEntity> getBlockEntityClass() {
        return ProceduralVoltmeterBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ProceduralVoltmeterBlockEntity> getBlockEntityType() {
        return ModBlockEntities.SQUARE_PROCEDURAL_VOLTMETER.get();
    }
}
