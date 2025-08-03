package com.DonutAddon.addon.modules.AI;

import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.LinkedList;

public class BacktrackManager {
    private final LinkedList<MoveSegment> moveHistory = new LinkedList<>();
    private MoveSegment currentSegment;
    private boolean isRetracing = false;
    private boolean isBacktracking = false;
    private int retraceBlocks = 0;
    private int backtrackBlocks = 0;

    public static class MoveSegment {
        public final Direction direction;
        public int blocksMoved;
        public final Vec3d startPos;

        public MoveSegment(Direction direction, Vec3d startPos) {
            this.direction = direction;
            this.startPos = startPos;
            this.blocksMoved = 0;
        }
    }

    public static class BacktrackPlan {
        public final boolean needsRetrace;
        public final boolean needsBacktrack;
        public final Direction retraceDirection;
        public final int retraceDistance;
        public final Direction backtrackDirection;
        public final int backtrackDistance;
        public final String description;

        public BacktrackPlan(boolean needsRetrace, boolean needsBacktrack,
                             Direction retraceDirection, int retraceDistance,
                             Direction backtrackDirection, int backtrackDistance,
                             String description) {
            this.needsRetrace = needsRetrace;
            this.needsBacktrack = needsBacktrack;
            this.retraceDirection = retraceDirection;
            this.retraceDistance = retraceDistance;
            this.backtrackDirection = backtrackDirection;
            this.backtrackDistance = backtrackDistance;
            this.description = description;
        }
    }

    public void startNewSegment(Direction direction, Vec3d startPos) {
        if (currentSegment != null && currentSegment.blocksMoved > 0) {
            moveHistory.addLast(currentSegment);
            // Keep only last 5 segments
            if (moveHistory.size() > 5) {
                moveHistory.removeFirst();
            }
        }
        currentSegment = new MoveSegment(direction, startPos);
    }

    public void recordMovement() {
        if (currentSegment != null) {
            currentSegment.blocksMoved++;
        }
    }

    public BacktrackPlan createBacktrackPlan(Direction mainTunnelDirection, int backtrackDistance) {
        // If we're in the main tunnel, just backtrack
        if (currentSegment != null && currentSegment.direction == mainTunnelDirection) {
            return new BacktrackPlan(
                false, true,
                null, 0,
                mainTunnelDirection.getOpposite(), backtrackDistance,
                "Backtrack in main tunnel"
            );
        }

        // We're in a side tunnel, need to retrace first
        if (currentSegment != null && currentSegment.blocksMoved > 0) {
            return new BacktrackPlan(
                true, true,
                currentSegment.direction.getOpposite(), currentSegment.blocksMoved,
                mainTunnelDirection.getOpposite(), backtrackDistance,
                "Retrace side tunnel then backtrack in main"
            );
        }

        // No movement recorded, just backtrack
        return new BacktrackPlan(
            false, true,
            null, 0,
            mainTunnelDirection.getOpposite(), backtrackDistance,
            "Direct backtrack"
        );
    }

    public void startRetrace(int blocks) {
        isRetracing = true;
        retraceBlocks = blocks;
    }

    public void startBacktrack(int blocks) {
        isBacktracking = true;
        backtrackBlocks = blocks;
    }

    public boolean updateRetrace() {
        if (retraceBlocks > 0) {
            retraceBlocks--;
            return retraceBlocks == 0;
        }
        return true;
    }

    public boolean updateBacktrack() {
        if (backtrackBlocks > 0) {
            backtrackBlocks--;
            return backtrackBlocks == 0;
        }
        return true;
    }

    public void reset() {
        isRetracing = false;
        isBacktracking = false;
        retraceBlocks = 0;
        backtrackBlocks = 0;
    }

    public boolean isRetracing() { return isRetracing; }
    public boolean isBacktracking() { return isBacktracking; }
    public int getCurrentSegmentBlocks() { return currentSegment != null ? currentSegment.blocksMoved : 0; }
}
