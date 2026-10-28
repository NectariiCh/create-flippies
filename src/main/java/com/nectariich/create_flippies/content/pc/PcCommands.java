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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
//? if forge {
import net.minecraftforge.event.RegisterCommandsEvent;
//?} else {
/*import net.neoforged.neoforge.event.RegisterCommandsEvent;
*///?}
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /flippies pc ...} (spec 12.19), operators only (level 2). A PC is the one at
 * {@code <pos>}, or else the case the player is looking at.
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
                .then(freezing("freeze", PcCaseBlockEntity::freeze))
                .then(freezing("unfreeze", PcCaseBlockEntity::unfreeze))
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
            Function<PcCaseBlockEntity, Component> action) {
        return Commands.literal(verb)
                .executes(c -> one(c.getSource(), find(c.getSource(), null), verb, action))
                .then(Commands.literal("all").executes(c -> all(c.getSource(), verb, action)))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> one(c.getSource(), find(c.getSource(), BlockPosArgument.getLoadedBlockPos(c,
                                "pos")), verb, action)));
    }

    private static int one(CommandSourceStack source, PcCaseBlockEntity pc, String verb,
            Function<PcCaseBlockEntity, Component> action) {
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

    private static int all(CommandSourceStack source, String verb, Function<PcCaseBlockEntity, Component> action) {
        PcServer server = PcServer.get();
        if (server == null)
            return 0;
        int done = 0;
        for (PcCaseBlockEntity pc : server.loadedCases())
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
        PcCaseBlockEntity pc = find(source, pos);
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
    private static PcCaseBlockEntity find(CommandSourceStack source, BlockPos pos) {
        BlockPos at = pos;
        if (at == null) {
            ServerPlayer player = source.getPlayer();
            HitResult hit = player == null ? null : player.pick(REACH, 1, false);
            if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK)
                at = block.getBlockPos();
        }
        if (at != null && source.getLevel().getBlockEntity(at) instanceof PcCaseBlockEntity pc)
            return pc;
        source.sendFailure(Component.translatable("commands.create_flippies.pc.no_case"));
        return null;
    }

    private static String pos(PcCaseBlockEntity pc) {
        BlockPos p = pc.getBlockPos();
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }
}
