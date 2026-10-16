package com.nectariich.create_flippies.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchTurnMode;

import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /flippies turnmode [push|pushInverted|drag]} — which gesture turns a
 * rotary switch, and with no argument, which one is in force.
 *
 * A client command rather than a server one, because the mode it sets is a client
 * one: {@link RotarySwitchPushInput} and {@link RotarySwitchDragInput} decide how
 * the mouse is read and send the server the same notch either way. Three things
 * follow from that, all of them wanted:
 *
 * <ul>
 * <li>Anybody can run it. Client commands are dispatched locally against a source
 * that never consults a permission level, and no node here asks for one, so it works
 * for a player with no operator status on somebody else's server.
 * <li>Nothing it says reaches public chat. The command never leaves the client, so
 * there is no server to echo it and no operators to be notified — and the
 * {@code false} passed to every {@code sendSuccess} below asks for no logging on top
 * of that.
 * <li>It is per player. Each client keeps its own answer in its own config file, so
 * everyone on a server chooses for themselves and nothing has to be synchronised.
 * </ul>
 *
 * The choice is persisted by {@code ClientConfig} and so survives restarts; see
 * {@link RotarySwitchTurnMode} for why that enum lives outside this package.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class RotarySwitchTurnModeCommand {

    private static final String PREFIX = "commands." + CreateFlippies.MOD_ID + ".turnmode.";

    /** Named once: it is both what is registered and what the join notice offers. */
    private static final String COMMAND = "/flippies turnmode";

    private RotarySwitchTurnModeCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> turnmode = Commands.literal("turnmode")
                .executes(context -> report(context.getSource()));
        // A literal per mode rather than a string argument, so every spelling tab
        // completes and an unknown one is a parse error rather than a runtime
        // complaint — and a mode added to the enum registers itself here
        for (RotarySwitchTurnMode mode : RotarySwitchTurnMode.values())
            turnmode = turnmode.then(Commands.literal(mode.id())
                    .executes(context -> set(context.getSource(), mode)));

        event.getDispatcher().register(Commands.literal("flippies").then(turnmode));
    }

    /**
     * A line on joining a world, saying which gesture is in force and how to change
     * it. Written straight into this client's own chat, so it is seen by the one
     * player it concerns and by nobody else.
     *
     * Said on every join rather than once ever, because the setting now outlives the
     * session and a player who set it weeks ago has no other reminder of why their
     * switches behave the way they do.
     */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        LocalPlayer player = event.getPlayer();
        if (player == null)
            return;
        player.displayClientMessage(Component.translatable(PREFIX + "hint",
                RotarySwitchDragInput.mode().label(), commandLink()), false);
    }

    private static int set(CommandSourceStack source, RotarySwitchTurnMode mode) {
        RotarySwitchDragInput.setMode(mode);
        source.sendSuccess(() -> Component.translatable(PREFIX + "set", mode.label()), false);
        if (mode.isExperimental())
            source.sendSuccess(() -> Component.translatable(PREFIX + "experimental")
                    .withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int report(CommandSourceStack source) {
        RotarySwitchTurnMode mode = RotarySwitchDragInput.mode();
        source.sendSuccess(() -> Component.translatable(PREFIX + "query", mode.label()), false);
        return 1;
    }

    /** The command, clickable: one click puts it in the chat box ready to finish. */
    private static Component commandLink() {
        return Component.literal(COMMAND).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, COMMAND + " "))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable(PREFIX + "hint.hover"))));
    }
}
