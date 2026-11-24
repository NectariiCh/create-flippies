package com.nectariich.create_flippies.content.cabinet;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverMode;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverSettings;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Cutting a hole in a cabinet, or filling one, on the server: the stroke the client
 * sent is checked again from scratch against the cabinet as it is here
 * ({@link CabinetCuts#add}), so a client that sends anything gets at most a hole the
 * rules allow. Nothing is used up and nothing drops: the metal is the cabinet's.
 */
public final class CabinetCutService {

    private static final String MESSAGE = "message." + CreateFlippies.MOD_ID + ".cabinet_cut.";

    /** The block reach and the reach across the biggest cabinet from its middle, blocks. */
    private static final double MAX_REACH = 11;

    private CabinetCutService() {}

    public static void apply(ServerPlayer player, ElectricalCabinetEntity cabinet, CabinetCut cut) {
        if (!player.mayBuild() || cabinet.isRemoved() || cabinet.level() != player.level()
                || player.distanceToSqr(cabinet.getBoundingBox().getCenter()) > MAX_REACH * MAX_REACH)
            return;
        InteractionHand hand = ScrewdriverItem.heldBy(player);
        if (hand == null
                || ScrewdriverSettings.read(player.getItemInHand(hand)).mode() != ScrewdriverMode.CUT_CABINET)
            return;
        CabinetCuts.Problem problem = cabinet.addCut(cut);
        if (!problem.ok()) {
            player.displayClientMessage(
                    Component.translatable(MESSAGE + problem.key()).withStyle(ChatFormatting.RED), true);
            return;
        }
        Vec3 at = cabinet.toWorld(CabinetGeometry.interiorCentre(cabinet.spec()));
        player.level().playSound(null, at.x, at.y, at.z,
                cut.fill() ? SoundEvents.NETHERITE_BLOCK_PLACE : SoundEvents.UI_STONECUTTER_TAKE_RESULT,
                SoundSource.BLOCKS, 0.6f, cut.fill() ? 1.2f : 1.4f);
    }
}
