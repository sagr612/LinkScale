package com.greatest.shortUrl.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greatest.shortUrl.entity.PasswordResetToken;
import com.greatest.shortUrl.repository.PasswordResetTokenRepository;
import com.greatest.shortUrl.services.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration")
public class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private EmailService emailService;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @AfterEach
    void clearRedis() {
        redissonClient.getKeys().flushdb();
    }

    @Test
    void signup_validUser_persistsUserAndReturnsTokens() throws Exception {
        String response = mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "email": "integration@example.com",
                                          "password": "Secret123!",
                                          "name": "Integration User"
                                        }
                                        """)
                ).andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);

        assertThat(json.get("accessToken").asText()).isNotBlank();
        assertThat(json.get("token").asText()).isNotBlank();
    }

    @Test
    void signup_duplicateEmail_returnsConflict() throws Exception {
        String request = """
                {
                  "email": "duplicate@example.com",
                  "password": "Secret123!",
                  "name": "Duplicate User"
                }
                """;
        mockMvc.perform(
                post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
        ).andExpect(status().isCreated());

        mockMvc.perform(
                post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
        ).andExpect(status().isConflict());
    }

    @Test
    void login_invalidPassword_returnsUnauthorized() throws Exception {
        String signupRequest = """
                {
                  "email": "login@example.com",
                  "password": "Correct123!",
                  "name": "Login User"
                }
                """;
        mockMvc.perform(
                post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupRequest)
        ).andExpect(status().isCreated());

        mockMvc.perform(
                post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "login@example.com",
                                  "password": "Wrong123!"
                                }
                                """)
        ).andExpect(status().isUnauthorized());
    }


    @Test
    void refreshToken_validToken_returnsNewAccessToken() throws Exception {
        String email = "refresh-" + UUID.randomUUID() + "@example.com";
        String signupResponse = mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "email": "%s",
                                          "password": "Secret123!",
                                          "name": "Refresh User"
                                        }
                                        """.formatted(email))
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(signupResponse);
        String refreshToken = json.get("token").asText();

        String refreshResponse = mockMvc.perform(
                        post("/api/v1/auth/refreshToken")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "token": "%s"
                                        }
                                        """.formatted(refreshToken))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode refreshTokenJson = objectMapper.readTree(refreshResponse);

        assertThat(refreshTokenJson.get("accessToken").asText()).isNotBlank();
        assertThat(refreshTokenJson.get("token").asText()).isEqualTo(refreshToken);

    }

    @Test
    void refreshToken_invalidToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/refreshToken")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "token": "invalid-refresh-token"
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized());
    }


    @Test
    void refreshToken_emptyToken_returnsForbidden() throws Exception {
        mockMvc.perform(
                post("/api/v1/auth/refreshToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                "token": ""
                                }
                                """)
        ).andExpect(status().isForbidden());
    }

    @Test
    void forgotPassword_existingUser_createsResetTokenAndSendsEmail() throws Exception {
        String email = "forgot-" + UUID.randomUUID() + "@example.com";

        mockMvc.perform(
                post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "Secret123!",
                                  "name": "Forgot User"
                                }
                                """.formatted(email))
        ).andExpect(status().isCreated());

        mockMvc.perform(
                post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s"
                                }
                                """.formatted(email))
        ).andExpect(status().isOk());

        assertThat(passwordResetTokenRepository.findByUserEmail(email)).isPresent();
        verify(emailService).sendEmail(
                eq(email),
                anyString(),
                contains("<a href")
        );
    }

    @Test
    void forgotPassword_unknownUser_returnsSameResponse() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/forgot-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "email": "unknown-%s@example.com"
                                        }
                                        """.formatted(UUID.randomUUID()))
                ).andExpect(status().isOk())
                .andExpect(content().string("Password reset email sent"));
        verifyNoInteractions(emailService);
    }

    @Test
    void resetPassword_validToken_allowsLoginWithNewPassword() throws Exception {
        String email = "reset-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(
                post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "10.0.10.1")
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "OldPassword123!",
                                  "name": "Reset User"
                                }
                                """.formatted(email))
        ).andExpect(status().isCreated());
        mockMvc.perform(
                post("/api/v1/auth/forgot-password")
                        .header("X-Forwarded-For", "10.0.10.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s"
                                }
                                """.formatted(email))
        ).andExpect(status().isOk());

        PasswordResetToken resetToken = passwordResetTokenRepository.findByUserEmail(email).orElseThrow();
        mockMvc.perform(
                post("/api/v1/auth/reset-password")
                        .header("X-Forwarded-For", "10.0.10.3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "%s",
                                  "newPassword": "NewPassword123!"
                                }
                                """.formatted(resetToken.getToken()
                        ))
        ).andExpect(status().isOk());


        mockMvc.perform(
                post("/api/v1/auth/login")
                        .header("X-Forwarded-For", "10.0.10.4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "NewPassword123!"
                                }
                                """.formatted(email))
        ).andExpect(status().isOk());
    }


    @Test
    void resetPassword_usedToken_returnsUnauthorized() throws Exception {
        String email = "reuse-reset-" + UUID.randomUUID() + "@example.com";

        mockMvc.perform(
                post("/api/v1/auth/signup")
                        .header("X-Forwarded-For", "10.0.10.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "OldPassword123!",
                                  "name": "Reuse Reset User"
                                }
                                """.formatted(email))
        ).andExpect(status().isCreated());

        mockMvc.perform(
                post("/api/v1/auth/forgot-password")
                        .header("X-Forwarded-For", "10.0.10.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                  {
                                  "email": "%s"
                                }
                                """.formatted(email))
        ).andExpect(status().isOk());

        PasswordResetToken resetToken = passwordResetTokenRepository.findByUserEmail(email).orElseThrow();

        String token = resetToken.getToken();

        mockMvc.perform(
                post("/api/v1/auth/reset-password")
                        .header("X-Forwarded-For", "10.0.10.3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "%s",
                                  "newPassword": "NewPassword123!"
                                }
                                """.formatted(token))
        ).andExpect(status().isOk());

        mockMvc.perform(
                post("/api/v1/auth/reset-password")
                        .header("X-Forwarded-For", "10.0.10.4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "%s",
                                  "newPassword": "anotherPassword123!"
                                }
                                """.formatted(token))
        ).andExpect(status().isUnauthorized());


    }

    @Test
    void authenticationRequests_overLimit_returnTooManyRequests() throws Exception {

        String request = """
            {
              "email": "missing@example.com",
              "password": "WrongPassword123!"
            }
            """;

        String testIp = "192.168.50.50";

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(
                    post("/api/v1/auth/login")
                            .header("X-Forwarded-For", testIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request)
            );
        }

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .header("X-Forwarded-For", testIp)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request)
                )
                .andExpect(status().isTooManyRequests());
    }


}
