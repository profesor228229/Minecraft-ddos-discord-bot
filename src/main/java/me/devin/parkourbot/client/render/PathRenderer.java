package me.devin.parkourbot.client.render;

import me.devin.parkourbot.client.ParkourBotClient;
import me.devin.parkourbot.client.pathfinder.Path;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;

/**
 * Renders the bot's current {@link Path} as a chain of colored line segments connecting
 * consecutive step centers, plus a green X at the goal block.
 *
 * <p>Uses {@link PathRenderLayer#THROUGH_LINES}, a custom {@code RenderLayer} that draws
 * GL_LINES through walls. We submit vertices to the entity vertex consumer immediate
 * provider, then explicitly flush our layer with {@code consumers.draw(LAYER)}. This
 * delegates all GL state management (depth test, blending, line width, etc.) to vanilla's
 * render pipeline, which is far more reliable than poking the GL state directly.
 */
public final class PathRenderer {
    private PathRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(PathRenderer::render);
    }

    private static void render(WorldRenderContext ctx) {
        Path path = ParkourBotClient.state().getPath();
        BlockPos goal = ParkourBotClient.state().getGoal();
        if ((path == null || path.size() < 1) && goal == null) return;

        VertexConsumerProvider.Immediate consumers = (VertexConsumerProvider.Immediate) ctx.consumers();
        if (consumers == null) {
            consumers = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
        }
        if (consumers == null) return;

        MatrixStack stack = ctx.matrixStack();
        Vec3d cam = ctx.camera().getPos();
        stack.push();
        stack.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mat = stack.peek().getModel();

        VertexConsumer lines = consumers.getBuffer(PathRenderLayer.THROUGH_LINES);

        if (path != null && path.size() >= 2) {
            for (int i = 1; i < path.size(); i++) {
                Path.Step prev = path.get(i - 1);
                Path.Step cur = path.get(i);
                int[] c = colorFor(cur.kind);

                float ax = prev.pos.getX() + 0.5f;
                float ay = prev.pos.getY() + 0.06f;
                float az = prev.pos.getZ() + 0.5f;
                float bx = cur.pos.getX() + 0.5f;
                float by = cur.pos.getY() + 0.06f;
                float bz = cur.pos.getZ() + 0.5f;

                lines.vertex(mat, ax, ay, az).color(c[0], c[1], c[2], 220).next();
                lines.vertex(mat, bx, by, bz).color(c[0], c[1], c[2], 220).next();

                // Vertical pole on the take-off block for parkour jumps so the user
                // can see exactly where the bot is going to leave the ground.
                if (Path.isParkourJump(cur.kind)) {
                    lines.vertex(mat, ax, ay, az).color(255, 80, 255, 220).next();
                    lines.vertex(mat, ax, prev.pos.getY() + 1.2f, az).color(255, 80, 255, 220).next();
                }
            }
        }

        if (goal != null) {
            float gx = goal.getX() + 0.5f;
            float gy = goal.getY() + 0.06f;
            float gz = goal.getZ() + 0.5f;
            int gr = 50, gg = 255, gb = 50, ga = 255;
            // X-cross on the floor of the goal block.
            lines.vertex(mat, gx - 0.45f, gy, gz - 0.45f).color(gr, gg, gb, ga).next();
            lines.vertex(mat, gx + 0.45f, gy, gz + 0.45f).color(gr, gg, gb, ga).next();
            lines.vertex(mat, gx - 0.45f, gy, gz + 0.45f).color(gr, gg, gb, ga).next();
            lines.vertex(mat, gx + 0.45f, gy, gz - 0.45f).color(gr, gg, gb, ga).next();
            // Vertical pole.
            lines.vertex(mat, gx, gy, gz).color(gr, gg, gb, ga).next();
            lines.vertex(mat, gx, gy + 1.5f, gz).color(gr, gg, gb, ga).next();
        }

        consumers.draw(PathRenderLayer.THROUGH_LINES);

        stack.pop();
    }

    private static int[] colorFor(Path.Kind kind) {
        switch (kind) {
            case WALK:
            case WALK_DIAG:
                return new int[]{255, 255, 255};
            case JUMP_UP_1:
                return new int[]{255, 220, 50};
            case DROP:
                return new int[]{80, 180, 255};
            case PARKOUR2:
            case PARKOUR3:
            case PARKOUR4:
            case PARKOUR5:
            case PARKOUR_UP2:
            case PARKOUR_UP3:
                return new int[]{255, 80, 255};
            case START:
            default:
                return new int[]{160, 160, 160};
        }
    }
}
