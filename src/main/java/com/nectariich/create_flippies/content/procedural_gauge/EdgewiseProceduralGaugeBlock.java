package com.nectariich.create_flippies.content.procedural_gauge;

import com.nectariich.create_flippies.registry.ModBlockEntities;

import net.minecraft.world.level.block.entity.BlockEntityType;

public class EdgewiseProceduralGaugeBlock extends EdgewiseGaugeBlock<EdgewiseProceduralGaugeBlockEntity> {

    public EdgewiseProceduralGaugeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Class<EdgewiseProceduralGaugeBlockEntity> getBlockEntityClass() {
        return EdgewiseProceduralGaugeBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends EdgewiseProceduralGaugeBlockEntity> getBlockEntityType() {
        return ModBlockEntities.EDGEWISE_PROCEDURAL_GAUGE.get();
    }
}
