package com.DonutAddon.addon.modules.AI;

public enum MiningState {
    IDLE,
    CENTERING,
    SCANNING,
    MINING,
    HAZARD_DETECTED,
    ROTATING,
    RETRACING,      // New state for going back in side tunnel
    BACKTRACKING,
    STOPPED
}
