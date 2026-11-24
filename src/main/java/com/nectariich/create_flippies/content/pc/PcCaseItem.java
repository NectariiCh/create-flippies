package com.nectariich.create_flippies.content.pc;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.device.DeviceEntity;
import com.nectariich.create_flippies.content.device.DeviceItem;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** The PC case's item: placed as any device is, and a case item carrying a snapshot (spec 12.19) places that PC. */
public class PcCaseItem extends DeviceItem {

    public PcCaseItem(Supplier<? extends EntityType<?>> type, Properties properties) {
        super(type, PcCaseEntity::new, properties);
    }

    @Override
    protected void placed(DeviceEntity device, ItemStack stack, Player player) {
        if (!PcSnapshots.isSnapshotItem(stack))
            return;
        PcServer server = PcServer.get();
        if (server != null && device instanceof PcCaseEntity pc)
            server.snapshots().place(pc, stack, player instanceof ServerPlayer serverPlayer ? serverPlayer : null);
    }
}
