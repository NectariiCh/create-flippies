package com.nectariich.create_flippies.content.design_workbench;

import java.text.DecimalFormat;
import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.procedural_gauge.GaugeScale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * A control made at the Design Workbench. What it is lives in its NBT, so it is a
 * plain item rather than a block item: it goes on with the screwdriver like every
 * other mounted control, and the kind it mounts as is named by the item alone.
 */
public class DesignedControlItem extends Item {

    private final DesignKind kind;

    public DesignedControlItem(DesignKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public DesignKind kind() {
        return kind;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        ControlDesign design = ControlDesign.of(stack);
        if (design == null) {
            tooltip.add(Component.translatable("item." + CreateFlippies.MOD_ID + ".designed.blank")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        tooltip.add(Component.literal(design.name()).withStyle(ChatFormatting.GRAY));
        if (kind.isButton())
            tooltip.add(design.rating().label().copy().withStyle(ChatFormatting.DARK_GRAY));
        if (design.screen() != null) {
            DecimalFormat number = GaugeScale.format("0.#");
            tooltip.add(design.screen().supply.label().copy().withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(Component.translatable("item." + CreateFlippies.MOD_ID + ".designed_screen.viewport",
                    number.format(design.screen().viewport().widthPixels()),
                    number.format(design.screen().viewport().heightPixels())).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (design.rack() != null)
            tooltip.add(Component.translatable("item." + CreateFlippies.MOD_ID + ".designed_annunciator.windows",
                    design.rack().cellsX, design.rack().cellsY).withStyle(ChatFormatting.DARK_GRAY));
        if (design.gauge() != null) {
            DecimalFormat number = GaugeScale.format("0.###");
            tooltip.add(Component.translatable("item." + CreateFlippies.MOD_ID + ".designed_gauge.range",
                    design.gauge().quantity.label(), number.format(design.gauge().minValue()),
                    number.format(design.gauge().maxValue()), design.gauge().units())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
