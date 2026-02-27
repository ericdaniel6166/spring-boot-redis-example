package com.eric6166.redis.api;

import com.eric6166.redis.dto.LeaderboardType;
import com.eric6166.redis.dto.UpdateProfileRequest;
import com.eric6166.redis.dto.UpdateScoreRequest;
import com.eric6166.redis.dto.UserProfileResponse;
import com.eric6166.redis.exception.GlobalExceptionHandler;
import com.eric6166.redis.service.RankingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({LeaderboardController.class, GlobalExceptionHandler.class})
class LeaderboardControllerTest {

    private static final String USERNAME = "user123";
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RankingService rankingService;

    @Autowired
    private ObjectMapper objectMapper;

    private UserProfileResponse sampleUser;

    @BeforeEach
    void setUp() {
        sampleUser = new UserProfileResponse(USERNAME, 1500.0, 1L, "http://avatar.url", "team-alpha");
    }

    @Test
    void updateScore_Success() throws Exception {
        UpdateScoreRequest request = new UpdateScoreRequest(USERNAME, 50.0, 3);

        mockMvc.perform(post("/leaderboard/score").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))).andExpect(status().isOk());

        verify(rankingService).recordActivity(any(UpdateScoreRequest.class), eq(1));
    }

    @Test
    void getBoard_Success() throws Exception {
        when(rankingService.getLeaderboard(eq(LeaderboardType.DAILY), eq(1), eq(0), eq(10))).thenReturn(List.of(sampleUser));

        String type = LeaderboardType.DAILY.name();
        mockMvc.perform(get("/leaderboard/" + type).param("page", "0")).andExpect(status().isOk()).andExpect(jsonPath("$[0].userId").value(USERNAME));
    }

    @Test
    void search_Success() throws Exception {
        when(rankingService.getNeighborhood(eq(LeaderboardType.DAILY), eq(1), eq(USERNAME), eq(5))).thenReturn(List.of(sampleUser));

        String type = LeaderboardType.DAILY.name();
        mockMvc.perform(get("/leaderboard/" + type + "/search").param("userId", USERNAME).param("radius", "5")).andExpect(status().isOk()).andExpect(jsonPath("$[0].userId").value(USERNAME));
    }

    @Test
    void updateProfile_Success() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest("new.png", "team-A");

        mockMvc.perform(put("/leaderboard/profiles/" + USERNAME).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))).andExpect(status().isOk());

        verify(rankingService).updateProfile(eq(USERNAME), any(UpdateProfileRequest.class));
    }

    @Test
    void search_InvalidRadius_ReturnsBadRequest() throws Exception {
        // Radius has @Max(20) constraint in the Controller
        String type = LeaderboardType.DAILY.name();
        mockMvc.perform(get("/leaderboard/" + type + "/search").param("userId", USERNAME).param("radius", "50")).andExpect(status().isBadRequest());
    }
}