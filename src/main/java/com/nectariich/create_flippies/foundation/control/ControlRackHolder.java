package com.nectariich.create_flippies.foundation.control;

import java.util.List;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;

/**
 * A block entity that carries mounted controls.
 *
 * There are two, and they have nothing else in common. One is the bare frame the
 * screwdriver opens in front of a panel; the other is a large panel cell itself,
 * which has to host its own controls because a chamfer's surface runs through the
 * middle of its blocks and leaves the cell in front of a point on it occupied by
 * the panel. Neither can inherit from the other — a panel's block entity must be a
 * {@code CopycatBlockEntity} to wear a material and a host's must be an
 * {@code ElectricBlockEntity} to have been one, and those are siblings — so what
 * they share is held in a {@link ControlRack} that both own and this interface
 * reaches.
 *
 * The convenience methods below are here rather than at the call sites because
 * roughly twenty places ask a block entity for the control under the crosshair,
 * and every one of them would otherwise have to know that the controls live one
 * indirection away.
 */
public interface ControlRackHolder {

    /** Everything mounted here, and the machinery that keeps it straight. */
    ControlRack rack();

    /**
     * The behaviour whose external nodes this rack's terminals are.
     *
     * Power Grid keys a wire endpoint on a block position and an index, so there is
     * one behaviour per block however many controls the block carries, and the rack
     * hands out slots within it.
     */
    ElectricBehaviour rackBehaviour();

    /** Persist and sync after the rack changed. */
    void rackChanged();

    /** The rack's extent changed, so the render bounding box has to be worked out again. */
    void rackResized();

    /**
     * Whether emptying this block should take the block with it.
     *
     * True for a bare frame, which is nothing but the controls in it: an empty one
     * would render as nothing, could be aimed at only through its stub, and would
     * still be answering for a run of dead terminals. False for anything that is
     * also something in its own right — a panel, a run of DIN rail — which has every
     * reason to stay once the last thing mounted on it is gone.
     */
    default boolean removeWhenEmpty() {
        return false;
    }

    /** Whatever the block looks like before anything is mounted on it. */
    default VoxelShape baseShape() {
        return Shapes.empty();
    }

    // What the rest of the mod asks of a block carrying controls

    default List<HostedControl> controls() {
        return rack().controls();
    }

    default HostedControl controlAt(Vec3 local) {
        return rack().controlAt(local);
    }

    default HostedControl installedAt(ControlMount mount) {
        return rack().installedAt(mount);
    }

    default PlacementRefusal install(ControlType type, ControlMount mount, Player placer) {
        return rack().install(type, mount, placer);
    }

    default PlacementRefusal install(HostedControl control, ControlMount mount, Player placer) {
        return rack().install(control, mount, placer);
    }

    default HostedControl remove(HostedControl control) {
        return rack().remove(control);
    }

    default boolean breakControl(Player player, Vec3 local) {
        return rack().breakControl(player, local);
    }

    default void dropControls() {
        rack().dropControls();
    }
}
