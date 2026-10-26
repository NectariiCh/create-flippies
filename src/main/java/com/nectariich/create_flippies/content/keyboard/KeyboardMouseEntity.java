package com.nectariich.create_flippies.content.keyboard;

import com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind;
import com.nectariich.create_flippies.content.device.DeviceShape;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A keyboard and mouse in one device (spec 11.2.15): the keyboard's body with two
 * PS/2 ports, DEVICE ends. Port 0 is the keyboard and port 1 the mouse; each is
 * linked to a case's PS/2 port on its own, like the two plugs of a real set, so
 * with one cord only that half reaches the PC. Using it captures the keyboard as
 * {@link KeyboardEntity} does, and the player's mouse drives the PC's mouse until
 * the toggle key hands it back to looking around.
 *
 * The model `keyboard_and_mouse.json` is the keyboard's, with the `ps/2 2` cube
 * beside `ps/2`.
 */
public class KeyboardMouseEntity extends KeyboardEntity {

    /** The model's `ps/2 2` cube, px. */
    static final double[] MOUSE_PORT = KeyboardGeometry.MOUSE_PORT;

    private static final double[][] PORTS = {PORT, MOUSE_PORT};

    public static final DeviceShape SHAPE = new DeviceShape(new double[][] {BODY},
            new double[][] {portBox(PORT), portBox(MOUSE_PORT)}, -1);

    public KeyboardMouseEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Override
    protected double[][] portCubes() {
        return PORTS;
    }

    @Override
    public Ps2DeviceKind deviceKind(int port) {
        return switch (port) {
            case 0 -> Ps2DeviceKind.KEYBOARD;
            case 1 -> Ps2DeviceKind.MOUSE;
            default -> Ps2DeviceKind.NONE;
        };
    }

    @Override
    public DeviceShape shape() {
        return SHAPE;
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.KEYBOARD_AND_MOUSE.get());
    }
}
