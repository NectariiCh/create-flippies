package com.nectariich.create_flippies.client;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.indicator.IndicatorAim;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.PressIndicatorPacket;
import com.simibubi.create.AllItems;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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
 * Holding the use key on an indicator's cap, which is how a button is pressed.
 *
 * A press could have been an ordinary right click on the block, the way a rotary
 * switch's push is, and for a latching cap that would have been enough. What needs
 * this is the spring-return cap: it is closed for as long as the key is held, and
 * vanilla offers neither half of that. There is no event for a key coming up, and the
 * event for one going down is re-posted every few ticks while it is held — which
 * would toggle a latching button over and over and would tell a spring-return one
 * nothing it does not already know.
 *
 * So the client, which is the only side that can tell a held key from a new press,
 * says so explicitly: one packet as the key goes down, one as it comes up, and the
 * click that would have been delivered in between is swallowed. Reading the key state
 * on the tick rather than waiting for a release event is what makes letting go
 * reliable — the same reason {@link RotarySwitchDragInput} watches it that way — since
 * a press has to end when the player opens a screen or dies as surely as when they
 * take their finger off the button.
 *
 * Nothing here is trusted by the server beyond "this player clicked that cap". A
 * press whose release never arrives is let go of by the button itself, as soon as the
 * player who made it is no longer standing in front of it; see
 * {@code IndicatorButton.tick}.
 *
 * Only a cap that clicks is claimed. A plain pilot lamp leaves the button alone, so a
 * player with a block in hand can still build against one, and the screwdriver and the
 * wrench keep their own meanings on every unit — the one opens the configuration while
 * sneaking, the other is Create's.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class IndicatorPressInput {

    /** The cap this client is holding down, or null if it is holding none. */
    private static BlockPos pressedPos;

    /** Where on it the press landed, which is how the server tells caps apart. */
    private static Vec3 pressedLocal;

    private IndicatorPressInput() {}

    /**
     * Takes the use key on a button, and swallows the repeats vanilla posts while it
     * is held.
     *
     * The arm swings on the press itself, as an uncancelled click would, and is told
     * not to on the repeats — otherwise it would flap every few ticks for as long as
     * the button was held down.
     */
    @SubscribeEvent
    public static void onUseKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem())
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null)
            return;

        if (pressedPos != null) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
        }
        // Sneaking is the configuration menu and Create's dismantle; the wrench is
        // Create's turn. Neither is a press, and the screwdriver's own modes have
        // already claimed this click if they wanted it
        if (player.isShiftKeyDown() || AllItems.WRENCH.isIn(player.getItemInHand(event.getHand()))
                || ScrewdriverItem.heldBy(player) != null)
            return;
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
            return;

        BlockPos pos = hit.getBlockPos();
        Vec3 local = hit.getLocation().subtract(Vec3.atLowerCornerOf(pos));
        // The same call the server makes when the packet arrives, so a click this
        // swallows is a click that will do something
        if (!IndicatorAim.isButton(minecraft.level, pos, local))
            return;

        pressedPos = pos;
        pressedLocal = local;
        ModPackets.sendToServer(new PressIndicatorPacket(pos, local, true));
        event.setCanceled(true);
    }

    /** Lets go, on the tick the key comes up or the player stops being able to hold it. */
    @SubscribeEvent
    //? if forge {
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pressedPos == null)
            return;
    //?} else {
    /*public static void onClientTick(ClientTickEvent.Post event) {
        if (pressedPos == null)
            return;
    *///?}
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.level != null && minecraft.screen == null
                && minecraft.options.keyUse.isDown() && minecraft.level.isLoaded(pressedPos))
            return;
        release();
    }

    private static void release() {
        ModPackets.sendToServer(new PressIndicatorPacket(pressedPos, pressedLocal, false));
        pressedPos = null;
        pressedLocal = null;
    }
}
