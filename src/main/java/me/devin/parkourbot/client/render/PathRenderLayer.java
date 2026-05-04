package me.devin.parkourbot.client.render;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.lwjgl.opengl.GL11;

import java.util.OptionalDouble;

/**
 * Custom {@link RenderLayer} for the bot's path lines. Subclasses {@link RenderLayer}
 * (which extends {@link RenderPhase}) so it can reference the protected
 * {@code ALWAYS_DEPTH_TEST}, {@code VIEW_OFFSET_Z_LAYERING}, etc. constants from outside
 * {@code net.minecraft.client.render}.
 *
 * <p>The layer:
 * <ul>
 *   <li>Draws {@code GL_LINES} with line width 2.5</li>
 *   <li>{@code ALWAYS_DEPTH_TEST} — visible through walls</li>
 *   <li>{@code VIEW_OFFSET_Z_LAYERING} — pulled towards the camera so lines don't
 *   z-fight with block faces</li>
 *   <li>Translucent blending so we can use alpha</li>
 *   <li>{@code DISABLE_CULLING} — both sides visible</li>
 * </ul>
 */
public final class PathRenderLayer extends RenderLayer {
    public static final RenderLayer THROUGH_LINES = RenderLayer.of(
            "parkourbot_through_lines",
            VertexFormats.POSITION_COLOR,
            GL11.GL_LINES,
            256,
            false,
            false,
            RenderLayer.MultiPhaseParameters.builder()
                    .lineWidth(new RenderPhase.LineWidth(OptionalDouble.of(2.5)))
                    .layering(VIEW_OFFSET_Z_LAYERING)
                    .transparency(TRANSLUCENT_TRANSPARENCY)
                    .target(ITEM_TARGET)
                    .writeMaskState(COLOR_MASK)
                    .cull(DISABLE_CULLING)
                    .depthTest(ALWAYS_DEPTH_TEST)
                    .build(false));

    /** Never instantiated — only here to expose the protected constants of {@code RenderPhase}. */
    private PathRenderLayer(String name, VertexFormat fmt, int drawMode, int sz,
                            boolean hasCrumbling, boolean translucent,
                            Runnable startAction, Runnable endAction) {
        super(name, fmt, drawMode, sz, hasCrumbling, translucent, startAction, endAction);
        throw new UnsupportedOperationException();
    }
}
