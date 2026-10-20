package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignStore;
import com.nectariich.create_flippies.registry.ModBlocks;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

/**
 * Asks for a designed control to be made at a workbench: what the blueprint
 * browser's Make sends, and the one place an item is minted from a design.
 *
 * Sent after the design's model and fonts are uploaded, which the server handles
 * first because packets from one client are handled in order; a design naming a file
 * the store does not hold is refused. Making is creative only for now: there is no
 * survival recipe yet, so a survival player is told so and given nothing.
 */
public class CreateDesignPacket {

    private static final double REACH_SQ = 8 * 8;

    private final BlockPos bench;
    private final CompoundTag design;

    public CreateDesignPacket(BlockPos bench, ControlDesign design) {
        this.bench = bench;
        this.design = design.write();
    }

    public CreateDesignPacket(FriendlyByteBuf buffer) {
        bench = buffer.readBlockPos();
        design = buffer.readNbt();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(bench);
        buffer.writeNbt(design);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        ServerPlayer player = context.get().getSender();
        if (player != null)
            context.get().enqueueWork(() -> create(player));
        context.get().setPacketHandled(true);
    }

    private void create(ServerPlayer player) {
        if (design != null)
            make(player, bench, ControlDesign.read(design));
    }

    /**
     * Server thread: gives {@code player} the control {@code design} describes, if
     * they are at a design workbench at {@code bench}, in creative, and the store
     * holds every file the design needs. Otherwise tells them why not on the action
     * bar, or says nothing when they are simply not at a bench, which a client
     * acting in good faith never sends.
     *
     * @return whether the item was given
     */
    public static boolean make(ServerPlayer player, BlockPos bench, ControlDesign design) {
        if (player.distanceToSqr(bench.getCenter()) > REACH_SQ
                || !player.level().getBlockState(bench).is(ModBlocks.DESIGN_WORKBENCH.get()))
            return false;
        if (!player.isCreative()) {
            player.displayClientMessage(Component.translatable("gui.create_flippies.design_workbench.make.survival"),
                    true);
            return false;
        }
        if (!ControlDesign.isHash(design.model()) || !DesignStore.has(player.server, design.model())) {
            player.displayClientMessage(Component.translatable("gui.create_flippies.design_workbench.missing_model"),
                    true);
            return false;
        }
        for (String font : design.fontHashes())
            if (!ControlDesign.isHash(font) || !DesignStore.has(player.server, font)) {
                player.displayClientMessage(
                        Component.translatable("gui.create_flippies.design_workbench.missing_font"), true);
                return false;
            }
        ItemStack stack = design.applyTo(new ItemStack(ModItems.DESIGNED_CONTROLS.get(design.kind()).get()));
        if (!player.getInventory().add(stack))
            player.drop(stack, false);
        return true;
    }
}
