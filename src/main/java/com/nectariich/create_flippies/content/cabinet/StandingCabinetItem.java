package com.nectariich.create_flippies.content.cabinet;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.device.DeviceShape;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The standing electrical cabinet's item: the same cabinet as the wall one, set up in
 * the same menu, but standing on the ground, up to {@link CabinetSpec#STANDING_MAX_WIDTH}
 * across and up and {@link CabinetSpec#STANDING_MAX_DEPTH} deep.
 *
 * It goes on the top of something, a block or another cabinet: its underside on the
 * face clicked, the middle of its footprint at the point clicked to the nearest pixel,
 * its door towards the player at a quarter turn. Like the wall cabinet, it goes only
 * where all of it would be clear.
 *
 * The cabinet's position is still the middle of its back ({@link CabinetGeometry}),
 * half its height up from the ground. One stands on another by a click on the
 * lower one's top ({@link ElectricalCabinetEntity#interactAt}).
 */
public class StandingCabinetItem extends ElectricalCabinetItem {

    public StandingCabinetItem(Supplier<? extends EntityType<?>> type, Properties properties) {
        super(type, properties);
    }

    @Override
    public boolean standing() {
        return true;
    }

    @Override
    public Placement placementFor(Level level, Player player, BlockHitResult hit, ItemStack stack) {
        if (hit.getDirection() != Direction.UP)
            return null;
        CabinetSpec spec = CabinetSpec.read(stack);
        Direction out = player.getDirection().getOpposite();
        Vec3 centre = snap(hit.getLocation());
        // Back from the middle of the footprint by half the depth, and up by the half below the position
        double back = (spec.depthPx() / 2) / 16.0, up = (spec.heightPx() / 2) / 16.0;
        Vec3 at = centre.add(-out.getStepX() * back, up, -out.getStepZ() * back);
        return new Placement(at, DeviceShape.stepFor(out.toYRot()));
    }
}
