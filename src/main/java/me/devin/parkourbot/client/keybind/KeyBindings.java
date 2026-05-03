package me.devin.parkourbot.client.keybind;

import me.devin.parkourbot.client.ParkourBotClient;
import me.devin.parkourbot.client.ParkourBotState;
import me.devin.parkourbot.client.freecam.Freecam;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.LiteralText;
import org.lwjgl.glfw.GLFW;

public final class KeyBindings {
    public static final String CATEGORY = "key.categories.parkourbot";

    public static KeyBinding TOGGLE_BOT;
    public static KeyBinding STOP_BOT;
    public static KeyBinding SET_GOAL;
    public static KeyBinding TOGGLE_FREECAM;

    private KeyBindings() {}

    public static void register() {
        TOGGLE_BOT = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.parkourbot.toggle_bot",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));
        STOP_BOT = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.parkourbot.stop_bot",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY));
        SET_GOAL = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.parkourbot.set_goal",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));
        TOGGLE_FREECAM = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.parkourbot.toggle_freecam",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));
    }

    /** Called once per client tick. */
    public static void poll(MinecraftClient client) {
        if (client.player == null) return;
        ParkourBotState state = ParkourBotClient.state();

        while (TOGGLE_BOT.wasPressed()) {
            if (state.getGoal() == null) {
                client.player.sendMessage(new LiteralText(
                        "\u00a7c[ParkourBot] no goal set. Look at a block and press G, or use /gotoxyz x y z."), false);
            } else {
                state.setEnabled(!state.isEnabled());
                if (state.isEnabled()) {
                    state.setPath(null); // force replan from current pos
                    client.player.sendMessage(new LiteralText("\u00a7a[ParkourBot] enabled"), false);
                } else {
                    client.player.sendMessage(new LiteralText("\u00a7e[ParkourBot] disabled"), false);
                }
            }
        }

        while (STOP_BOT.wasPressed()) {
            state.setEnabled(false);
            state.setPath(null);
            state.setGoal(null);
            client.player.sendMessage(new LiteralText("\u00a7c[ParkourBot] stopped"), false);
        }

        while (SET_GOAL.wasPressed()) {
            ParkourBotClient.setGoalAtCrosshair(client);
        }

        while (TOGGLE_FREECAM.wasPressed()) {
            Freecam.INSTANCE.toggle(client);
        }
    }
}
