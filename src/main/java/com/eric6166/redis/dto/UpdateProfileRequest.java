package com.eric6166.redis.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(
        @NotBlank String avatarUrl,
        @NotBlank String teamId
) {
}
