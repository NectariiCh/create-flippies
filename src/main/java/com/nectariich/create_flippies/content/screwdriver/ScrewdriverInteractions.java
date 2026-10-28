package com.nectariich.create_flippies.content.screwdriver;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.ControlConfig;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.ScrewdriverActionPacket;
import com.nectariich.create_flippies.network.ScrewdriverAirPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * How a screwdriver click is taken away from whatever else the player is holding.
 *
 * {@code Item.useOn} would be the obvious hook and it does not work, because the
 * tool has to be usable with the control in either hand.
 * {@code Minecraft.startUseItem} tries the main hand first and stops as soon as
 * that hand consumes the click, so a control block item in the main hand places
 * itself as a block long before a screwdriver in the off hand is consulted.
 * {@link PlayerInteractEvent.RightClickBlock} fires once per hand before any of
 * {@code onItemUseFirst}, {@code BlockState.use} and {@code ItemStack.useOn}, and
 * on the main hand's pass regardless of what either hand is holding, which makes
 * it the only hook that can pre-empt the block item. Create uses the same event
 * for its value-settings interaction, so this is the sanctioned route rather than
 * a way round the item system.
 *
 * The handler runs on both sides and cancels on both, which is not belt and
 * braces. The client's cancellation stops the local prediction and stops the loop
 * ever reaching the off hand, but the vanilla use-on packet is sent from inside
 * {@code startPrediction} whatever the interaction returned, so the server holds
 * its own pass of the same event and would place the block item there if nothing
 * cancelled it. Both sides read the same stack NBT and reach the same verdict.
 *
 * The click itself travels as {@link ScrewdriverActionPacket} rather than being
 * carried out from the server's pass of the event. The event's hit position has
 * been through a float on its way through the vanilla packet, and the left-click
 * case has no server pass at all worth relying on, so one path that always sends
 * the crosshair position explicitly is both more accurate and the only one that
 * works for both buttons.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID)
public final class ScrewdriverInteractions {

    /**
     * How far the client re-casts to recover the exact aim for a left click. The
     * left-click event carries a block and a face and no position, so the point has
     * to come from somewhere; the range only has to exceed the reach vanilla
     * already allowed for the click to have happened at all.
     */
    private static final double PICK_RANGE = 8;

