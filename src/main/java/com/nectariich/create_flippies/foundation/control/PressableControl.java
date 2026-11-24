package com.nectariich.create_flippies.foundation.control;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A mounted control that is pushed for as long as the use key is held on it: the
 * indicator buttons' press path, offered to any control.
 *
 * The client claims the use key on such a control when {@link #pressableAt} says
 * the aim is on the part that pushes, sends a press as the key goes down and a
 * release as it comes up, and swallows the repeats in between — see
 * {@code client/IndicatorPressInput}. The server is trusted for nothing but "this
 * player pushed here": a control must let go on its own once the holder is gone.
 */
public interface PressableControl {

    /** Whether {@code local}, a point within the host cell in block units, is on the part that pushes. */
    boolean pressableAt(Vec3 local);

    /** Server-side: the use key went down on this control. */
    void press(Player player);

    /** Server-side: the use key came up; only the holder's release counts. */
    void release(Player player);

    /** The pressable control under {@code local} in the host at {@code pos}, or null. */
    static PressableControl at(Level level, BlockPos pos, Vec3 local) {
        if (level == null || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof ControlRackHolder host))
            return null;
        HostedControl control = host.controlAt(local);
        return control instanceof PressableControl pressable && pressable.pressableAt(local) ? pressable : null;
    }
}
