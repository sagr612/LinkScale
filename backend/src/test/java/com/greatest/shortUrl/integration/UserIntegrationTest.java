package com.greatest.shortUrl.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class UserIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedissonClient redissonClient;

    @AfterEach
    void clearRedis() {
        redissonClient.getKeys().flushdb();
    }

    @Test
    void currentUser_returnsAuthenticatedProfile() throws Exception {
        String email = "profile-" + UUID.randomUUID() + "@example.com";
        String token = signupAndGetAccessToken(email);
        String response = mockMvc.perform(
                        get("/api/v1/users/me")
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode profile = objectMapper.readTree(response);
        assertThat(profile.get("email").asText()).isEqualTo(email);
        assertThat(profile.get("name").asText()).isEqualTo("Integration User");
        assertThat(profile.get("id").asText()).isNotBlank();
        assertThat(profile.get("role").asText()).isEqualTo("ROLE_USER");
        assertThat(profile.get("createdAt").asText()).isNotBlank();
    }

    @Test
    void currentUser_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void currentUserStats_returnsCorrectCounts() throws Exception {
        String email = "stats-" + UUID.randomUUID() + "@example.com";
        String token = signupAndGetAccessToken(email);

        createShortUrl(token, "https://example.com/public-url", false);
        createShortUrl(token, "https://example.com/private-url", true);

        String response = mockMvc.perform(
                        get("/api/v1/users/me/stats")
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode stats = objectMapper.readTree(response);
        assertThat(stats.get("totalUrls").asLong()).isEqualTo(2);
        assertThat(stats.get("publicUrls").asLong()).isEqualTo(1);
        assertThat(stats.get("privateUrls").asLong()).isEqualTo(1);
        assertThat(stats.get("totalClicks").asLong()).isEqualTo(0);
    }

    @Test
    void currentUserStats_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/stats"))
                .andExpect(status().isUnauthorized());
    }

    private String signupAndGetAccessToken(String email) throws Exception {
        String response = mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .header(
                                        "X-Forwarded-For",
                                        "10.50.0.1"
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "email": "%s",
                                          "password": "Secret123!",
                                          "name": "Integration User"
                                        }
                                        """.formatted(email))
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response)
                .get("accessToken")
                .asText();
    }

    private String createShortUrl(String token, String originalUrl, boolean isPrivate) throws Exception {
        String response = mockMvc.perform(
                        post("/api/v1/shorten/")
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                                .header(
                                        "X-Forwarded-For",
                                        "10.50.0.2"
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "originalUrl": "%s",
                                          "isPrivate": %s,
                                          "expirationInDays": 7
                                        }
                                        """.formatted(
                                        originalUrl,
                                        isPrivate
                                ))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response)
                .get("shortKey")
                .asText();
    }
}
