package com.eric6166.redis.dto;

import java.io.Serializable;

/**
 * Immutable DTO for API responses. Rank is 1-based.
 */
public record UserProfileResponse(
        String userId,
        Double score,
        Long rank,
        String avatarUrl,
        String teamId
) implements Serializable {
    public static final String DEFAULT_AVATAR_URL = "default.png";
    public static final String DEFAULT_TEAM_ID = "none";
}

