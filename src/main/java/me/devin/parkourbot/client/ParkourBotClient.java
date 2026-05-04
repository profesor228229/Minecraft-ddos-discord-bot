package me.devin.parkourbot.client;

import me.devin.parkourbot.client.command.GotoCommand;
import me.devin.parkourbot.client.freecam.Freecam;
import me.devin.parkourbot.client.keybind.KeyBindings;
import me.devin.parkourbot.client.movement.MovementExecutor;
import me.devin.parkourbot.client.pathfinder.Pathfinder;
import me.devin.parkourbot.client.render.PathRenderer;
import me.devin.parkourbot.client.util.RayUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Client entry point. Wires together keybinds, the freecam, the pathfinder, the movement
 * executor and the goto chat command.
 *
 * <p>State machine for the bot:
 * <pre>
 *   IDLE  --(set goal + enable)-->  PATHING  --(path found)-->  EXECUTING
 *                                       |                           |
 *                                       \--(no path / cancel)----> IDLE
 * </pre>
 */
public final class ParkourBotClient implements ClientModInitializer {
    public static final String MOD_ID = "parkourbot";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private static final ParkourBotState STATE = new ParkourBotState();

    public static ParkourBotState state() {
        return STATE;
    }

    @Override
    public void onInitializeClient() {
        KeyBindings.register();
        GotoCommand.register();
        PathRenderer.register();

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndClientTick);
        LOGGER.info("[ParkourBot] initialised. Open Controls -> Parkour Bot to bind keys.");
    }

    private void onEndClientTick(MinecraftClient client) {
        // 1) Drain keybinds.
        KeyBindings.poll(client);

        // 2) Tick the freecam (movement, raycast on right click).
        Freecam.INSTANCE.tick(client);

        if (client.player == null || client.world == null) {
            STATE.setExecuting(false);
            return;
        }

        // 3) If a goal is set and the bot is enabled, ensure we have a path then run the executor.
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;

        if (STATE.isEnabled() && STATE.getGoal() != null) {
            if (STATE.getPath() == null && !STATE.isPathing()) {
                STATE.setPathing(true);
                BlockPos start = player.getBlockPos();
                BlockPos goal = STATE.getGoal();
                LOGGER.info("[ParkourBot] computing path {} -> {}", start, goal);
                new Thread(() -> {
                    try {
                        Pathfinder pf = new Pathfinder(world);
                        Pathfinder.Result r = pf.findPath(start, goal);
                        // Treat a single-step "best effort" path as a failure \u2014 it would
                        // otherwise make the bot say "arrived" instantly without doing
                        // anything, which looks like the bot just turning off.
                        boolean trivial = r.path != null && r.path.size() <= 1
                                && !"ok".equals(r.reason) && !"already at goal".equals(r.reason);
                        if (r.path == null || trivial) {
                            final String reason = r.reason == null ? "unknown" : r.reason;
                            LOGGER.info("[ParkourBot] no path found ({})", reason);
                            STATE.setPath(null);
                            client.execute(() -> {
                                if (client.player != null) {
                                    client.player.sendMessage(new LiteralText(
                                            "\u00a7c[ParkourBot] no path found (" + reason + ")"), false);
                                }
                            });
                            STATE.setEnabled(false);
                        } else {
                            STATE.setPath(r.path);
                            client.execute(() -> {
                                if (client.player != null) {
                                    String suffix = "ok".equals(r.reason) ? "" : " \u00a77(" + r.reason + ")";
                                    client.player.sendMessage(new LiteralText(
                                            "\u00a7a[ParkourBot] path of " + r.path.size() + " steps" + suffix), false);
                                }
                            });
                        }
                    } catch (Throwable t) {
                        LOGGER.error("[ParkourBot] pathfinder crashed", t);
                        STATE.setPath(null);
                        STATE.setEnabled(false);
                    } finally {
                        STATE.setPathing(false);
                    }
                }, "ParkourBot-Pathfinder").start();
            }

            if (STATE.getPath() != null) {
                MovementExecutor.INSTANCE.tick(client, STATE);
            }
        } else {
            MovementExecutor.INSTANCE.reset();
        }
    }

    /**
     * Entry called by the "set goal at crosshair" keybind.
     * In freecam, we raycast from the freecam camera; otherwise from the player's eyes.
     */
    public static void setGoalAtCrosshair(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        BlockHitResult hit;
        if (Freecam.INSTANCE.isEnabled()) {
            hit = RayUtil.raycastFromFreecam(client, Freecam.INSTANCE);
        } else {
            hit = RayUtil.raycastFromEntity(client.player, 256.0);
        }

        if (hit == null || hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) {
            client.player.sendMessage(new LiteralText(
                    "\u00a7c[ParkourBot] crosshair points at nothing"), false);
            return;
        }
        // Stand on top of the block we are pointing at.
        BlockPos goal = hit.getBlockPos().up();
        STATE.setGoal(goal);
        STATE.setPath(null);
        client.player.sendMessage(new LiteralText(
                "\u00a7e[ParkourBot] goal set: " + goal.getX() + " " + goal.getY() + " " + goal.getZ()), false);
    }
}
