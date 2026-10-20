package com.nectariich.create_flippies.content.pc;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** A hard disk (spec 11.2.3). Its image is in the disk store under its {@code DiskId}. */
public class PcHddItem extends PcPartItem {

    private final PcHddType type;

    public PcHddItem(PcHddType type, Properties properties) {
        super(Kind.HDD, properties);
        this.type = type;
    }

    public PcHddType type() {
        return type;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal(PcRamRules.format(type.bytes())).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("gui.create_flippies.pc.tooltip.disk_id",
                PcDiskItems.shortId(PcDiskItems.idOf(stack))).withStyle(ChatFormatting.DARK_GRAY));
    }
}
