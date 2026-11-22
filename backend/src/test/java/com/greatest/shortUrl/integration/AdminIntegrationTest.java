package com.greatest.shortUrl.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greatest.shortUrl.entity.User;
import com.greatest.shortUrl.model.Role;
import com.greatest.shortUrl.repository.UserRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.greatest.shortUrl.entity.ShortUrl;
import com.greatest.shortUrl.repository.ShortUrlRepo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class AdminIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private ShortUrlRepo shortUrlRepo;

    @AfterEach
    void clearRedis() {
        redissonClient.getKeys().flushdb();
    }

    @Test
    void anonymousUser_cannotAccessAdminStats() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats")).andExpect(status().isUnauthorized());
    }

    @Test
    void normalUser_cannotAccessAdminStats() throws Exception {
        String email = "normal-admin-test-" + UUID.randomUUID() + "@example.com";
        String token = signupAndGetAccessToken(email);
        mockMvc.perform(
                        get("/api/v1/admin/stats")
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void adminUser_canAccessAdminStats() throws Exception {
        String email = "admin-stats-" + UUID.randomUUID() + "@example.com";
        String token = createAdminAndGetAccessToken(email);
        String response = mockMvc.perform(
                        get("/api/v1/admin/stats")
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode stats = objectMapper.readTree(response);
        assertThat(stats.get("totalUsers").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.get("totalUrls").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(stats.get("publicUrls").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(stats.get("privateUrls").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(stats.get("totalClicks").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void adminUser_canAccessAdminUsers() throws Exception {
        String email = "admin-users-" + UUID.randomUUID() + "@example.com";
        String token = createAdminAndGetAccessToken(email);
        String response = mockMvc.perform(
                        get("/api/v1/admin/users")
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode users = objectMapper.readTree(response);
        assertThat(users.isArray()).isTrue();
        boolean adminWasReturned = false;
        for (JsonNode user : users) {
            if (email.equals(user.get("email").asText())) {
                adminWasReturned = true;
                assertThat(user.get("role").asText()).isEqualTo("ROLE_ADMIN");
                break;
            }
        }
        assertThat(adminWasReturned).isTrue();
    }

    @Test
    void adminUser_canAccessAdminUrls() throws Exception {
        String email = "admin-urls-" + UUID.randomUUID() + "@example.com";
        String token = createAdminAndGetAccessToken(email);
        mockMvc.perform(
                        post("/api/v1/shorten/")
                                .header("Authorization", "Bearer " + token)
                                .header("X-Forwarded-For", "10.30.0.1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "originalUrl": "https://example.com/admin-test-url",
                                          "isPrivate": false,
                                          "expirationInDays": 7
                                        }
                                        """)
                )
                .andExpect(status().isOk());

        String response = mockMvc.perform(
                        get("/api/v1/admin/urls")
                                .header("Authorization", "Bearer " + token)
                                .param("page", "1")
                                .param("size", "10")
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode urls = objectMapper.readTree(response);

        assertThat(urls.get("data").isArray()).isTrue();
        assertThat(urls.get("totalElements").asLong())
                .isGreaterThanOrEqualTo(1);

        boolean adminUrlWasReturned = false;

        for (JsonNode url : urls.get("data")) {
            if ("https://example.com/admin-test-url"
                    .equals(url.get("originalUrl").asText())) {
                adminUrlWasReturned = true;
                break;
            }
        }

        assertThat(adminUrlWasReturned).isTrue();
    }

    private String createAdminAndGetAccessToken(String email) throws Exception {
        User admin = User.builder()
                .email(email)
                .name("Integration Admin")
                .password(passwordEncoder.encode("AdminPassword123!"))
                .role(Role.ROLE_ADMIN)
                .build();

        userRepo.saveAndFlush(admin);

        return loginAndGetAccessToken(
                email,
                "AdminPassword123!"
        );
    }

    private String signupAndGetAccessToken(String email) throws Exception {

        String response = mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .header("X-Forwarded-For", "10.30.0.2")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "email": "%s",
                                          "password": "Secret123!",
                                          "name": "Normal User"
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

    private String loginAndGetAccessToken(String email, String password) throws Exception {

        String response = mockMvc.perform(
                        post("/api/v1/auth/login")
                                .header("X-Forwarded-For", "10.30.0.3")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "email": "%s",
                                          "password": "%s"
                                        }
                                        """.formatted(email, password))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response)
                .get("accessToken")
                .asText();
    }


    @Test
    void adminUser_canDeleteUrls() throws Exception {
        String adminEmail = "admin-delete-" + UUID.randomUUID() + "@example.com";
        String adminToken=createAdminAndGetAccessToken(adminEmail);

        String createResponse = mockMvc.perform(
                        post("/api/v1/shorten/")
                                .header("Authorization", "Bearer " + adminToken)
                                .header("X-Forwarded-For", "10.40.0.1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {
                                      "originalUrl": "https://example.com/admin-delete-test",
                                      "isPrivate": false,
                                      "expirationInDays": 7
                                    }
                                    """)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode createdUrl = objectMapper.readTree(createResponse);
        String id=createdUrl.get("id").asText();

        mockMvc.perform(
                        delete("/api/v1/admin/delete-urls")
                                .header("Authorization", "Bearer " + adminToken)
                                .param("ids", id)
                )
                .andExpect(status().isOk())
                .andExpect(content().string("URLs deleted successfully"));

        assertThat(shortUrlRepo.findById(id)).isEmpty();

    }

}
