package com.nectariich.create_flippies.client;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchAim;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchSide;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchTurnMode;
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
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class RotarySwitchPushInput {

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
        if (!event.isUseItem() || !ClientConfig.turnMode().invertsPush())
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

        BlockPos pos = hit.getBlockPos();
        Vec3 local = hit.getLocation().subtract(Vec3.atLowerCornerOf(pos));
        // The inversion, and the whole of the difference between the two push modes
        ModPackets.CHANNEL.sendToServer(new TurnRotarySwitchPacket(pos, local, side.opposite()));
        event.setCanceled(true);
    }
}
