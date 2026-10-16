package com.nectariich.create_flippies.content.gauge;

import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class UniversalGaugeBlockEntity extends BlockEntity implements IHaveGoggleInformation {

    private static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".universal_gauge.goggles.";

    private float min = 0;
    private float max = 100;
    private GaugeTheme theme = GaugeTheme.SPEEDOMETER;
    private float value = 0;
    private boolean hasValue = false;
    private String lastText = "";
    // Which way the dial's top points when mounted on a floor or ceiling
    private Direction topDirection = Direction.NORTH;

    public float dialTarget = 0;

    // Client-side needle animation
    public float dialState = 0;
    public float prevDialState = 0;

    public UniversalGaugeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.UNIVERSAL_GAUGE.get(), pos, state);
    }

    public float getMin() {
        return min;
    }

    public float getMax() {
        return max;
    }

    public GaugeTheme getTheme() {
        return theme;
    }

    public float getValue() {
        return value;
    }

    public boolean hasValue() {
        return hasValue;
    }

    public Direction getTopDirection() {
        return topDirection;
    }

    public void setTopDirection(Direction direction) {
        if (direction.getAxis().isVertical())
            return;
        topDirection = direction;
        notifyUpdate();
    }

    /**
     * Called server-side by the display target with the raw link text and, if one was
     * found in it, the extracted number.
     */
    public void updateFromLink(String rawText, Double number) {
        lastText = rawText;
        if (number != null) {
            value = number.floatValue();
            hasValue = true;
            recomputeDial();
        }
        notifyUpdate();
    }

    /** Called server-side when a configuration packet arrives. */
    public void configure(float min, float max, GaugeTheme theme) {
        this.min = min;
        this.max = max;
        this.theme = theme;
        recomputeDial();
        notifyUpdate();
    }

    private void recomputeDial() {
        if (!hasValue) {
            dialTarget = 0;
            return;
        }
        float span = max - min;
        dialTarget = span == 0 ? 0 : Mth.clamp((value - min) / span, 0, 1);
    }

    private void notifyUpdate() {
        setChanged();
        if (level != null && !level.isClientSide)
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public void clientTick() {
        prevDialState = dialState;
        dialState += (dialTarget - dialState) * .125f;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putFloat("Min", min);
        tag.putFloat("Max", max);
        tag.putString("Theme", theme.getSerializedName());
        tag.putFloat("Value", value);
        tag.putBoolean("HasValue", hasValue);
        tag.putString("LastText", lastText);
        tag.putInt("Top", topDirection.get3DDataValue());
        tag.putFloat("DialTarget", dialTarget);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        min = tag.getFloat("Min");
        max = tag.getFloat("Max");
        theme = GaugeTheme.byName(tag.getString("Theme"));
        value = tag.getFloat("Value");
        hasValue = tag.getBoolean("HasValue");
        lastText = tag.getString("LastText");
        Direction top = Direction.from3DDataValue(tag.getInt("Top"));
        topDirection = top.getAxis().isVertical() ? Direction.NORTH : top;
        dialTarget = tag.getFloat("DialTarget");
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "header")
                .withStyle(ChatFormatting.WHITE)));
        // The display link's output verbatim, nothing else: no label, and no
        // restating the number pulled out of it or the range, both of which the
        // dial already shows.
        if (lastText.isEmpty()) {
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "no_data")
                    .withStyle(ChatFormatting.DARK_GRAY)));
            return true;
        }
        tooltip.add(indent(Component.literal(lastText).withStyle(ChatFormatting.WHITE)));
        return true;
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
