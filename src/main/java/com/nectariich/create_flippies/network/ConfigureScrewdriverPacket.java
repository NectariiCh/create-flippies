package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.schematic_arrow.ArrowDraft;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowGrab;
import com.nectariich.create_flippies.content.panel.PanelEditKind;
import com.nectariich.create_flippies.content.screwdriver.DynamicBlockKind;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverMode;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverSettings;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

/**
 * The screwdriver menu, applied to the stack the sender is holding.
 *
 * Carrying the hand rather than a slot or an item identity is Create's own
 * arrangement for a configurable tool, and the reason is that the hand is the one
 * thing the server can check: it re-reads the stack itself, so a client cannot
 * name a stack it is not holding, and there is no per-player state to keep in step
 * with the inventory.
 */
public class ConfigureScrewdriverPacket {

    private final InteractionHand hand;
    private final ScrewdriverMode mode;
    private final int columns;
    private final int rows;
    private final int turn;
    private final DynamicBlockKind kind;
    private final int yaw;
    private final PanelEditKind editKind;
    private final int step;
    private final boolean clearCorner;

    public ConfigureScrewdriverPacket(InteractionHand hand, ScrewdriverMode mode, int columns, int rows, int turn,
            DynamicBlockKind kind, int yaw, PanelEditKind editKind, int step, boolean clearCorner) {
        this.hand = hand;
        this.mode = mode;
        this.columns = columns;
        this.rows = rows;
        this.turn = turn;
        this.kind = kind;
        this.yaw = yaw;
        this.editKind = editKind;
        this.step = step;
        this.clearCorner = clearCorner;
    }

    public ConfigureScrewdriverPacket(FriendlyByteBuf buffer) {
        hand = buffer.readEnum(InteractionHand.class);
        mode = buffer.readEnum(ScrewdriverMode.class);
        columns = buffer.readVarInt();
        rows = buffer.readVarInt();
        turn = buffer.readByte();
        kind = buffer.readEnum(DynamicBlockKind.class);
        yaw = buffer.readByte();
        editKind = buffer.readEnum(PanelEditKind.class);
        step = buffer.readVarInt();
        clearCorner = buffer.readBoolean();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(hand);
        buffer.writeEnum(mode);
        buffer.writeVarInt(columns);
        buffer.writeVarInt(rows);
        buffer.writeByte(turn);
        buffer.writeEnum(kind);
        buffer.writeByte(yaw);
        buffer.writeEnum(editKind);
        buffer.writeVarInt(step);
        buffer.writeBoolean(clearCorner);
    }

    /**
     * The track counts and the edit step are not validated here beyond what
     * {@link ScrewdriverSettings} clamps them to. They only ever decide how a
     * rectangle is ruled or how much of a panel a click covers, and both the grid
     * store and the panel refuse what they cannot hold, so the worst a nonsensical
     * number can do is waste one click.
     */
    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null)
                return;
            ItemStack stack = player.getItemInHand(hand);
            if (!ScrewdriverItem.isScrewdriver(stack))
                return;
            new ScrewdriverSettings(mode, columns, rows, turn, null, null, kind, yaw, null, null, editKind, step,
                    null).apply(stack);
            // One button forgets whichever rectangle is half drawn, and there is no
            // sense in which the player could mean only one of them
            if (clearCorner) {
                ScrewdriverSettings.setCorner(stack, null);
                ScrewdriverSettings.setAreaCorner(stack, null);
                ScrewdriverSettings.setFirstPoint(stack, null);
                ScrewdriverSettings.setFirstPanel(stack, null);
                ScrewdriverSettings.setEditPanel(stack, null);
                // SCHEMATIC ARROWS
                ArrowDraft.write(stack, null);
                ArrowGrab.write(stack, null);
            }
        });
        context.get().setPacketHandled(true);
    }
}
