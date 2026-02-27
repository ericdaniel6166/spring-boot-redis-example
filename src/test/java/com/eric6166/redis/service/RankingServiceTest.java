package com.eric6166.redis.service;

import com.eric6166.redis.dto.LeaderboardType;
import com.eric6166.redis.dto.UpdateProfileRequest;
import com.eric6166.redis.dto.UpdateScoreRequest;
import com.eric6166.redis.dto.UserProfileResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RankingServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @InjectMocks
    private RankingService rankingService;

    private static final String USER_ID = "user-123";
    private static final int VERSION = 1;
    private static final String KEY_ALL_TIME_VERSION = "{" + LeaderboardType.ALL_TIME.toSlug() + "}:v" + VERSION;

    @BeforeEach
    void setUp() {
        // Common mock setup for ZSet operations
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @DisplayName("GetLeaderboard should use L1 Cache for Page 0 and avoid Redis on subsequent calls")
    void getLeaderboard_L1CacheUsage() {
        // Mocking first call (Cache Miss)
        ZSetOperations.TypedTuple<String> tuple = ZSetOperations.TypedTuple.of(USER_ID, 100.0);
        Set<ZSetOperations.TypedTuple<String>> range = new LinkedHashSet<>(List.of(tuple));
        when(zSetOperations.reverseRangeWithScores(anyString(), eq(0L), eq(9L))).thenReturn(range);

        Map<String, String> profileData = Map.of("avatar", "pic.png", "team", "alpha");
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of(profileData));

        // Call 1: Misses L1, calls Redis
        rankingService.getLeaderboard(LeaderboardType.ALL_TIME, VERSION, 0, 10);

        // Call 2: Hits L1
        rankingService.getLeaderboard(LeaderboardType.ALL_TIME, VERSION, 0, 10);

        // Verify Redis was only called once for the range
        verify(zSetOperations, times(1)).reverseRangeWithScores(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("GetLeaderboard should bypass L1 for pages > 0")
    void getLeaderboard_BypassL1ForDeepPages() {
        when(zSetOperations.reverseRangeWithScores(anyString(), eq(10L), eq(19L))).thenReturn(Collections.emptySet());

        rankingService.getLeaderboard(LeaderboardType.ALL_TIME, VERSION, 1, 10);

        // Should not interact with Caffeine cache for page 1
        verify(zSetOperations).reverseRangeWithScores(anyString(), eq(10L), eq(19L));
    }

    @Test
    @DisplayName("SyncToDb should perform batch updates when data exists")
    void syncToDb_WithData() {
        ZSetOperations.TypedTuple<String> tuple = ZSetOperations.TypedTuple.of(USER_ID, 500.0);
        when(zSetOperations.reverseRangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(Set.of(tuple));

        rankingService.syncToDb(LeaderboardType.ALL_TIME, VERSION);

        verify(jdbcTemplate).batchUpdate(anyString(), any(Set.class), eq(1000), any());
    }

    @Test
    @DisplayName("SyncToDb should return early when Redis set is empty")
    void syncToDb_EmptyRedis() {
        when(zSetOperations.reverseRangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(Collections.emptySet());

        rankingService.syncToDb(LeaderboardType.ALL_TIME, VERSION);

        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyCollection(), anyInt(), any());
    }

    @Test
    @DisplayName("RehydrateAllProfiles should handle multiple batches")
    void rehydrateAllProfiles_Batching() {
    // 1. Mock the ResultSet to simulate 1500 records
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);

            // Simulate 1500 rows
            when(rs.next()).thenReturn(true).thenAnswer(new Answer<Boolean>() {
                private int count = 0;
                public Boolean answer(InvocationOnMock inv) {
                    return ++count < 1500;
                }
            });
            when(rs.getString("user_id")).thenReturn("user-id");

            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(eq("SELECT user_id FROM user_profiles"), any(RowCallbackHandler.class));

    // 2. Mock the second query (inside syncProfilesToRedis) to return an empty list
    // so the loop continues without crashing on nulls
    lenient().when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
            .thenReturn(Collections.emptyList());

    // 3. Execute
        rankingService.rehydrateAllProfiles();

    // 4. Verify:
    // Batch 1: 1000 IDs
    // Batch 2: 500 IDs
    // Total: 2 calls to queryForList (which is inside syncProfilesToRedis)
    verify(jdbcTemplate, times(2)).queryForList(contains("WHERE user_id IN"), any(Object[].class));
    }

    @Test
    @DisplayName("recordActivity should handle null ranks and skip notifications")
    void recordActivity_NoDisplacement() {
        UpdateScoreRequest request = new UpdateScoreRequest(USER_ID, 10.0, 10);

        // User stays at rank 15 (no displacement)
        when(zSetOperations.reverseRank(anyString(), eq(USER_ID))).thenReturn(15L, 15L);

        rankingService.recordActivity(request, VERSION);

        // Should NOT send notification
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("fetchHydratedRange should handle missing profile data gracefully")
    void fetchHydratedRange_MissingProfiles() {
        ZSetOperations.TypedTuple<String> tuple = ZSetOperations.TypedTuple.of(USER_ID, 100.0);
        when(zSetOperations.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(Set.of(tuple));

        // Return a null or empty map from the pipeline
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(Collections.singletonList(null));

        List<UserProfileResponse> result = rankingService.getLeaderboard(LeaderboardType.ALL_TIME, VERSION, 0, 10);

        assertEquals(1, result.size());
        assertEquals(UserProfileResponse.DEFAULT_AVATAR_URL, result.get(0).avatarUrl()); // Check fallback
        assertEquals(UserProfileResponse.DEFAULT_TEAM_ID, result.get(0).teamId()); // Check fallback
    }

    @Test
    @DisplayName("Should resolve Redis key with Hash Tags for clustering")
    void resolveKey() {
        String key = rankingService.resolveKey(LeaderboardType.ALL_TIME, VERSION);
        assertEquals(KEY_ALL_TIME_VERSION, key);
    }

    @Test
    @DisplayName("RecordActivity should increment score and trigger notification on displacement")
    void recordActivity() {
        UpdateScoreRequest request = new UpdateScoreRequest(USER_ID, 10.0, 10);

        // Mock old rank (out of top 10) and new rank (into top 10)
        when(zSetOperations.reverseRank(anyString(), eq(USER_ID))).thenReturn(15L, 5L);

        // Mock the displacement victim check
        Set<String> victims = Collections.singleton("displaced-user");
        when(zSetOperations.reverseRange(anyString(), eq(10L), eq(10L))).thenReturn(victims);

        rankingService.recordActivity(request, VERSION);

        verify(zSetOperations, atLeastOnce()).incrementScore(anyString(), eq(USER_ID), eq(10.0));
        verify(redisTemplate, atLeastOnce()).convertAndSend(eq(RankingService.NOTIFY_CHANNEL), anyString());
    }

    @Test
    @DisplayName("GetLeaderboard should return hydrated list from Redis")
    void getLeaderboard() {
        // Setup mock data for ZSet range
        ZSetOperations.TypedTuple<String> tuple = ZSetOperations.TypedTuple.of(USER_ID, 100.0);
        Set<ZSetOperations.TypedTuple<String>> range = new LinkedHashSet<>(List.of(tuple));

        when(zSetOperations.reverseRangeWithScores(anyString(), eq(0L), eq(9L))).thenReturn(range);

        // Mock Pipeline execution for profile hydration
        Map<String, String> profileData = Map.of("avatar", "pic.png", "team", "alpha");
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of(profileData));

        List<UserProfileResponse> result = rankingService.getLeaderboard(LeaderboardType.ALL_TIME, VERSION, 0, 10);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(USER_ID, result.get(0).userId());
        assertEquals(100.0, result.get(0).score());
    }

    @Test
    @DisplayName("UpdateProfile should update DB and sync to Redis")
    void updateProfile() {
        // This test ensures the flow from DB to Redis happens
        when(jdbcTemplate.queryForList(anyString(), Optional.ofNullable(any()))).thenReturn(List.of(
                Map.of("user_id", USER_ID, "avatar_url", "new.png", "team_id", "beta")
        ));

        rankingService.updateProfile(USER_ID, new UpdateProfileRequest("new.png", "beta"));

        verify(jdbcTemplate).update(anyString(), eq(USER_ID), eq("new.png"), eq("beta"));
        verify(hashOperations, atLeastOnce()).putAll(anyString(), anyMap());
    }

    @Test
    @DisplayName("RehydrateFromDb should use temp key and rename for atomicity")
    void rehydrateFromDb() {
        String key = KEY_ALL_TIME_VERSION;
        String tempKey = key + RankingService.TEMP_SUFFIX;

        rankingService.rehydrateFromDb(LeaderboardType.ALL_TIME, VERSION);

        verify(redisTemplate).delete(tempKey);
        verify(jdbcTemplate).query(contains("SELECT"), any(RowCallbackHandler.class), eq(LeaderboardType.ALL_TIME.name()), eq(VERSION));
        verify(redisTemplate).rename(tempKey, key);
    }

    @Test
    @DisplayName("GetNeighborhood should return empty list if user has no rank")
    void getNeighborhood_UserNotFound() {
        when(zSetOperations.reverseRank(anyString(), eq(USER_ID))).thenReturn(null);

        List<UserProfileResponse> result = rankingService.getNeighborhood(LeaderboardType.ALL_TIME, VERSION, USER_ID, 3);

        assertTrue(result.isEmpty());
        verify(zSetOperations, never()).reverseRangeWithScores(anyString(), anyLong(), anyLong());
    }
}