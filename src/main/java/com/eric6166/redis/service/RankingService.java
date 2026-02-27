package com.eric6166.redis.service;

import com.eric6166.redis.dto.*;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RankingService {

    public static final String NOTIFY_CHANNEL = "lb_notifications";
    public static final String TEMP_SUFFIX = "_temp";
    public static final String PROFILE_HASH_KEY_AVATAR = "avatar";
    public static final String PROFILE_HASH_KEY_TEAM = "team";
    public static final int BATCH_SIZE = 1000;
    private static final String PROFILE_HASH_KEY = "user_profiles";
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    // L1 Cache: Absorbs spike, Prevents "Cache Stampede" by caching Top 10 for 10 seconds (Page 0)
    private final Cache<String, List<UserProfileResponse>> l1Cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(10, TimeUnit.SECONDS)
            .build();

    /**
     * Resolves the Redis key using {} Hash Tags for Cluster sharding.
     */
    public String resolveKey(LeaderboardType type, int version) {
        return "{" + type.toSlug() + "}:v" + version;
    }

    /**
     * ATOMIC UPDATE: Increments score and broadcasts "Dethrone" events if Top N changes.
     */
    public void recordActivity(@Valid UpdateScoreRequest request, int version) {
        for (LeaderboardType type : LeaderboardType.values()) {
            String key = resolveKey(type, version);

            // Step 1: Check rank BEFORE update
            Long oldRank = redisTemplate.opsForZSet().reverseRank(key, request.userId());

            // Step 2: Update Redis
            redisTemplate.opsForZSet().incrementScore(key, request.userId(), request.points());

            // Step 3: Check rank AFTER update and notify if someone was displaced
            Long newRank = redisTemplate.opsForZSet().reverseRank(key, request.userId());
            if (newRank != null && newRank < request.threshold() && (oldRank == null || oldRank >= request.threshold())) {
                notifyDisplacedUser(key, type, request.threshold());
            }
        }
    }

    /**
     * PAGINATED READ: Uses L1 Caffeine -> L2 Redis with pipelined hydration.
     */
    public List<UserProfileResponse> getLeaderboard(LeaderboardType type, int version, int page, int size) {
        String key = resolveKey(type, version);

        // Only use L1 for Page 0 to handle 99% of user traffic
        if (page == 0 && size <= 10) {
            return l1Cache.get(key, k -> fetchHydratedRange(key, 0, size - 1));
        }

        long start = (long) page * size;
        long end = start + size - 1;
        return fetchHydratedRange(key, start, end);
    }

    /**
     * PROFILE UPDATE: Centralized logic for DB upsert and Redis cache invalidation.
     */
    @Transactional
    public void updateProfile(String userId, @Valid UpdateProfileRequest request) {
        String sql = """
                INSERT INTO user_profiles (user_id, avatar_url, team_id)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET 
                    avatar_url = EXCLUDED.avatar_url, 
                    team_id = EXCLUDED.team_id, 
                    updated_at = CURRENT_TIMESTAMP
                """;

        jdbcTemplate.update(sql, userId, request.avatarUrl(), request.teamId());

        // Sync to L2 (Redis) and clear L1 to ensure immediate visibility
        syncProfilesToRedis(List.of(userId));
        l1Cache.invalidateAll();
    }

    /**
     * NEIGHBORHOOD SEARCH: Find rank and show context (above/below).
     */
    public List<UserProfileResponse> getNeighborhood(LeaderboardType type, int version, String userId, int radius) {
        String key = resolveKey(type, version);
        Long rank = redisTemplate.opsForZSet().reverseRank(key, userId);

        if (rank == null) return Collections.emptyList();

        return fetchHydratedRange(key, Math.max(0, rank - radius), rank + radius);
    }

    /**
     * PIPELINING: Fetches all profile hashes and ZSet scores in one network trip.
     * This avoids the N+1 problem where we would otherwise call Redis once for every user in the list.
     */
    private List<UserProfileResponse> fetchHydratedRange(String key, long start, long end) {
        Set<ZSetOperations.TypedTuple<String>> range = redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
        if (range == null || range.isEmpty()) return Collections.emptyList();

        List<String> ids = range.stream().map(ZSetOperations.TypedTuple::getValue).toList();

        List<Object> profiles = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String id : ids) {
                connection.hGetAll((PROFILE_HASH_KEY + ":" + id).getBytes());
            }
            return null;
        });

        List<UserProfileResponse> result = new ArrayList<>();
        int i = 0;
        long currentRank = start + 1;
        for (ZSetOperations.TypedTuple<String> tuple : range) {
            @SuppressWarnings("unchecked")
            Map<String, String> profile = (Map<String, String>) profiles.get(i++);

            String avatar = (profile != null && profile.containsKey(PROFILE_HASH_KEY_AVATAR)) ? profile.get(PROFILE_HASH_KEY_AVATAR) : UserProfileResponse.DEFAULT_AVATAR_URL;
            String team = (profile != null && profile.containsKey(PROFILE_HASH_KEY_TEAM)) ? profile.get(PROFILE_HASH_KEY_TEAM) : UserProfileResponse.DEFAULT_TEAM_ID;

            result.add(new UserProfileResponse(tuple.getValue(), tuple.getScore(), currentRank++, avatar, team));
        }
        return result;
    }

    /**
     * PERSISTENCE (Upsert): Batch updates DB using virtual-thread safe JDBC.
     */
    @Transactional
    public void syncToDb(LeaderboardType type, int version) {
        String key = resolveKey(type, version);
        Set<ZSetOperations.TypedTuple<String>> scores = redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, -1);

        if (scores == null || scores.isEmpty()) return;

        String sql = """
                INSERT INTO user_scores (user_id, board_type, total_score, version)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, board_type, version) 
                DO UPDATE SET total_score = EXCLUDED.total_score, last_updated = CURRENT_TIMESTAMP
                """;

        jdbcTemplate.batchUpdate(sql, scores, BATCH_SIZE, (ps, tuple) -> {
            ps.setString(1, tuple.getValue());
            ps.setString(2, type.name());
            ps.setDouble(3, tuple.getScore());
            ps.setInt(4, version);
        });
    }

    /**
     * REHYDRATION (DB -> Redis): Uses a temp key to prevent "Blank Leaderboard" syndrome.
     * We load data into a hidden key first, then use Redis RENAME to swap it instantly.
     */
    public void rehydrateFromDb(LeaderboardType type, int version) {
        String key = resolveKey(type, version);
        String tempKey = key + TEMP_SUFFIX;
        redisTemplate.delete(tempKey);

        jdbcTemplate.query("SELECT user_id, total_score FROM user_scores WHERE board_type = ? AND version = ?",
                (rs) -> {
                    redisTemplate.opsForZSet().add(tempKey, rs.getString("user_id"), rs.getDouble("total_score"));
                }, type.name(), version);

        redisTemplate.rename(tempKey, key);
    }

    /**
     * Syncs specific user profiles from DB to Redis.
     * Called when a profile is updated or during full rehydration.
     */
    public void syncProfilesToRedis(List<String> userIds) {
        if (userIds.isEmpty()) return;

        String sql = "SELECT user_id, avatar_url, team_id FROM user_profiles WHERE user_id IN (" +
                String.join(",", Collections.nCopies(userIds.size(), "?")) + ")";

        List<Map<String, Object>> dbProfiles = jdbcTemplate.queryForList(sql, userIds.toArray());

        for (Map<String, Object> profile : dbProfiles) {
            String userId = (String) profile.get("user_id");
            String redisKey = PROFILE_HASH_KEY + ":" + userId;

            Map<String, String> hashData = new HashMap<>();
            hashData.put(PROFILE_HASH_KEY_AVATAR, (String) profile.get("avatar_url"));
            hashData.put(PROFILE_HASH_KEY_TEAM, (String) profile.get("team_id"));

            redisTemplate.opsForHash().putAll(redisKey, hashData);
            redisTemplate.expire(redisKey, Duration.ofDays(7));
        }
    }

    /**
     * Full Profile Rehydration: Streams all profiles from DB to Redis.
     * Useful for disaster recovery or cache clearing.
     */
    public void rehydrateAllProfiles() {
        jdbcTemplate.query("SELECT user_id FROM user_profiles", (rs) -> {
            // Process in batches of 1000 to avoid memory pressure
            List<String> batch = new ArrayList<>();
            do {
                batch.add(rs.getString("user_id"));
                if (batch.size() >= BATCH_SIZE) {
                    syncProfilesToRedis(batch);
                    batch.clear();
                }
            } while (rs.next());
            syncProfilesToRedis(batch);
        });
    }

    private void notifyDisplacedUser(String key, LeaderboardType type, int threshold) {
        Set<String> victim = redisTemplate.opsForZSet().reverseRange(key, threshold, threshold);
        if (victim != null && !victim.isEmpty()) {
            String victimId = victim.iterator().next();
            DethroneEvent message = new DethroneEvent(victimId, type, (long) threshold + 1, "Dethroned!");
            log.debug(message.toString());
            redisTemplate.convertAndSend(NOTIFY_CHANNEL, message.toString());
        }
    }

}