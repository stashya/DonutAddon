package io.github.stashya.donutaddon.modules.AI;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class DirectionalPathfinder {
    private Direction primaryDirection;
    private Direction originalPrimaryDirection;
    private Queue<BlockPos> currentDetour = new LinkedList<>();
    private boolean isDetouring = false;
    private final PathScanner pathScanner;

    // Debug tracking
    private int detourCount = 0;
    private int directionChangeCount = 0;
    private String lastDecisionReason = "";

    public DirectionalPathfinder(PathScanner scanner) {
        this.pathScanner = scanner;
        System.out.println("=== DirectionalPathfinder Initialized ===");
    }

    public void setInitialDirection(Direction dir) {
        this.primaryDirection = dir;
        this.originalPrimaryDirection = dir;
        System.out.println("DEBUG: Primary direction set to " + dir.getName());
        System.out.println("DEBUG: Will maintain " + dir.getName() + " as primary unless completely blocked");
    }

    public Direction getPrimaryDirection() {
        return primaryDirection;
    }

    public boolean isDetouring() {
        return isDetouring;
    }

    public Queue<BlockPos> peekAllWaypoints() {
        return new LinkedList<>(currentDetour);
    }

    public BlockPos getNextWaypoint() {
        if (currentDetour.isEmpty()) {
            System.out.println("DEBUG: No more waypoints, detour complete");
            isDetouring = false;
            return null;
        }
        BlockPos next = currentDetour.poll();
        System.out.println("DEBUG: Next waypoint: " + next + " (" + currentDetour.size() + " remaining)");
        return next;
    }

    public BlockPos peekNextWaypoint() {
        return currentDetour.peek();
    }

    public static class PathPlan {
        public final boolean needsDetour;
        public final Queue<BlockPos> waypoints;
        public final String reason;
        public final Direction newPrimaryDirection;

        public PathPlan(boolean needsDetour, Queue<BlockPos> waypoints, String reason) {
            this.needsDetour = needsDetour;
            this.waypoints = waypoints;
            this.reason = reason;
            this.newPrimaryDirection = null;
        }

        public PathPlan(Direction newDirection, String reason) {
            this.needsDetour = false;
            this.waypoints = new LinkedList<>();
            this.reason = reason;
            this.newPrimaryDirection = newDirection;
        }
    }

    public PathPlan calculateDetour(BlockPos playerPos, PathScanner.ScanResult hazard) {
        System.out.println("\n=== CALCULATING DETOUR ===");
        System.out.println("Player position: " + playerPos);
        System.out.println("Hazard type: " + hazard.getHazardType());
        System.out.println("Hazard distance: " + hazard.getHazardDistance());
        System.out.println("Maintaining primary direction: " + primaryDirection.getName());

        // NEW: APPROACH LOGIC FOR DISTANT HAZARDS
        if (hazard.getHazardDistance() > 10) {
            System.out.println("Hazard is " + hazard.getHazardDistance() + " blocks away (far)");
            return createApproachPlan(playerPos, hazard);
        }

        // For nearby hazards (10 blocks or less), plan a detour
        System.out.println("Hazard is close (" + hazard.getHazardDistance() + " blocks), planning detour");

        // Find hazard boundaries
        HazardBounds bounds = scanHazardBoundaries(playerPos, hazard);
        System.out.println("Hazard boundaries: width=" + bounds.width + ", depth=" + bounds.depth);

        // Try the most efficient paths first
        List<BlockPos> leftPath = buildMinimalDetourPath(playerPos, bounds, true);
        List<BlockPos> rightPath = buildMinimalDetourPath(playerPos, bounds, false);

        System.out.println("Left detour: " + (leftPath != null ? leftPath.size() + " waypoints" : "BLOCKED"));
        System.out.println("Right detour: " + (rightPath != null ? rightPath.size() + " waypoints" : "BLOCKED"));

        // Choose shortest valid path
        if (leftPath != null && rightPath != null) {
            if (leftPath.size() <= rightPath.size()) {
                System.out.println("DECISION: Taking LEFT detour (" + leftPath.size() + " waypoints)");
                return createDetourPlan(leftPath, "Left detour");
            } else {
                System.out.println("DECISION: Taking RIGHT detour (" + rightPath.size() + " waypoints)");
                return createDetourPlan(rightPath, "Right detour");
            }
        } else if (leftPath != null) {
            System.out.println("DECISION: Taking LEFT detour (only option)");
            return createDetourPlan(leftPath, "Left detour");
        } else if (rightPath != null) {
            System.out.println("DECISION: Taking RIGHT detour (only option)");
            return createDetourPlan(rightPath, "Right detour");
        } else {
            System.out.println("WARNING: No detour possible for close hazard, need to change primary direction");
            return handleCompletelyBlocked(playerPos);
        }
    }

    private PathPlan createApproachPlan(BlockPos playerPos, PathScanner.ScanResult hazard) {
        System.out.println("\n=== APPROACH PLAN ===");

        // Calculate safe approach distance (stop 7 blocks before hazard)
        int safeApproachDistance = Math.max(hazard.getHazardDistance() - 7, 0);

        if (safeApproachDistance <= 0) {
            // We're already too close, shouldn't happen but handle it
            System.out.println("Already within approach distance, treating as close hazard");
            return new PathPlan(false, new LinkedList<>(), "Already close to hazard");
        }

        // Create a single waypoint to approach the hazard
        BlockPos approachPoint = playerPos.offset(primaryDirection, safeApproachDistance);

        // Validate the approach path is safe
        System.out.println("Planning approach: " + safeApproachDistance + " blocks forward");
        if (!validateSegment(playerPos, approachPoint, primaryDirection, safeApproachDistance)) {
            System.out.println("Approach path blocked! Need to handle intermediate obstacle");
            // If we can't even approach, then we need to change direction
            return handleCompletelyBlocked(playerPos);
        }

        // Create simple approach plan with single waypoint
        List<BlockPos> approachPath = new ArrayList<>();
        approachPath.add(approachPoint);

        System.out.println("SUCCESS: Can safely approach to within 7 blocks of hazard");
        System.out.println("Will reassess when closer");

        Queue<BlockPos> waypoints = new LinkedList<>(approachPath);
        currentDetour = new LinkedList<>(waypoints);
        isDetouring = true; // Mark as detouring even though it's just approaching

        return new PathPlan(true, waypoints, "Approaching distant hazard");
    }

    private List<BlockPos> buildMinimalDetourPath(BlockPos playerPos, HazardBounds bounds, boolean goLeft) {
        Direction sideDir = goLeft ?
            primaryDirection.rotateYCounterclockwise() :
            primaryDirection.rotateYClockwise();

        String sideName = goLeft ? "LEFT" : "RIGHT";
        System.out.println("\nDEBUG: Building minimal " + sideName + " detour");

        // Calculate minimal side distance needed
        int sideDistance = (bounds.width / 2) + 2; // Just enough to clear hazard + safety

        // Try increasingly aggressive paths until one works
        for (int attempt = 0; attempt < 6; attempt++) {
            List<BlockPos> path = tryDetourPath(playerPos, bounds, sideDir, sideDistance + attempt);
            if (path != null) {
                System.out.println("SUCCESS: Found " + sideName + " path with " + path.size() + " waypoints");
                return path;
            }
        }

        return null;
    }

    private List<BlockPos> tryDetourPath(BlockPos playerPos, HazardBounds bounds, Direction sideDir, int sideDistance) {
        List<BlockPos> waypoints = new ArrayList<>();

        // Calculate key positions
        int approachDistance = Math.max(bounds.startDistance - 2, 1);
        int forwardPastHazard = bounds.depth + 3;

        // Waypoint 1: Turn point (get close to hazard before turning)
        BlockPos turnPoint = playerPos.offset(primaryDirection, approachDistance);
        turnPoint = adjustToGroundLevel(turnPoint); // Adjust to actual ground level
        if (!validateSegment(playerPos, turnPoint, primaryDirection, approachDistance)) {
            return null;
        }
        waypoints.add(turnPoint);

        // Waypoint 2: Side position (move sideways to clear hazard)
        BlockPos sidePoint = turnPoint.offset(sideDir, sideDistance);
        sidePoint = adjustToGroundLevel(sidePoint); // Adjust to actual ground level
        if (!validateSegment(turnPoint, sidePoint, sideDir, sideDistance)) {
            return null;
        }
        waypoints.add(sidePoint);

        // Waypoint 3: Forward past hazard (continue in primary direction)
        BlockPos pastHazard = sidePoint.offset(primaryDirection, forwardPastHazard);
        pastHazard = adjustToGroundLevel(pastHazard); // Adjust to actual ground level
        if (!validateSegment(sidePoint, pastHazard, primaryDirection, forwardPastHazard)) {
            return null;
        }
        waypoints.add(pastHazard);

        // NO WAYPOINT 4! After reaching last waypoint, normal scanning/mining resumes

        return waypoints;
    }

    private BlockPos adjustToGroundLevel(BlockPos pos) {
        // Find the actual ground level at this position (handle 1-2 block drops)
        // Check if current position is solid ground
        PathScanner.ScanResult currentScan = pathScanner.scanDirection(
            pos, primaryDirection, 0, 1, false
        );

        // If current position is air/unsafe, check 1-2 blocks down for ground
        if (currentScan.getHazardType() == PathScanner.HazardType.UNSAFE_GROUND ||
            !isGroundSolid(pos)) {

            // Check 1 block down
            BlockPos oneDown = pos.down();
            if (isGroundSolid(oneDown)) {
                System.out.println("  Adjusted waypoint down 1 block to ground level");
                return oneDown;
            }

            // Check 2 blocks down
            BlockPos twoDown = pos.down(2);
            if (isGroundSolid(twoDown)) {
                System.out.println("  Adjusted waypoint down 2 blocks to ground level");
                return twoDown;
            }
        }

        // Check if we need to go up (in case of elevation)
        BlockPos oneUp = pos.up();
        PathScanner.ScanResult upScan = pathScanner.scanDirection(
            oneUp, primaryDirection, 0, 1, false
        );
        if (upScan.isSafe() && !isGroundSolid(pos) && isGroundSolid(pos.down())) {
            // We might be inside a block, go up
            System.out.println("  Adjusted waypoint up 1 block to avoid collision");
            return oneUp;
        }

        return pos; // Original position is fine
    }

    private boolean isGroundSolid(BlockPos pos) {
        // Quick check if there's solid ground at this position
        // We scan with 0 distance to check the block itself
        PathScanner.ScanResult groundCheck = pathScanner.scanDirection(
            pos.up(), primaryDirection, 0, 1, false
        );

        // If scanning from above this position shows it's safe or has a non-fluid hazard,
        // it's likely solid ground
        return groundCheck.isSafe() ||
            (groundCheck.getHazardType() != PathScanner.HazardType.LAVA &&
                groundCheck.getHazardType() != PathScanner.HazardType.WATER &&
                groundCheck.getHazardType() != PathScanner.HazardType.UNSAFE_GROUND);
    }

    private boolean validateSegment(BlockPos start, BlockPos end, Direction moveDir, int distance) {
        // For very long segments (approaching distant hazards), validate in chunks
        if (distance > 10) {
            System.out.println("  Validating long segment (" + distance + " blocks) in chunks");
            BlockPos currentPos = start;

            for (int chunk = 0; chunk < distance; chunk += 5) {
                int chunkSize = Math.min(5, distance - chunk);

                // Validate this chunk
                for (int i = 1; i <= chunkSize; i++) {
                    BlockPos checkPos = currentPos.offset(moveDir, i);

                    // Use moveDir for scanning, but limit scan distance for long segments
                    int scanAhead = (distance > 10) ? 2 : 1; // Only scan 2 blocks ahead for long approaches
                    PathScanner.ScanResult scan = pathScanner.scanDirection(
                        checkPos, moveDir, scanAhead, 4, false
                    );

                    if (!scan.isSafe()) {
                        System.out.println("  Segment blocked at offset " + (chunk + i) + ": " + scan.getHazardType());
                        return false;
                    }

                    // Check for lava/water holes
                    if (!checkGroundSafety(checkPos)) {
                        System.out.println("  Unsafe ground at offset " + (chunk + i));
                        return false;
                    }
                }

                currentPos = currentPos.offset(moveDir, chunkSize);
            }

            System.out.println("  Long segment validated successfully");
            return true;
        }

        // Normal validation for shorter segments
        for (int i = 1; i <= distance; i++) {
            BlockPos checkPos = start.offset(moveDir, i);

            // IMPORTANT: When validating a segment, scan in the MOVEMENT direction, not primary
            // This prevents false positives from hazards that aren't actually in our path
            PathScanner.ScanResult scan = pathScanner.scanDirection(
                checkPos, moveDir, 1, 4, false  // Use moveDir, not primaryDirection!
            );

            if (!scan.isSafe()) {
                System.out.println("  Segment blocked at offset " + i + ": " + scan.getHazardType());
                return false;
            }

            // Additional check: If moving sideways, also verify we can continue forward later
            // But only check immediate blockage, not distant hazards
            if (moveDir != primaryDirection && i == distance) {
                // At the end of a sideways movement, check if we can move forward from here
                PathScanner.ScanResult forwardCheck = pathScanner.scanDirection(
                    checkPos, primaryDirection, 2, 4, false  // Just check 2 blocks ahead
                );

                if (!forwardCheck.isSafe() && forwardCheck.getHazardDistance() <= 1) {
                    System.out.println("  Can't continue forward from end of segment: " + forwardCheck.getHazardType());
                    return false;
                }
            }

            // Check for lava/water holes
            if (!checkGroundSafety(checkPos)) {
                System.out.println("  Unsafe ground at offset " + i);
                return false;
            }
        }

        return true;
    }

    private boolean checkGroundSafety(BlockPos pos) {
        // Quick check for lava/water in 1-2 block drops
        for (int depth = 1; depth <= 2; depth++) {
            BlockPos below = pos.down(depth);

            // Check all cardinal directions from below position for fluids
            for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                PathScanner.ScanResult scan = pathScanner.scanDirection(
                    below, dir, 0, 1, false
                );

                if (!scan.isSafe() && scan.getHazardDistance() == 0) {
                    if (scan.getHazardType() == PathScanner.HazardType.LAVA) {
                        System.out.println("    Lava detected " + depth + " blocks below!");
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private PathPlan createDetourPlan(List<BlockPos> waypoints, String reason) {
        Queue<BlockPos> waypointQueue = new LinkedList<>(waypoints);

        currentDetour = new LinkedList<>(waypointQueue);
        isDetouring = true;
        detourCount++;
        lastDecisionReason = "Detour #" + detourCount + ": " + reason;

        System.out.println("Created minimal detour with " + waypoints.size() + " waypoints");
        System.out.println("Will resume " + primaryDirection.getName() + " with full scanning after last waypoint");

        // After the last waypoint is reached, the main module should:
        // 1. Call completeDetour()
        // 2. Transition to SCANNING_PRIMARY state
        // 3. Resume normal hazard detection and mining logic

        return new PathPlan(true, waypointQueue, lastDecisionReason);
    }

    private HazardBounds scanHazardBoundaries(BlockPos playerPos, PathScanner.ScanResult initialHazard) {
        int hazardDistance = initialHazard.getHazardDistance();
        BlockPos hazardCenter = playerPos.offset(primaryDirection, hazardDistance);

        int leftWidth = 0;
        int rightWidth = 0;
        int forwardDepth = 0;

        Direction leftDir = primaryDirection.rotateYCounterclockwise();
        Direction rightDir = primaryDirection.rotateYClockwise();

        // Scan hazard width
        for (int i = 1; i <= 10; i++) {
            BlockPos checkPos = hazardCenter.offset(leftDir, i);
            PathScanner.ScanResult scan = pathScanner.scanDirection(
                checkPos, primaryDirection, 1, 4, false
            );
            if (scan.isSafe()) break;
            leftWidth = i;
        }

        for (int i = 1; i <= 10; i++) {
            BlockPos checkPos = hazardCenter.offset(rightDir, i);
            PathScanner.ScanResult scan = pathScanner.scanDirection(
                checkPos, primaryDirection, 1, 4, false
            );
            if (scan.isSafe()) break;
            rightWidth = i;
        }

        // Scan hazard depth
        for (int i = 0; i <= 20; i++) {
            BlockPos checkPos = hazardCenter.offset(primaryDirection, i);
            PathScanner.ScanResult scan = pathScanner.scanDirection(
                checkPos, primaryDirection, 1, 4, false
            );
            if (scan.isSafe()) break;
            forwardDepth = i;
        }

        int totalWidth = leftWidth + rightWidth + 1;
        int totalDepth = Math.max(forwardDepth, 1);

        return new HazardBounds(hazardDistance, totalWidth, totalDepth, hazardCenter);
    }

    private PathPlan handleCompletelyBlocked(BlockPos playerPos) {
        System.out.println("\n=== COMPLETELY BLOCKED ===");
        System.out.println("Cannot continue " + primaryDirection.getName());
        System.out.println("Will center first, then rotate to a new direction");

        // Only try perpendicular directions - NEVER go backwards
        Direction[] alternatives = {
            primaryDirection.rotateYClockwise(),
            primaryDirection.rotateYCounterclockwise()
        };

        for (Direction newDir : alternatives) {
            System.out.println("Checking " + newDir.getName() + "...");
            PathScanner.ScanResult scan = pathScanner.scanDirection(
                playerPos, newDir, 20, 4, false
            );

            if (scan.isSafe()) {
                System.out.println("Found safe direction: " + newDir.getName());
                System.out.println("Will center, then rotate to face " + newDir.getName());
                directionChangeCount++;
                lastDecisionReason = "Perpendicular direction change #" + directionChangeCount;
                return new PathPlan(newDir, lastDecisionReason);
            }
        }

        // NEVER try opposite direction - that's just the tunnel we came from!
        Direction opposite = primaryDirection.getOpposite();
        System.out.println("NOT checking " + opposite.getName() + " (that's where we came from)");
        System.out.println("ERROR: No valid forward paths - all perpendicular directions blocked!");
        System.out.println("Stopping to avoid going backwards into mined tunnel.");

        return new PathPlan(false, new LinkedList<>(), "Dead end - refusing to go backwards");
    }

    public void completeDetour() {
        System.out.println("DEBUG: Detour completed");
        System.out.println("DEBUG: Resuming primary direction: " + primaryDirection.getName());
        System.out.println("DEBUG: Returning to normal scanning/mining logic");
        isDetouring = false;
        currentDetour.clear();
        // Primary direction stays the same!
        // The main module should now transition to SCANNING_PRIMARY state
        // to resume normal hazard detection and mining
    }

    public String getDebugInfo() {
        return String.format(
            "Primary: %s (original: %s) | Detouring: %s | Detours: %d | Changes: %d | Reason: %s",
            primaryDirection.getName(),
            originalPrimaryDirection.getName(),
            isDetouring,
            detourCount,
            directionChangeCount,
            lastDecisionReason
        );
    }

    // Inner classes
    private static class HazardBounds {
        final int startDistance;
        final int width;
        final int depth;
        final BlockPos center;

        HazardBounds(int startDistance, int width, int depth, BlockPos center) {
            this.startDistance = startDistance;
            this.width = width;
            this.depth = depth;
            this.center = center;
        }
    }
}
