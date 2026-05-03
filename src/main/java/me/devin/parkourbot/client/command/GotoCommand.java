package me.devin.parkourbot.client.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import me.devin.parkourbot.client.ParkourBotClient;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v1.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;

/**
 * Registers /gotoxyz {x} {y} {z} which sets the bot's goal and enables it.
 */
public final class GotoCommand {
    private GotoCommand() {}

    public static void register() {
        ClientCommandManager.DISPATCHER.register(
                ClientCommandManager.literal("gotoxyz")
                        .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                .executes(GotoCommand::run)))));

        ClientCommandManager.DISPATCHER.register(
                ClientCommandManager.literal("parkourstop")
                        .executes(ctx -> {
                            ParkourBotClient.state().setEnabled(false);
                            ParkourBotClient.state().setPath(null);
                            ParkourBotClient.state().setGoal(null);
                            ((FabricClientCommandSource) ctx.getSource()).sendFeedback(
                                    new LiteralText("\u00a7c[ParkourBot] stopped"));
                            return 1;
                        }));
    }

    private static int run(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx) {
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        BlockPos goal = new BlockPos(x, y, z);

        ParkourBotClient.state().setGoal(goal);
        ParkourBotClient.state().setPath(null);
        ParkourBotClient.state().setEnabled(true);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(new LiteralText(
                    "\u00a7e[ParkourBot] going to " + x + " " + y + " " + z), false);
        }
        return 1;
    }
}
