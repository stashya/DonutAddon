package com.DonutAddon.addon.modules.AI;

import net.minecraft.util.math.Direction;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class DirectionManager {
    private Direction currentDirection;
    private Direction lastMovementDirection; // Last direction we successfully moved in

    public final Map<Direction, Integer> totalBlocksMined = new HashMap<>();
    private final Map<Direction, Boolean> activeHazards = new HashMap<>();
    private final Map<Direction, Integer> consecutiveHazards = new HashMap<>();

    private int blocksSinceLastTurn = 0;
    private boolean justBacktracked = false;

    private final Random random = new Random();

    public static class DirectionChoice {
        public final Direction direction;
        public final boolean needsBacktrack;
        public final String reason;

        public DirectionChoice(Direction direction, boolean needsBacktrack, String reason) {
            this.direction = direction;
            this.needsBacktrack = needsBacktrack;
            this.reason = reason;
        }
    }

    public DirectionManager() {
        for (Direction dir : getCardinalDirections()) {
            totalBlocksMined.put(dir, 0);
            activeHazards.put(dir, false);
            consecutiveHazards.put(dir, 0);
        }
    }

    public void setInitialDirection(Direction dir) {
        this.currentDirection = dir;
        this.lastMovementDirection = dir;
        this.blocksSinceLastTurn = 0;
        this.justBacktracked = false;
    }

    public Direction getCurrentDirection() {
        return currentDirection;
    }

    public Direction getMainTunnel() {
        // Find the direction with the most blocks mined
        Direction mainDir = currentDirection;
        int maxBlocks = 0;

        for (Map.Entry<Direction, Integer> entry : totalBlocksMined.entrySet()) {
            if (entry.getValue() > maxBlocks) {
                maxBlocks = entry.getValue();
                mainDir = entry.getKey();
            }
        }

        return mainDir;
    }

    public DirectionChoice getNextDirection() {
        // Don't clear hazards - they persist until explicitly cleared

        // If we just backtracked, we already have a plan
        if (justBacktracked) {
            justBacktracked = false;
            return getNextDirectionAfterBacktrack();
        }

        // Mark current direction as hazardous
        activeHazards.put(currentDirection, true);
        consecutiveHazards.put(currentDirection, consecutiveHazards.get(currentDirection) + 1);

        // If we've barely moved in this direction and hit a hazard, it's probably blocked
        boolean immediateHazard = blocksSinceLastTurn < 3;

        // Get the main tunnel direction (most blocks mined)
        Direction mainTunnel = getMainTunnel();

        // If we're in the main tunnel
        if (currentDirection == mainTunnel) {
            // Try perpendicular directions
            Direction left = currentDirection.rotateYCounterclockwise();
            Direction right = currentDirection.rotateYClockwise();

            boolean leftClear = !activeHazards.get(left) || totalBlocksMined.get(left) == 0;
            boolean rightClear = !activeHazards.get(right) || totalBlocksMined.get(right) == 0;

            if (leftClear && rightClear) {
                // Both available, choose the one with fewer blocks mined
                Direction chosen;
                if (totalBlocksMined.get(left) < totalBlocksMined.get(right)) {
                    chosen = left;
                } else if (totalBlocksMined.get(right) < totalBlocksMined.get(left)) {
                    chosen = right;
                } else {
                    chosen = random.nextBoolean() ? left : right;
                }

                lastMovementDirection = currentDirection;
                currentDirection = chosen;
                blocksSinceLastTurn = 0;
                return new DirectionChoice(chosen, false, "Side tunnel from main");
            } else if (leftClear) {
                lastMovementDirection = currentDirection;
                currentDirection = left;
                blocksSinceLastTurn = 0;
                return new DirectionChoice(left, false, "Left clear from main");
            } else if (rightClear) {
                lastMovementDirection = currentDirection;
                currentDirection = right;
                blocksSinceLastTurn = 0;
                return new DirectionChoice(right, false, "Right clear from main");
            } else {
                // Both sides have hazards, need to backtrack in main tunnel
                return new DirectionChoice(null, true, "Backtrack in main tunnel");
            }
        } else {
            // We're in a side tunnel

            // If this is an immediate hazard (hit lava right after turning), try other perpendicular
            if (immediateHazard) {
                // We came from the main tunnel, try the other perpendicular
                Direction opposite = lastMovementDirection.getOpposite();
                Direction otherPerpendicular = null;

                for (Direction dir : getCardinalDirections()) {
                    if (dir != currentDirection && dir != lastMovementDirection && dir != opposite) {
                        otherPerpendicular = dir;
                        break;
                    }
                }

                if (otherPerpendicular != null && !activeHazards.get(otherPerpendicular)) {
                    currentDirection = otherPerpendicular;
                    blocksSinceLastTurn = 0;
                    return new DirectionChoice(otherPerpendicular, false, "Other perpendicular after immediate hazard");
                }
            }

            // If we've mined a decent distance in this side tunnel, we can:
            // 1. Continue straight (push through the hazard)
            // 2. Return to main tunnel
            // 3. Try the opposite of main tunnel

            // If we've established this side tunnel (mined > 10 blocks), consider continuing
            if (totalBlocksMined.get(currentDirection) > 10 && !immediateHazard) {
                // Continue in same direction (will push through hazard)
                blocksSinceLastTurn = 0;
                activeHazards.put(currentDirection, false); // Clear hazard to continue
                return new DirectionChoice(currentDirection, false, "Continue established side tunnel");
            }

            // Try to return to main tunnel if it's been a while
            if (!activeHazards.get(mainTunnel) || consecutiveHazards.get(mainTunnel) < 2) {
                lastMovementDirection = currentDirection;
                currentDirection = mainTunnel;
                blocksSinceLastTurn = 0;
                // Clear the hazard flag for main tunnel to retry
                activeHazards.put(mainTunnel, false);
                consecutiveHazards.put(mainTunnel, 0);
                return new DirectionChoice(mainTunnel, false, "Return to main tunnel");
            }

            // Try opposite of main tunnel
            Direction opposite = mainTunnel.getOpposite();
            if (!activeHazards.get(opposite)) {
                lastMovementDirection = currentDirection;
                currentDirection = opposite;
                blocksSinceLastTurn = 0;
                return new DirectionChoice(opposite, false, "Opposite of main tunnel");
            }

            // Everything is blocked, backtrack in current direction
            return new DirectionChoice(null, true, "Backtrack in side tunnel");
        }
    }

    public DirectionChoice getNextDirectionAfterBacktrack() {
        // Clear hazards for the direction we backtracked from
        activeHazards.put(currentDirection, false);
        consecutiveHazards.put(currentDirection, 0);

        // We've backtracked, now try perpendiculars again
        Direction left = currentDirection.rotateYCounterclockwise();
        Direction right = currentDirection.rotateYClockwise();

        // Clear their hazard flags since we've moved back
        activeHazards.put(left, false);
        activeHazards.put(right, false);

        // Choose based on which has been mined less
        if (totalBlocksMined.get(left) < totalBlocksMined.get(right)) {
            currentDirection = left;
            blocksSinceLastTurn = 0;
            return new DirectionChoice(left, false, "Left after backtrack");
        } else if (totalBlocksMined.get(right) < totalBlocksMined.get(left)) {
            currentDirection = right;
            blocksSinceLastTurn = 0;
            return new DirectionChoice(right, false, "Right after backtrack");
        } else {
            Direction chosen = random.nextBoolean() ? left : right;
            currentDirection = chosen;
            blocksSinceLastTurn = 0;
            return new DirectionChoice(chosen, false, "Random after backtrack");
        }
    }

    public void recordMovement(int blocks) {
        totalBlocksMined.put(currentDirection, totalBlocksMined.get(currentDirection) + blocks);
        blocksSinceLastTurn += blocks;
    }

    public void recordHazard(Direction dir, int blocksMined) {
        // Don't record movement here - it's already tracked in recordMovement
        activeHazards.put(dir, true);
    }

    public void markBacktrackComplete() {
        justBacktracked = true;
    }

    private Direction[] getCardinalDirections() {
        return new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };
    }
}
