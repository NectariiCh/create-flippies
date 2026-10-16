package com.nectariich.create_flippies.content.host;

import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * How breaking a panel is stopped from breaking every instrument on it.
 *
 * Vanilla breaking has no target finer than a block, so a left click on a host would
 * take the frame and everything screwed into it. This cancels that, on both sides,
 * and the click itself is turned into the removal of one control elsewhere: the
 * client notices the press through
 * {@code ControlBreakInput} and sends where it was aimed, because it is the only side
 * that knows.
 *
 * Both cancels are needed and neither covers the other. The client's stops the mining
 * animation and the packet that would follow it; the server holds its own pass of the
 * same event, at the top of {@code ServerPlayerGameMode.handleBlockBreakAction}, and
 * that is the pass that actually protects the panel — in creative the client destroys
 * a block on its own prediction and asks afterwards, and the client's pass of this
 * event is not fired in creative at all.
 *
 * The screwdriver is deliberately left alone. It cancels every left click of its own
 * accord, so that a mistimed click cannot take out a wall of instruments the tool is
 * being used to build, and nothing here should give that click an effect again.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID)
public final class ControlHostInteractions {

    private ControlHostInteractions() {}

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        if (ScrewdriverItem.heldBy(player) != null)
            return;
        // Only a block that is nothing but its controls is protected wholesale. An
        // empty frame is left minable: nothing is screwed into it to aim at, and a
        // host that somehow outlived its last control would otherwise be permanent.
        // A panel cell is never protected here — it is a wall in its own right and
        // breaking it is a real gesture, so whether a click unscrews a control or
        // mines the block is decided by where it landed, which only
        // {@code ControlBreakInput} can see.
        if (player.level().getBlockEntity(event.getPos()) instanceof ControlRackHolder host
                && host.removeWhenEmpty() && !host.controls().isEmpty())
            event.setCanceled(true);
    }
}
