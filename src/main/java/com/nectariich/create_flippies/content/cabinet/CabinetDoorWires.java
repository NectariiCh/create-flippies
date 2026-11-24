package com.nectariich.create_flippies.content.cabinet;

import com.nectariich.create_flippies.content.host.ControlHostBlockEntity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;

/**
 * Wires to the controls on a shut cabinet door: neither drawn nor hit.
 *
 * Those controls are wired where they are with the door open
 * ({@link CabinetDoorLink}), so with it shut their wires would run to empty space
 * beside the cabinet. For now they are simply not there until the door opens again.
 */
public final class CabinetDoorWires {

    private CabinetDoorWires() {}

    /** Whether {@code wire} runs to a control on a shut door. */
    public static boolean hidden(Entity wire) {
        if (!(wire instanceof BaseWireEntity base))
            return false;
        Level level = wire.level();
        return level != null && (onShutDoor(level, base.getEndpoint1()) || onShutDoor(level, base.getEndpoint2()));
    }

    private static boolean onShutDoor(Level level, IWireEndpoint end) {
        return end instanceof BlockWireEndpoint block && level.isLoaded(block.getPos())
                && level.getBlockEntity(block.getPos()) instanceof ControlHostBlockEntity host && host.doorShut();
    }
}
