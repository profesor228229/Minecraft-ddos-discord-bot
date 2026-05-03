package me.devin.parkourbot.client;

import me.devin.parkourbot.client.pathfinder.Path;
import net.minecraft.util.math.BlockPos;

/**
 * Mutable, single-instance, main-thread-only state for the bot. The pathfinder thread
 * is allowed to call {@link #setPath(Path)} once it finishes.
 */
public final class ParkourBotState {
    private volatile boolean enabled;
    private volatile boolean pathing;
    private volatile BlockPos goal;
    private volatile Path path;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    public boolean isPathing() { return pathing; }
    public void setPathing(boolean v) { this.pathing = v; }

    public BlockPos getGoal() { return goal; }
    public void setGoal(BlockPos g) { this.goal = g; }

    public Path getPath() { return path; }
    public void setPath(Path p) { this.path = p; }

    /** Bot is currently driving the player. Used to guard input mixins. */
    private volatile boolean executing;
    public boolean isExecuting() { return executing; }
    public void setExecuting(boolean v) { this.executing = v; }
}
