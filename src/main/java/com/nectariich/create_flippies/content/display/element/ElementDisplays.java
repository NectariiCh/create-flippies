package com.nectariich.create_flippies.content.display.element;

import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Finds the {@link ElementDisplay} a packet or a menu means, whichever form it takes.
 *
 * The generalisation of
 * {@link com.nectariich.create_flippies.content.display.AlphanumDisplay#at}: a null
 * mount means the block entity is the display, a mount means the block entity is a
 * host and the display is the control at exactly that mount. A mount identifies a
 * control within its host because no two controls may occupy the same space, so it
 * is the one handle a packet can carry that does not depend on the order the host
 * happens to hold its controls in.
 */
public final class ElementDisplays {

    private ElementDisplays() {}

    /** The display there, or null when there is none (or it is not one that shows elements). */
    public static ElementDisplay at(BlockEntity blockEntity, ControlMount mount) {
        if (mount == null)
            return blockEntity instanceof ElementDisplay display ? display : null;
        if (!(blockEntity instanceof ControlRackHolder host))
            return null;
        for (HostedControl control : host.controls())
            if (control instanceof ElementDisplay display && mount.equals(control.mount()))
                return display;
        return null;
    }
}
