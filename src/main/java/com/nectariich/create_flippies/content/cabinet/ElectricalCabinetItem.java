package com.nectariich.create_flippies.content.cabinet;

import java.util.List;
import java.util.function.Supplier;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.content.device.DeviceEntity;
import com.nectariich.create_flippies.content.device.DeviceItem;
import com.nectariich.create_flippies.content.device.DeviceShape;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * The electrical cabinet's item: what cabinet it places is set on the item, in the
 * menu a sneaking right click in the air opens ({@code CabinetSettingsScreen}), and
 * kept under {@link CabinetSpec#TAG}.
 *
 * It hangs only on walls, the side of something: back flush against the face
 * clicked, front looking straight out of it, the middle of its back at the point
 * clicked to the nearest pixel. It goes only where all of it would be clear, the
 * room inside as well as the walls, and nobody stands.
 */
public class ElectricalCabinetItem extends DeviceItem {

    private static final String LANG_PREFIX = "item.create_flippies.electrical_cabinet.";

    public ElectricalCabinetItem(Supplier<? extends EntityType<?>> type, Properties properties) {
        super(type, ElectricalCabinetEntity::new, properties);
    }

    /** Whether it places standing cabinets ({@link StandingCabinetItem}) rather than wall ones. */
    public boolean standing() {
        return false;
    }

    @Override
    public Placement placementFor(Level level, Player player, BlockHitResult hit, ItemStack stack) {
        Direction face = hit.getDirection();
        if (face.getAxis() == Direction.Axis.Y)
            return null;
        // The position is the middle of the back, to within half a pixel (CabinetGeometry)
        return new Placement(snap(hit.getLocation()), DeviceShape.stepFor(face.toYRot()));
    }

    @Override
    public void configure(DeviceEntity device, ItemStack stack) {
        if (device instanceof ElectricalCabinetEntity cabinet)
            cabinet.setSpec(CabinetSpec.read(stack));
    }

    /** As any device, and the room inside clear too: nothing in it, block, device or anyone. */
    @Override
    public boolean canPlace(Level level, DeviceEntity device) {
        if (!fits(level, device) || !(device instanceof ElectricalCabinetEntity cabinet))
            return false;
        double[] b = CabinetGeometry.outline(cabinet.spec());
        AABB whole = new AABB(cabinet.toWorld(new Vec3(b[0], b[1], b[2])), cabinet.toWorld(new Vec3(b[3], b[4], b[5])))
                .deflate(1e-6);
        return level.noCollision(null, whole) && level.isUnobstructed(null, Shapes.create(whole));
    }

    /** A sneaking right click in the air opens the cabinet menu; anywhere else it does nothing of its own. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()
                || getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE).getType() != HitResult.Type.MISS)
            return InteractionResultHolder.pass(stack);
        if (level.isClientSide)
            ClientOnly.run(
                    () -> () -> com.nectariich.create_flippies.client.cabinet.CabinetSettingsScreen.open(hand));
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        CabinetSpec spec = CabinetSpec.read(stack);
        tooltip.add(Component.translatable(LANG_PREFIX + "size", spec.widthPx(), spec.heightPx(), spec.depthPx())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(LANG_PREFIX + "colour", colourName(spec)).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(LANG_PREFIX + "hinge",
                Component.translatable(LANG_PREFIX + "hinge." + spec.hinge().key())).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(LANG_PREFIX + (standing() ? "hint_standing" : "hint"))
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** The preset's name, or the colour as hex; with a square of the colour in front. */
    public static Component colourName(CabinetSpec spec) {
        Component name = spec.preset() == CabinetSpec.CUSTOM
                ? Component.literal(String.format(java.util.Locale.ROOT, "#%06X", spec.rgb()))
                : Component.translatable("gui.create_flippies.cabinet.colour."
                        + CabinetSpec.PRESETS.get(spec.preset()).key());
        return Component.empty().append(Component.literal("■ ").withStyle(style -> style.withColor(spec.rgb())))
                .append(name);
    }

    /** The colour the item's icon is tinted on its body layer. */
    public static int tint(ItemStack stack) {
        return CabinetSpec.read(stack).rgb();
    }
}
