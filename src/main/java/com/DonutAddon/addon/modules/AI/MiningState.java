package com.DonutAddon.addon.modules.AI;

public enum MiningState {
    IDLE,
    CENTERING,
    CENTERING_AT_WAYPOINT,
    SCANNING_PRIMARY,
    MINING_PRIMARY,
    HAZARD_DETECTED,
    CALCULATING_DETOUR,
    FOLLOWING_DETOUR,
    ROTATING,
    CHANGING_DIRECTION,
    STOPPED,

    // RTP Recovery States
    RTP_INITIATED,           // RTP command sent, waiting for teleport
    RTP_WAITING,            // Waiting for teleport to complete
    RTP_SCANNING_GROUND,    // Scanning ground below after RTP
    MINING_DOWN,            // Mining straight down to Y=-25
    RTP_COOLDOWN            // Waiting for RTP cooldown before retry
}
