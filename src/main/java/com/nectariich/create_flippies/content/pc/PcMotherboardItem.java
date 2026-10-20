package com.nectariich.create_flippies.content.pc;

import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The motherboard: every port and the network card are on it (spec 11.2.3). It
 * carries its MAC address in NBT ({@code Mac}), assigned the first time it is
 * installed, so a board keeps its address when it moves to another case.
 */
public class PcMotherboardItem extends PcPartItem {

    public static final String TAG_MAC = "Mac";

    public PcMotherboardItem(Properties properties) {
        super(Kind.MOTHERBOARD, properties);
    }

    /** The board's MAC, assigning one first if it has none. */
    public static long macOf(ItemStack stack, RandomSource random) {
        if (stack.hasTag() && stack.getTag().contains(TAG_MAC))
            return stack.getTag().getLong(TAG_MAC);
        long mac = randomMac(random.nextLong());
        stack.getOrCreateTag().putLong(TAG_MAC, mac);
        return mac;
    }

    /**
     * A locally administered unicast address (spec 11.1.7): byte 0, the most
     * significant of the 48 bits, has bit 1 set and bit 0 clear.
     */
    public static long randomMac(long bits) {
        long mac = bits & 0xFFFF_FFFF_FFFFL;
        long first = (mac >>> 40) & 0xFC | 0x02;
        return (first << 40) | (mac & 0xFF_FFFF_FFFFL);
    }

    /** "02:1a:2b:3c:4d:5e". */
    public static String formatMac(long mac) {
        StringBuilder s = new StringBuilder();
        for (int i = 5; i >= 0; i--) {
            s.append(String.format(Locale.ROOT, "%02x", (mac >>> (i * 8)) & 0xFF));
            if (i > 0)
                s.append(':');
        }
        return s.toString();
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        if (stack.hasTag() && stack.getTag().contains(TAG_MAC))
            tooltip.add(Component.translatable("gui.create_flippies.pc.tooltip.mac",
                    formatMac(stack.getTag().getLong(TAG_MAC))).withStyle(ChatFormatting.GRAY));
    }
}
