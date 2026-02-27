package com.eric6166.redis.dto;

import java.io.Serializable;

/**
 * Message sent over Redis Pub/Sub for real-time "Dethrone" alerts.
 */
public record DethroneEvent(
        String userId,
        LeaderboardType type,
        Long newRank,
        String message
) implements Serializable {
}
