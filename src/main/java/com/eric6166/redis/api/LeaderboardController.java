package com.eric6166.redis.api;

import com.eric6166.redis.dto.LeaderboardType;
import com.eric6166.redis.dto.UpdateProfileRequest;
import com.eric6166.redis.dto.UpdateScoreRequest;
import com.eric6166.redis.dto.UserProfileResponse;
import com.eric6166.redis.service.RankingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/leaderboard")
@Validated
@RequiredArgsConstructor
public class LeaderboardController {

    private final RankingService rankingService;

    @PostMapping("/score")
    public void updateScore(
            @Valid @RequestBody UpdateScoreRequest request) {
        rankingService.recordActivity(request, 1);
    }

    @GetMapping("/{type}")
    public List<UserProfileResponse> getBoard(
            @PathVariable LeaderboardType type,
            @RequestParam(defaultValue = "0") int page) {
        return rankingService.getLeaderboard(type, 1, page, 10);
    }

    @GetMapping("/{type}/search")
    public List<UserProfileResponse> search(
            @PathVariable LeaderboardType type,
            @RequestParam String userId,
            @Min(1) @Max(20) @RequestParam(defaultValue = "5") int radius) {
        return rankingService.getNeighborhood(type, 1, userId, radius);
    }

    @PutMapping("/profiles/{userId}")
    public void updateProfile(
            @PathVariable String userId,
            @Valid @RequestBody UpdateProfileRequest request) {
        rankingService.updateProfile(userId, request);
    }
}
