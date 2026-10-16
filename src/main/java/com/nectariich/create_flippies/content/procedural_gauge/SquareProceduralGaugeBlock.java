package com.nectariich.create_flippies.content.procedural_gauge;

import com.nectariich.create_flippies.registry.ModBlockEntities;

import net.minecraft.world.level.block.entity.BlockEntityType;

public class SquareProceduralGaugeBlock extends ProceduralGaugeBlock<SquareProceduralGaugeBlockEntity> {

    public SquareProceduralGaugeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Class<SquareProceduralGaugeBlockEntity> getBlockEntityClass() {
        return SquareProceduralGaugeBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends SquareProceduralGaugeBlockEntity> getBlockEntityType() {
        return ModBlockEntities.SQUARE_PROCEDURAL_GAUGE.get();
    }
}
