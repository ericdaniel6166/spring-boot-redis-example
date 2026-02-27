package com.eric6166.redis.dto;

/**
 * The categories of leaderboards available.
 */
public enum LeaderboardType {
    DAILY, WEEKLY, ALL_TIME;

    public String toSlug() {
        return this.name().toLowerCase();
    }
}
