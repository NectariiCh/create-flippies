package com.nectariich.create_flippies.foundation.control;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The one way into any control's configuration: a right click with a screwdriver in
 * hand.
 *
 * It used to be the wrench on a block and a plain right click on a mounted control,
 * which was two gestures for one idea and spent the gesture the controls actually
 * need. An empty-handed click has to stay free — a button screwed into a panel must
 * be pressable by someone carrying nothing, and a rotary switch is turned by one —
 * and the wrench is Create's tool for taking things off walls, which is what players
 * reach for it expecting. So configuring is the screwdriver's, which is already the
 * tool that mounts these things and is already in the hand of whoever is fitting a
 * panel out. What tells the two apart is the tool, not the posture: holding the
 * screwdriver is the whole of the gesture, and sneaking with one belongs to the
 * tool's own settings.
 *
 * Everything with a menu is reached through here, whichever form it is in. A block
 * answers for itself through {@link ConfigurableBlock}; a mounted control is found
 * under the crosshair and asked through {@link HostedControl#openEditor}, which is
 * the same call the screwdriver makes as it installs one.
 *
 * <h2>Why it is asked on both sides</h2>
 *
 * The screwdriver's modes are what this competes with, and one click cannot both
 * open a menu and rule a grid. The tool's own interaction handler settles that by
 * offering the mode the click first and calling here only with what is left over.
 * The client then decides for itself, since the menu is a screen; the server decides
 * again when the same click arrives there, since a mounted control's screen can only
 * be asked for from the server. Both sides run this method against the same block
 * entities and reach the same verdict, so the two cannot disagree about which menu
 * the player asked for.
 */
public final class ControlConfig {

    private ControlConfig() {}

    /**
     * Opens whatever configuration is under {@code local}, if there is one.
     *
     * {@code local} is the aim as an offset within the block at {@code pos}, in block
     * units. It is only consulted for a host block, which may be carrying several
     * controls; a block that is an instrument in its own right is the one thing that
     * can be aimed at within it.
     *
     * @return whether a configuration was claimed, so that the caller can leave the
     *         click alone if not
     */
    public static boolean open(Level level, BlockPos pos, Vec3 local, Player player) {
        if (level == null || !level.isLoaded(pos))
            return false;
        if (level.getBlockState(pos).getBlock() instanceof ConfigurableBlock block)
            return block.openConfig(level, pos, player);
        if (!(level.getBlockEntity(pos) instanceof ControlRackHolder host))
            return false;
        HostedControl control = host.controlAt(local);
        // A control with no editor — a breaker, say — leaves the click alone rather
        // than swallowing it, so the screwdriver's own menu still opens over one
        if (control == null || !control.hasEditor())
            return false;
        // The client says yes and does nothing: a mounted control's screen is opened
        // by the packet the server sends from the line below, because only the server
        // may decide that this player is allowed to edit this control
        if (level.isClientSide)
            return true;
        return player instanceof ServerPlayer server && control.openEditor(server);
    }
}
