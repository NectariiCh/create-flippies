package com.nectariich.create_flippies.client;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchAim;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchSide;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchSpring;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchTurnMode;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;
import com.nectariich.create_flippies.foundation.ClientConfig;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.TurnRotarySwitchPacket;
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
 * The inverted push: a click on one half of a rotary switch's front brings the
 * handle to that half, instead of driving it away as a real knob does.
 *
 * The honest gesture — {@link RotarySwitchTurnMode#PUSH}, and the default — needs
 * nothing on the client at all. It is an ordinary right click on a block, and
 * {@code RotarySwitchBlock#use} works the side out again from where the click
 * landed. That is what this class has to get out of the way of: if the server were
 * left to turn the switch its own way and this sent a notch back the other way, the
 * two would cancel and the switch would sit still. So the click is swallowed here
 * and one notch is sent explicitly, the way a drag sends its notches.
 *
 * <h2>What the server is told</h2>
 *
 * The same thing either mode would have sent: a switch, and a direction. The
 * preference never leaves this client, so it needs no config to sync, no permission
 * to set, and two players at one panel may each be clicking the way they like on the
 * same switch. {@link TurnRotarySwitchPacket} re-checks reach and interaction
 * permission and applies the notch to whatever position the server already had, so
 * nothing here is trusted beyond "this player clicked that switch".
 *
 * <h2>What counts as a push</h2>
 *
 * Every guard below is one the server would have applied a moment later, asked in
 * the same order and — for the one that matters — through the same call.
 * {@link RotarySwitchAim#sideAt} is what decides whether there is a front here at
 * all, exactly as it does for the crosshair, for the ordinary push and for the drag;
 * a second opinion worked out here would disagree with the server somewhere along
 * the axis, which is precisely where a careful player aims. The rest — sneaking, the
 * wrench — keep the meanings they have in every other mode, so the appearance menu
 * and Create's dismantle are reached the same way whichever gesture is in force.
 *
 * The crosshair is left alone. Its arrow names the half of the front under it rather
 * than the way the handle will travel, so it reads correctly in both push modes
 * without knowing which one is on; see {@link RotarySwitchSide#glyph()}.
 *
 * <h2>Holding a spring over</h2>
 *
 * A switch with any detent that springs back ({@link RotarySwitchSpring}) is taken
 * over in both push modes, the honest one included. A push into a springing detent
 * holds the handle there for as long as the use key is held, and that needs the two
 * things {@code IndicatorPressInput} does for a spring-return button: the key coming
 * up has to be noticed, which vanilla has no event for, and the clicks vanilla
 * re-posts while the key is held have to be swallowed, or each of them would push
 * the handle again. So on such a switch the push is sent as an explicit notch, the
 * key is watched on the tick, and a release goes to the server when it comes up, when
 * a screen opens, when the player dies or walks out of reach, or when the switch
 * unloads. The release is sent whatever detent the push landed in; the server
 * ignores one from a player who is not holding anything over.
 *
 * A switch with no springs is left exactly as it was: the plain push stays an
 * ordinary click, repeats and all.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class RotarySwitchPushInput {

    /** The switch this client is holding over after a push, or null if none. */
    private static BlockPos heldPos;

    /** Where on it the push landed, which is how the server tells switches apart. */
    private static Vec3 heldLocal;

    private RotarySwitchPushInput() {}

    /**
     * Turns the switch towards the click, and swallows the click that would have
     * turned it away.
     *
     * Vanilla posts this repeatedly while the use key is held, on the same four-tick
     * delay an ordinary block use repeats on — which it sets before this event is
     * fired, so cancelling here leaves the cadence of a held click exactly as push
     * mode has it. The arm is left to swing: a cancelled interaction swings by
     * default, which is what the uncancelled push does too.
     */
    @SubscribeEvent
    public static void onUseKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem())
            return;
        // Holding a spring over: swallow the repeats, without the arm flapping every
        // few ticks for as long as the key is down
        if (heldPos != null) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
        }
        RotarySwitchTurnMode mode = ClientConfig.turnMode();
        if (mode == RotarySwitchTurnMode.DRAG)
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null)
            return;
        // Sneaking is the screwdriver's configuration menu and Create's dismantle;
        // the wrench is Create's turn. Neither is a push, in this mode or any other.
        if (player.isShiftKeyDown() || AllItems.WRENCH.isIn(player.getItemInHand(event.getHand())))
            return;
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
            return;

        // Null here is every case that is not a push: not a switch, no switch mounted
        // where a panel was clicked, or the contact rack behind the wall, which is
        // wired rather than operated
        RotarySwitchSide side = RotarySwitchAim.sideAt(minecraft.level, hit);
        if (side == null)
            return;

        boolean springs = RotarySwitchAim.springsAt(minecraft.level, hit) != 0;
        if (!mode.invertsPush()) {
            // The honest push on a switch without springs stays an ordinary click.
            // With a screwdriver in hand the click is the screwdriver's, which has
            // already had its say, so it is not taken from it here either.
            if (!springs || ScrewdriverItem.heldBy(player) != null)
                return;
        }

        BlockPos pos = hit.getBlockPos();
        Vec3 local = hit.getLocation().subtract(Vec3.atLowerCornerOf(pos));
        // The inversion, and the whole of the difference between the two push modes
        RotarySwitchSide turn = mode.invertsPush() ? side.opposite() : side;
        ModPackets.sendToServer(new TurnRotarySwitchPacket(pos, local, turn));
        if (springs) {
            heldPos = pos;
            heldLocal = local;
        }
        event.setCanceled(true);
    }

    /**
     * Lets go of a held spring on the tick the key comes up, or the player stops
     * being able to hold it: a screen opening, death, walking out of the reach the
     * server holds them to, or the switch unloading.
     */
    @SubscribeEvent
    //? if forge {
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || heldPos == null)
    //?} else {
    /*public static void onClientTick(ClientTickEvent.Post event) {
        if (heldPos == null)
    *///?}
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player != null && minecraft.level != null && minecraft.screen == null && player.isAlive()
                && minecraft.options.keyUse.isDown() && minecraft.level.isLoaded(heldPos)
                && player.distanceToSqr(Vec3.atCenterOf(heldPos))
                        <= RotarySwitchSpring.MAX_REACH * RotarySwitchSpring.MAX_REACH)
            return;
        release(minecraft);
    }

    /**
     * Tells the server the key is up and forgets the switch. With no connection left
     * there is nobody to tell, and the switch will have let go on its own.
     */
    private static void release(Minecraft minecraft) {
        if (minecraft.getConnection() != null)
            ModPackets.sendToServer(TurnRotarySwitchPacket.release(heldPos, heldLocal));
        heldPos = null;
        heldLocal = null;
    }
}
