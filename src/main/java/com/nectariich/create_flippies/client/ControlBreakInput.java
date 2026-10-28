package com.nectariich.create_flippies.client;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverSettings;
import com.nectariich.create_flippies.network.BreakControlPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
//? if forge {
import net.minecraftforge.event.TickEvent;
//?} else {
/*import net.neoforged.neoforge.client.event.ClientTickEvent;
*///?}
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Turns a left click on a panel into the removal of the one control under the
 * crosshair.
 *
 * The attack key is taken rather than
 * {@link net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock},
 * which would be the obvious hook and cannot do the job: the client only fires that
 * event on the path a survival player takes towards breaking a block, so in creative
 * — where the click is instant — it never fires at all and nothing would be removed.
 * This event is posted from the top of {@code Minecraft.startAttack}, before the game
 * mode is consulted, so it happens once per press in every mode. Cancelling it is what
 * stops the press from reaching {@code startDestroyBlock}; the arm still swings,
 * because unscrewing something is a hand movement.
 *
 * What was aimed at comes from {@code Minecraft.hitResult}, which is the very thing
 * {@code startAttack} would have switched on, so this claims a click exactly when
 * vanilla would have used it to break the panel and never when vanilla would have
 * done something else with it — a mob standing in front of a panel is still hit
 * rather than being shot through. It is also the ray the protruding-hitbox pick
 * extends, which is what makes a control's contact rack aimable from the cell behind
 * the panel.
 *
 * The point travels to the server explicitly because breaking gives the server a block
 * position and nothing finer, so a host wearing half a dozen instruments could not
 * otherwise be asked which one was meant. That is the same reason the screwdriver
 * sends its own clicks.
 *
 * Holding a screwdriver does not stop this any more — only its ruling modes do,
 * because there the button already means "take that ruling away". Unscrewing a
 * control with a screwdriver in hand is the obvious reading of the gesture, and it
 * is the one thing the tool refuses inside a protected area.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class ControlBreakInput {

    /** Said above the hotbar when a click lands on a control that must not come off. */
    private static final String PROTECTED = "message." + CreateFlippies.MOD_ID + ".control.protected";

    /**
     * Whether the attack key has been let go since the last unscrew. Forge posts the
     * attack event from {@code continueAttack} as well, every tick the button is
     * held, so without this one ordinary click sent a removal per tick it stayed
     * down — harmless while each control was one thing, but it took a relay and
     * then its mount (or a control and the next one behind it) in a single click.
     */
    private static boolean released = true;

    private ControlBreakInput() {}

    @SubscribeEvent
    //? if forge {
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !Minecraft.getInstance().options.keyAttack.isDown())
            released = true;
    //?} else {
    /*public static void onClientTick(ClientTickEvent.Post event) {
        if (!Minecraft.getInstance().options.keyAttack.isDown())
            released = true;
    *///?}
    }

    @SubscribeEvent
    public static void onAttack(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack())
            return;
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        // Adventure and spectator break nothing, and the server would refuse anyway
        if (player == null || !player.mayBuild())
            return;
        // The ruling modes take every left click for themselves; the placement modes
        // leave it, and on a panel it means unscrewing the one control under the
        // crosshair — which is what the tool is for and finer than breaking the block
        InteractionHand tool = ScrewdriverItem.heldBy(player);
        if (tool != null && ScrewdriverSettings.read(player.getItemInHand(tool)).mode().claimsLeftClick())
            return;

        HitResult crosshair = minecraft.hitResult;
        if (!(crosshair instanceof BlockHitResult block) || block.getType() != HitResult.Type.BLOCK)
            return;
        // Only a click that is actually on a control unscrews one. An empty frame is
        // left to vanilla, which is the only way one can be removed; and a panel cell
        // carrying a control anywhere on it stays minable everywhere else on it, which
        // it would not be if merely holding something claimed every click
        HostedControl aimed = player.level().getBlockEntity(block.getBlockPos()) instanceof ControlRackHolder host
                ? host.controlAt(crosshair.getLocation().subtract(Vec3.atLowerCornerOf(block.getBlockPos())))
                : null;
        if (aimed == null)
            return;

        // Claimed either way: a locked control must not come off, and must not let
        // the click through to mine the panel it is screwed to either
        event.setCanceled(true);
        if (aimed.isLocked()) {
            player.displayClientMessage(Component.translatable(PROTECTED).withStyle(ChatFormatting.RED), true);
            return;
        }
        // Still claimed while held, so vanilla cannot start mining, but one removal a press
        if (!released)
            return;
        released = false;
        ModPackets.sendToServer(new BreakControlPacket(block.getBlockPos(), crosshair.getLocation()));
    }
}
