package com.nectariich.create_flippies.content.modem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.computer.net.PackContent;
import com.nectariich.create_flippies.content.computer.net.PackSet;
import com.nectariich.create_flippies.content.computer.net.SharedFolder;
import com.nectariich.create_flippies.content.pc.PcServer;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
//? if forge {
import net.minecraftforge.event.RegisterCommandsEvent;
//?} else {
/*import net.neoforged.neoforge.event.RegisterCommandsEvent;
*///?}
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /flippies packs} lists the network modems' content packs in lookup order and the hosts
 * they serve; {@code /flippies packs reload} scans the pack folders again (spec 12.4.3). Operators
 * only (level 2). A reload leaves downloads in progress on the packs they started on.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PackCommands {

    private PackCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("flippies").then(Commands.literal("packs")
                .requires(source -> source.hasPermission(2))
                .executes(context -> list(context.getSource()))
                .then(Commands.literal("reload").executes(context -> reload(context.getSource())))));
    }

    private static int list(CommandSourceStack source) {
        ModemPacks packs = packs(source);
        if (packs == null)
            return 0;
        PackSet set = packs.registry().current();
        List<String> lines = new ArrayList<>();
        lines.add("Content packs in lookup order (" + set.packs().size() + "):");
        Map<String, List<String>> hosts = new TreeMap<>();
        int n = 1;
        for (PackContent p : set.packs()) {
            lines.add(" " + n++ + ". " + p.manifest().id() + " " + p.manifest().version() + " \"" + p.manifest().title()
                    + "\": " + String.join(", ", p.manifest().hosts()) + " (" + (packs.inWorld(p) ? "world" : "server")
                    + ": " + p.file().getFileName() + ")");
            for (String h : p.manifest().hosts())
                hosts.computeIfAbsent(h, k -> new ArrayList<>()).add(p.manifest().id());
        }
        for (String h : set.hosts())
            hosts.computeIfAbsent(h, k -> new ArrayList<>());
        List<String> served = new ArrayList<>();
        served.add(SharedFolder.HOST + " (the world's file share)");
        hosts.forEach((h, ids) -> served.add(h + " (" + (ids.isEmpty() ? "built in" : String.join(", ", ids)) + ")"));
        lines.add("Hosts: " + String.join(", ", served));
        lines.add("Folders: " + packs.registry().folders());
        String text = String.join("\n", lines);
        source.sendSuccess(() -> Component.literal(text), false);
        return set.packs().size();
    }

    private static int reload(CommandSourceStack source) {
        ModemPacks packs = packs(source);
        if (packs == null)
            return 0;
        List<String> problems = new ArrayList<>();
        PackSet set = packs.reload(problems::add);
        for (String problem : problems)
            source.sendFailure(Component.literal(problem));
        source.sendSuccess(() -> Component.literal("Reloaded the content packs: " + set.packs().size() + " loaded"), true);
        return set.packs().size();
    }

    private static ModemPacks packs(CommandSourceStack source) {
        PcServer server = PcServer.get();
        if (server == null) {
            source.sendFailure(Component.literal("No server is running"));
            return null;
        }
        return server.packs();
    }
}
