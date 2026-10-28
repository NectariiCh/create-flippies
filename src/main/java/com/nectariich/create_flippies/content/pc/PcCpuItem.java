package com.nectariich.create_flippies.content.pc;

import java.util.List;

import com.nectariich.create_flippies.compat.StackData;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * One CPU at one clock (spec 11.2.3). A 68040 also carries the strict FPU option in
 * NBT ({@code StrictFpu}, default off), toggled by sneaking and using the item in
 * the air; it becomes {@code MachineConfig.strictFpu}.
 */
public class PcCpuItem extends PcPartItem {

    public static final String TAG_STRICT_FPU = "StrictFpu";

    private final PcCpuType type;

    public PcCpuItem(PcCpuType type, Properties properties) {
        super(Kind.CPU, properties);
        this.type = type;
    }

    public PcCpuType type() {
        return type;
    }

    public static boolean strictFpu(ItemStack stack) {
        return stack.getItem() instanceof PcCpuItem cpu && cpu.type.hasStrictFpuOption() && StackData.peek(stack) != null
                && StackData.peek(stack).getBoolean(TAG_STRICT_FPU);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown() || !type.hasStrictFpuOption())
            return InteractionResultHolder.pass(stack);
        if (!level.isClientSide) {
            boolean strict = !strictFpu(stack);
            StackData.edit(stack, tag -> tag.putBoolean(TAG_STRICT_FPU, strict));
            player.displayClientMessage(Component.translatable(strict
                    ? "gui.create_flippies.pc.tooltip.strict_fpu_on" : "gui.create_flippies.pc.tooltip.strict_fpu_off"),
                    true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal(type.displayName()).withStyle(ChatFormatting.GRAY));
        if (type.hasStrictFpuOption())
            tooltip.add(Component.translatable(strictFpu(stack)
                    ? "gui.create_flippies.pc.tooltip.strict_fpu_on" : "gui.create_flippies.pc.tooltip.strict_fpu_off")
                    .withStyle(ChatFormatting.DARK_GRAY));
    }
}
