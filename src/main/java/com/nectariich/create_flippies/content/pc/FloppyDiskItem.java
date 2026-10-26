package com.nectariich.create_flippies.content.pc;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * A 1.44 MB floppy disk (spec 11.2.3): a {@code DiskId} assigned on first insert,
 * the label it was given in an anvil, and the write-protect tab
 * ({@code WriteProtect}), toggled by sneaking and using it in the air. A disk with
 * {@code Preset} (spec 12.5) gets that bundled image on its first insert
 * ({@link FloppyPresets}) and is named after it until it is writable.
 */
public class FloppyDiskItem extends PcPartItem {

    public static final String TAG_WRITE_PROTECT = "WriteProtect";
    public static final String TAG_PRESET = "Preset";

    public FloppyDiskItem(Properties properties) {
        super(Kind.FLOPPY, properties);
    }

    public static boolean writeProtected(ItemStack stack) {
        return stack.hasTag() && stack.getTag().getBoolean(TAG_WRITE_PROTECT);
    }

    /** The bundled image this disk was made from, or null for a blank one. */
    public static String preset(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(TAG_PRESET) ? stack.getTag().getString(TAG_PRESET) : null;
    }

    /** A new, never inserted, write-protected disk of a preset, as the creative tab lists it. */
    public static ItemStack presetStack(net.minecraft.world.item.Item item, String preset) {
        ItemStack stack = new ItemStack(item);
        stack.getOrCreateTag().putString(TAG_PRESET, preset);
        stack.getTag().putBoolean(TAG_WRITE_PROTECT, true);
        return stack;
    }

    /**
     * On a preset disk's first insert (it has no {@code DiskId} yet): a new disk id holding the
     * bundled image. A preset that is not bundled or not readable leaves a blank disk, logged.
     * Server only.
     */
    static void fillFromPreset(ItemStack stack, DiskStore disks) {
        String preset = preset(stack);
        if (preset == null || PcDiskItems.idOf(stack) != null)
            return;
        byte[] image;
        try {
            image = FloppyPresets.load(FloppyPresets.class.getClassLoader(), preset);
        } catch (java.io.IOException | IllegalArgumentException e) {
            com.nectariich.create_flippies.CreateFlippies.LOGGER.warn("Floppy preset {} not loaded", preset, e);
            return;
        }
        if (image == null) {
            com.nectariich.create_flippies.CreateFlippies.LOGGER.warn("Floppy preset {} is not bundled ({})", preset,
                    FloppyPresets.resource(preset));
            return;
        }
        var disk = disks.acquire(PcDiskItems.assignId(stack),
                com.nectariich.create_flippies.content.computer.device.PagedDiskImage.FLOPPY_CAPACITY);
        FloppyPresets.copyInto(disk, image);
        disks.release(disk);
        FloppyPresets.settle(stack.getTag());
    }

    @Override
    public Component getName(ItemStack stack) {
        String preset = preset(stack);
        return preset == null ? super.getName(stack)
                : Component.translatable(getDescriptionId(stack) + ".preset." + preset);
    }

    /** The anvil name, or null for an unlabelled disk. */
    public static String label(ItemStack stack) {
        return stack.hasCustomHoverName() ? stack.getHoverName().getString() : null;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown())
            return InteractionResultHolder.pass(stack);
        if (!level.isClientSide) {
            boolean protect = !writeProtected(stack);
            stack.getOrCreateTag().putBoolean(TAG_WRITE_PROTECT, protect);
            FloppyPresets.settle(stack.getTag());
            player.displayClientMessage(Component.translatable(protect
                    ? "gui.create_flippies.pc.tooltip.write_protected" : "gui.create_flippies.pc.tooltip.writable"),
                    true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(writeProtected(stack)
                ? "gui.create_flippies.pc.tooltip.write_protected" : "gui.create_flippies.pc.tooltip.writable")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("gui.create_flippies.pc.tooltip.disk_id",
                PcDiskItems.shortId(PcDiskItems.idOf(stack))).withStyle(ChatFormatting.DARK_GRAY));
    }
}
