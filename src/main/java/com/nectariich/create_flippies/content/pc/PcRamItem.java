package com.nectariich.create_flippies.content.pc;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** A RAM stick (spec 11.2.3); two slots per case. */
public class PcRamItem extends PcPartItem {

    private final PcRamType type;

    public PcRamItem(PcRamType type, Properties properties) {
        super(Kind.RAM, properties);
        this.type = type;
    }

    public PcRamType type() {
        return type;
    }

    public static PcRamType typeOf(ItemStack stack) {
        return stack.getItem() instanceof PcRamItem ram ? ram.type : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal(PcRamRules.format(type.bytes())).withStyle(ChatFormatting.GRAY));
    }
}
