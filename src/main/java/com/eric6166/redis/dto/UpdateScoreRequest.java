package com.eric6166.redis.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateScoreRequest(
        @NotBlank String userId,
        @NotNull Double points,
        @Min(1) @Max(100) Integer threshold
) {
    public UpdateScoreRequest {
        if (threshold == null) {
            threshold = 3;
        }
    }
}
