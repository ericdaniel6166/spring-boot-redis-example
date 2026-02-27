package com.eric6166.redis.api;

import com.eric6166.redis.dto.*;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({LeaderboardController.class, GlobalExceptionHandler.class})
class LeaderboardControllerTest {

    public static final String USER = "user123";
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RankingService rankingService;

    @Autowired
    private ObjectMapper objectMapper;

    private UserProfileResponse sampleUser;

    @BeforeEach
    void setUp() {
        sampleUser = new UserProfileResponse(
                USER,
                1500.0,
                1L,
                "http://avatar.url",
                "team-alpha"
        );
    }

    @Test
    void updateScore_Success() throws Exception {
        UpdateScoreRequest request = new UpdateScoreRequest(USER, 50.0, 3);

        mockMvc.perform(post("/leaderboard/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(rankingService).recordActivity(any(UpdateScoreRequest.class), eq(1));
    }

    @Test
    void getBoard_Success() throws Exception {
        when(rankingService.getLeaderboard(eq(LeaderboardType.DAILY), eq(1), eq(0), eq(10)))
                .thenReturn(List.of(sampleUser));

        mockMvc.perform(get("/leaderboard/DAILY")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(USER));
    }

    @Test
    void search_Success() throws Exception {
        when(rankingService.getNeighborhood(eq(LeaderboardType.DAILY), eq(1), eq(USER), eq(5)))
                .thenReturn(List.of(sampleUser));

        mockMvc.perform(get("/leaderboard/DAILY/search")
                        .param("userId", USER)
                        .param("radius", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(USER));
    }

    @Test
    void updateProfile_Success() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest("new.png", "team-A");

        mockMvc.perform(put("/leaderboard/profiles/user123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(rankingService).updateProfile(eq(USER), any(UpdateProfileRequest.class));
    }

    @Test
    void search_InvalidRadius_ReturnsBadRequest() throws Exception {
        // Radius has @Max(20) constraint in the Controller
        String type = LeaderboardType.DAILY.name();
        mockMvc.perform(get("/leaderboard/" + type + "/search")
                        .param("userId", USER)
                        .param("radius", "50"))
                .andExpect(status().isBadRequest());
    }
}