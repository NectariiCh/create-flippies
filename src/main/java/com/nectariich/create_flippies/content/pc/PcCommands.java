package com.nectariich.create_flippies.content.pc;

import java.util.function.Function;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
//? if forge {
import net.minecraftforge.event.RegisterCommandsEvent;
//?} else {
/*import net.neoforged.neoforge.event.RegisterCommandsEvent;
*///?}
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /flippies pc ...} (spec 12.19), operators only (level 2). A PC is the case in the
 * block at {@code <pos>} (the one nearest its middle, if several share it), or else the case
 * the player is looking at.
 * <ul>
 * <li>{@code freeze [<pos>|all]}, {@code unfreeze [<pos>|all]}: hold a running PC still, or let
 * it run on;</li>
 * <li>{@code snapshot save <name> [server] [<pos>]}: a snapshot of the PC, to the player's
 * computer, or with {@code server} to the server's folder;</li>
 * <li>{@code snapshot give <name> [server]}: a case item that places a copy of it, frozen;</li>
 * <li>{@code snapshot list [server]}.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PcCommands {

    /** How far the looked-at case may be. */
    private static final double REACH = 16;

    private PcCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("flippies").then(Commands.literal("pc")
                .requires(source -> source.hasPermission(2))
                .then(freezing("freeze", PcCaseEntity::freeze))
                .then(freezing("unfreeze", PcCaseEntity::unfreeze))
                .then(Commands.literal("snapshot")
                        .then(Commands.literal("save").then(Commands.argument("name", StringArgumentType.word())
                                .executes(c -> save(c, false, null))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(c -> save(c, false, BlockPosArgument.getLoadedBlockPos(c, "pos"))))
                                .then(Commands.literal("server")
                                        .executes(c -> save(c, true, null))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(c -> save(c, true,
                                                        BlockPosArgument.getLoadedBlockPos(c, "pos")))))))
                        .then(Commands.literal("give").then(Commands.argument("name", StringArgumentType.word())
                                .executes(c -> give(c, false))
                                .then(Commands.literal("server").executes(c -> give(c, true)))))
                        .then(Commands.literal("list")
                                .executes(c -> snapshots(c.getSource()).list(c.getSource(), false))
                                .then(Commands.literal("server")
                                        .executes(c -> snapshots(c.getSource()).list(c.getSource(), true)))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> freezing(String verb,
            Function<PcCaseEntity, Component> action) {
        return Commands.literal(verb)
                .executes(c -> one(c.getSource(), find(c.getSource(), null), verb, action))
                .then(Commands.literal("all").executes(c -> all(c.getSource(), verb, action)))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> one(c.getSource(), find(c.getSource(), BlockPosArgument.getLoadedBlockPos(c,
                                "pos")), verb, action)));
    }

    private static int one(CommandSourceStack source, PcCaseEntity pc, String verb,
            Function<PcCaseEntity, Component> action) {
        if (pc == null)
            return 0;
        Component refusal = action.apply(pc);
        if (refusal != null) {
            source.sendFailure(Component.translatable("commands.create_flippies.pc." + verb + ".refused",
                    pos(pc), refusal));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.create_flippies.pc." + verb + ".done", pos(pc)),
                true);
        return 1;
    }

    private static int all(CommandSourceStack source, String verb, Function<PcCaseEntity, Component> action) {
        PcServer server = PcServer.get();
        if (server == null)
            return 0;
        int done = 0;
        for (PcCaseEntity pc : server.loadedCases())
            if ((verb.equals("freeze") ? pc.running() && !pc.frozen() : pc.frozen()) && action.apply(pc) == null)
                done++;
        int n = done;
        source.sendSuccess(() -> Component.translatable("commands.create_flippies.pc." + verb + ".all", n), true);
        return done;
    }

    private static int save(CommandContext<CommandSourceStack> c, boolean toServer, BlockPos pos) {
        CommandSourceStack source = c.getSource();
        String name = StringArgumentType.getString(c, "name");
        if (!validName(source, name))
            return 0;
        PcCaseEntity pc = find(source, pos);
        return pc == null ? 0 : snapshots(source).save(source, pc, name, toServer);
    }

    private static int give(CommandContext<CommandSourceStack> c, boolean fromServer) {
        CommandSourceStack source = c.getSource();
        String name = StringArgumentType.getString(c, "name");
        return validName(source, name) ? snapshots(source).give(source, name, fromServer) : 0;
    }

    private static boolean validName(CommandSourceStack source, String name) {
        if (PcSnapshot.validName(name))
            return true;
        source.sendFailure(Component.translatable("commands.create_flippies.snapshot.bad_name", name));
        return false;
    }

    private static PcSnapshots snapshots(CommandSourceStack source) {
        PcServer server = PcServer.get();
        if (server == null)
            throw new IllegalStateException("no server is running");
        return server.snapshots();
    }

    /** The case at {@code pos}, or the one the player looks at; null (said why) if there is none. */
    private static PcCaseEntity find(CommandSourceStack source, BlockPos pos) {
        PcCaseEntity pc = pos != null ? caseIn(source, pos) : lookedAt(source.getPlayer());
        if (pc == null)
            source.sendFailure(Component.translatable("commands.create_flippies.pc.no_case"));
        return pc;
    }

    /** The case in the block at {@code pos}, the one nearest its middle; null if none. */
    private static PcCaseEntity caseIn(CommandSourceStack source, BlockPos pos) {
        Vec3 middle = Vec3.atCenterOf(pos);
        PcCaseEntity best = null;
        for (PcCaseEntity pc : source.getLevel().getEntitiesOfClass(PcCaseEntity.class, new AABB(pos)))
            if (best == null || pc.getBoundingBox().getCenter().distanceToSqr(middle)
                    < best.getBoundingBox().getCenter().distanceToSqr(middle))
                best = pc;
        return best;
    }

    /** The case nearest the player's eye along their look, within {@link #REACH}; null if none. */
    private static PcCaseEntity lookedAt(ServerPlayer player) {
        if (player == null)
            return null;
        Vec3 eye = player.getEyePosition();
        Vec3 far = eye.add(player.getViewVector(1).scale(REACH));
        PcCaseEntity best = null;
        double bestFraction = Double.MAX_VALUE;
        for (PcCaseEntity pc : player.level().getEntitiesOfClass(PcCaseEntity.class, new AABB(eye, far).inflate(1))) {
            var hit = pc.clip(eye, far);
            if (hit != null && hit.fraction() < bestFraction) {
                best = pc;
                bestFraction = hit.fraction();
            }
        }
        return best;
    }

    private static String pos(PcCaseEntity pc) {
        BlockPos p = pc.blockPosition();
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }
}
