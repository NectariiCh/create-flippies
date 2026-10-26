package com.nectariich.create_flippies.content.screwdriver;

import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.decal.DecalSelection;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowDraft;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowSettings;
import com.nectariich.create_flippies.content.wire_duct.WireDuctItem;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/**
 * The tool that rules faces into cells and screws controls into them.
 *
 * The item itself is deliberately thin. Its clicks are not handled here at all
 * but in {@link ScrewdriverInteractions}, because the tool has to work with the
 * control in either hand and {@code useOn} is only reached for the hand vanilla is
 * currently trying — a block item in the main hand would have placed itself long
 * before the screwdriver in the off hand was consulted. What is left for the item
 * is the two things vanilla does route here: the sneaking click that opens the
 * configuration, and the tooltip.
 *
 * Both sneak paths are needed and neither covers the other. {@code use} is reached
 * by a sneaking right click on nothing, {@code useOn} by one on a block; vanilla
 * skips a block's own use method while sneaking but still offers the item its
 * turn, so the two together mean the menu opens wherever the player happens to be
 * pointing. The zapper does exactly this, for exactly this reason.
 *
 * The sneaking click is the tool's alone. Configuring a control used to share it,
 * and sharing cost the settings their gesture: a control under the crosshair took
 * the click, so in front of a panel already fitted with instruments the only way to
 * reach the tool's own menu was to turn round and point at the floor. A control's
 * menu is opened by a plain right click instead, in {@link ScrewdriverInteractions},
 * where the tool's modes are already given first refusal on that button. One gesture
 * each, and neither can take the other's.
 */
public class ScrewdriverItem extends Item {

    /**
     * Ticks the item is unusable for after the menu opens. Without it, the click
     * that opens the menu is also delivered to whatever is under the crosshair the
     * instant the menu closes on a held button.
     */
    private static final int MENU_COOLDOWN = 10;

    private static final String LANG_PREFIX = "item." + CreateFlippies.MOD_ID + ".screwdriver.";

    public ScrewdriverItem(Properties properties) {
        super(properties);
    }

    public static boolean isScrewdriver(ItemStack stack) {
        return stack.is(ModItems.SCREWDRIVER.get());
    }

    /**
     * Which hand holds a screwdriver, or null if neither does. The main hand wins a
     * tie, which only arises with one in each hand — an arrangement with nothing to
     * place, and so one the placement modes decline anyway.
     */
    public static InteractionHand heldBy(Player player) {
        if (isScrewdriver(player.getItemInHand(InteractionHand.MAIN_HAND)))
            return InteractionHand.MAIN_HAND;
        if (isScrewdriver(player.getItemInHand(InteractionHand.OFF_HAND)))
            return InteractionHand.OFF_HAND;
        return null;
    }

    public static InteractionHand otherHand(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    /**
     * Marks the tool as having just opened a menu, whichever gesture asked for one.
     *
     * A mounted control's editor is opened from {@link ScrewdriverInteractions}
     * rather than from the item, because the click that asks for it is taken from the
     * event and never reaches {@code useOn} — so the cooldown the two share has to be
     * reachable from outside. See {@link #MENU_COOLDOWN} for what it is for.
     */
    public static void menuOpened(Player player) {
        player.getCooldowns().addCooldown(ModItems.SCREWDRIVER.get(), MENU_COOLDOWN);
    }

    /**
     * Whether the screwdriver wants this right click rather than whatever else the
     * player is holding.
     *
     * A sneaking click never counts: that is the menu, and the menu has to remain
     * reachable however the tool is configured. The ruling modes claim every other
     * click, because ruling is the only thing they do. A placement mode claims only
     * when the other hand actually holds a control or a wire duct, so a screwdriver in the pocket
     * does not stop the player from opening a door — and the idle mode claims
     * nothing at all.
     */
    public static boolean claimsRightClick(Player player, InteractionHand hand, ScrewdriverSettings settings) {
        if (player.isShiftKeyDown())
            return false;
        if (!settings.mode().claimsRightClick())
            return false;
        if (!settings.mode().places())
            return true;
        ItemStack other = player.getItemInHand(otherHand(hand));
        return ControlTypes.forItem(other) != null || other.getItem() instanceof WireDuctItem;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown())
            return InteractionResultHolder.pass(stack);
        // A duct half laid is let go of first; the menu waits for the next sneaking click
        if (WireDuctItem.cancel(player))
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        if (level.isClientSide)
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.nectariich.create_flippies.client.ScrewdriverScreen.open(hand));
        menuOpened(player);
        return InteractionResultHolder.success(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown())
            return super.useOn(context);
        if (WireDuctItem.cancel(player))
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        if (context.getLevel().isClientSide)
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.nectariich.create_flippies.client.ScrewdriverScreen.open(context.getHand()));
        menuOpened(player);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        ScrewdriverSettings settings = ScrewdriverSettings.read(stack);
        tooltip.add(settings.mode().label().copy().withStyle(ChatFormatting.GOLD));
        int[] division = settings.division();
        if (settings.mode().places())
            tooltip.add(Component.translatable(LANG_PREFIX + "division", division[0], division[1])
                    .withStyle(ChatFormatting.GRAY));
        else if (settings.mode() == ScrewdriverMode.DEFINE_GRID)
            tooltip.add(Component.translatable(LANG_PREFIX + "tracks", settings.columns(), settings.rows())
                    .withStyle(ChatFormatting.GRAY));
        else if (settings.mode() == ScrewdriverMode.EDIT_PANEL) {
            tooltip.add(settings.editKind().label().copy().withStyle(ChatFormatting.GRAY));
            // A tile is the panel's own square and takes no size from the tool
            if (settings.editKind().sized())
                tooltip.add(Component.translatable(LANG_PREFIX + "step", settings.step())
                        .withStyle(ChatFormatting.GRAY));
        }
        else if (settings.mode() == ScrewdriverMode.PAINT_DECAL) {
            DecalSelection decal = DecalSelection.read(stack);
            tooltip.add((decal.hasArt() ? Component.translatable(LANG_PREFIX + "paint_art", decal.name())
                    : Component.translatable(LANG_PREFIX + "paint_no_art")).withStyle(ChatFormatting.GRAY));
        }
        // SCHEMATIC ARROWS
        else if (settings.mode() == ScrewdriverMode.SCHEMATIC_ARROW)
            tooltip.add(Component.translatable(LANG_PREFIX + "arrow_action", Component.translatable(
                    "gui." + CreateFlippies.MOD_ID + ".screwdriver.arrow.action." + ArrowSettings.read(stack)
                            .action().key())).withStyle(ChatFormatting.GRAY));
        if (settings.isPicking() || ArrowDraft.read(stack) != null)
            tooltip.add(Component.translatable(LANG_PREFIX + "corner_pending").withStyle(ChatFormatting.DARK_AQUA));
    }
}