    private ScrewdriverInteractions() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // Only the main hand's pass. It always happens, it happens first, and
        // cancelling it is what stops the off hand from being tried at all
        if (event.getHand() != InteractionHand.MAIN_HAND)
            return;
        Player player = event.getEntity();
        InteractionHand hand = ScrewdriverItem.heldBy(player);
        if (hand == null)
            return;
        if (!ScrewdriverItem.claimsRightClick(player, hand, ScrewdriverSettings.read(player.getItemInHand(hand)))) {
            configure(event, player);
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!player.level().isClientSide)
            return;
        BlockHitResult hit = event.getHitVec();
        ModPackets.sendToServer(ScrewdriverActionPacket.of(hand, false, hit.getBlockPos(), hit.getDirection(),
                hit.getLocation()));
    }

    /**
     * The click that opens a mounted control's menu.
     *
     * It is offered the button only after the mode has turned it down, and that order
     * is the whole of the design. A right click in a ruling or editing mode is a
     * ruling or an edit, and a panel fitted out with instruments would otherwise be
     * the one place those modes could not be used — the controls would swallow every
     * click aimed anywhere near them. What is left over is the idle mode, and a
     * placement mode with nothing in the other hand to place: the cases where the
     * button was doing nothing at all. That is the one this takes.
     *
     * Sneaking is left alone, because it is the tool's own settings. The two used to
     * share the sneaking click, which cost the settings their gesture — see
     * {@link ScrewdriverItem}.
     *
     * Both sides run it and both cancel, for the reason the class comment gives.
     * {@link ControlConfig} is asked on each and reaches the same verdict, and
     * cancelling on the server is what stops the block underneath from answering the
     * click as well: an indicator is a button as much as a lamp, and a click meant
     * for its menu must not press it on the way.
     */
    private static void configure(PlayerInteractEvent.RightClickBlock event, Player player) {
        if (player.isShiftKeyDown())
            return;
        BlockPos pos = event.getPos();
        Vec3 local = event.getHitVec().getLocation().subtract(Vec3.atLowerCornerOf(pos));
        if (!ControlConfig.open(player.level(), pos, local, player))
            return;
        ScrewdriverItem.menuOpened(player);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * A right click with nothing under the crosshair, which the block event never
     * reports and which both the second corner of a dynamic block and an edit aimed
     * through a hole need.
     *
     * Never while sneaking, which belongs to the menu; see {@link #claimsAir} for how
     * narrowly each mode takes it.
     */
    @SubscribeEvent
    public static void onRightClickAir(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (player.isShiftKeyDown())
            return;
        InteractionHand hand = ScrewdriverItem.heldBy(player);
        if (hand == null)
            return;
        if (!claimsAir(ScrewdriverSettings.read(player.getItemInHand(hand))))
            return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (player.level().isClientSide)
            ModPackets.sendToServer(new ScrewdriverAirPacket(hand, false));
    }

    /**
     * A left click on nothing, which only the edit mode has any use for: a hole seen
     * against open sky is the one thing worth clicking that has no block behind it.
     *
     * The event is the client's alone — there is no server pass of it at all — so the
     * packet is the whole of the path rather than a shortcut around one.
     */
    @SubscribeEvent
    public static void onLeftClickAir(PlayerInteractEvent.LeftClickEmpty event) {
        Player player = event.getEntity();
        InteractionHand hand = ScrewdriverItem.heldBy(player);
        if (hand == null)
            return;
        if (ScrewdriverSettings.read(player.getItemInHand(hand)).mode() != ScrewdriverMode.EDIT_PANEL)
            return;
        ModPackets.sendToServer(new ScrewdriverAirPacket(hand, true));
    }

    /**
     * Whether a right click with nothing under the crosshair is the tool's.
     *
     * Claimed as narrowly as each mode allows. The dynamic gesture wants it only once
     * a first corner is waiting, since that is the click that finishes a panel
     * standing in the air; an edit wants every one of them, because a click aimed
     * through the gap where a tile used to be looks exactly like a click at the sky.
     * Every other mode leaves it, so a screwdriver in the hand still does nothing
     * surprising when the player waves it about.
     */
    private static boolean claimsAir(ScrewdriverSettings settings) {
        if (settings.mode() == ScrewdriverMode.EDIT_PANEL)
            return true;
        return settings.mode() == ScrewdriverMode.DEFINE_DYNAMIC_BLOCK && !settings.kind().picksPanels()
                && !settings.kind().picksEdge() && settings.firstPoint() != null;
    }

    /**
     * Who gets the left click while a screwdriver is held: the tool, break
     * protection, or the block.
     *
     * The tool used to take every one of them in every mode, which stopped the
     * mistimed click that takes a wall of instruments out and also stopped the
     * player clearing the wall they were building it on. The two are now separated —
     * the ruling modes claim the button because it is how a ruling or an area is
     * given back, and the placement modes leave it, with
     * {@link BreakProtection} standing in front of whatever the player has said is
     * finished.
     *
     * Both sides run this and both cancel, which is not belt and braces: the client
     * has to stop the swing before the block visibly breaks, and the server has to
     * stop it whatever the client did. They reach the same verdict because they read
     * the same stack NBT and the same protection mirror, which is a snapshot of the
     * server's own store.
     */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        InteractionHand hand = ScrewdriverItem.heldBy(player);
        if (hand == null)
            return;
        boolean claimed = ScrewdriverSettings.read(player.getItemInHand(hand)).mode().claimsLeftClick();
        if (!claimed && !BreakProtection.stops(player, event.getPos()))
            return;

        event.setCanceled(true);

        Direction face = event.getFace();
        // Held buttons repeat this event every tick; one action, or one refusal,
        // per press
        if (face == null || !player.level().isClientSide
                || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START)
            return;
        if (!claimed) {
            BreakProtection.refuse(player);
            return;
        }
        BlockHitResult hit = aimedAt(player, event.getPos(), face);
        if (hit == null)
            return;
        ModPackets.sendToServer(
                ScrewdriverActionPacket.of(hand, true, event.getPos(), face, hit.getLocation()));
    }

    /**
     * The crosshair, re-cast, or null if it is no longer on the face the event
     * named — which means the player turned between the click and this handler, and
     * acting on a stale face would take away a grid they were not pointing at.
     */
    private static BlockHitResult aimedAt(Player player, BlockPos pos, Direction face) {
        HitResult hit = player.pick(PICK_RANGE, 1.0f, false);
        if (!(hit instanceof BlockHitResult block))
            return null;
        return block.getBlockPos().equals(pos) && block.getDirection() == face ? block : null;
    }
}
