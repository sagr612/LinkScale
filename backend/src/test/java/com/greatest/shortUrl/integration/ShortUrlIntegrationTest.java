package com.greatest.shortUrl.integration;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greatest.shortUrl.entity.ShortUrl;
import com.greatest.shortUrl.model.UrlStatus;
import com.greatest.shortUrl.repository.ShortUrlRepo;
import com.greatest.shortUrl.services.ClickCountFlusher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration")
public class ShortUrlIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ShortUrlRepo shortUrlRepo;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private ClickCountFlusher clickCountFlusher;

    @AfterEach
    void clearRedis() {
        redissonClient.getKeys().flushdb();
    }

    @Test
    void authenticatedUser_canCreateShortUrl_andRetrieveItFromMyUrls() throws Exception {
        String email = "url-user" + UUID.randomUUID() + "@example.com";
        String accessToken = signupAndGetAccessToken(email);

        String createResponse = mockMvc.perform(
                        post("/api/v1/shorten/")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "originalUrl": "https://example.com/my-long-url",
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
        String id = createdUrl.get("id").asText();
        String shortKey = createdUrl.get("shortKey").asText();

        assertThat(id).isNotBlank();
        assertThat(shortKey).isNotBlank();
        assertThat(createdUrl.get("originalUrl").asText()).isEqualTo("https://example.com/my-long-url");
        assertThat(createdUrl.get("isPrivate").asBoolean()).isFalse();
        assertThat(createdUrl.get("status").asText()).isEqualTo("ACTIVE");

        ShortUrl savedUrl = shortUrlRepo.findById(id).orElseThrow();

        assertThat(savedUrl.getShortKey()).isEqualTo(shortKey);
        assertThat(savedUrl.getOriginalUrl()).isEqualTo("https://example.com/my-long-url");
        assertThat(savedUrl.getIsPrivate()).isFalse();
        assertThat(savedUrl.getClickCount()).isZero();


        String myUrlsResponse = mockMvc.perform(
                        get("/api/v1/shorten/my-urls")
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode myUrls = objectMapper.readTree(myUrlsResponse);

        assertThat(myUrls.get("data").isArray()).isTrue();
        assertThat(myUrls.get("data")).hasSize(1);
        assertThat(myUrls.get("data").get(0).get("id").asText()).isEqualTo(id);
        assertThat(myUrls.get("data").get(0).get("shortKey").asText()).isEqualTo(shortKey);


    }

    private String signupAndGetAccessToken(String email) throws Exception {
        String response = mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                                {
                                                  "email": "%s",
                                                  "password": "Secret123!",
                                                  "name": "URL Test User"
                                                }
                                                """.formatted(email))
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("accessToken").asText();

    }


    @Test
    void publicUrl_redirectsToOriginalUrl_andIncrementsClickCount() throws Exception {
        String email = "redirect-user-" + UUID.randomUUID() + "@example.com";
        String accessToken = signupAndGetAccessToken(email);

        String createResponse = mockMvc.perform(
                        post("/api/v1/shorten/")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "originalUrl": "https://spring.io/projects/spring-boot",
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

        String id = createdUrl.get("id").asText();
        String shortKey = createdUrl.get("shortKey").asText();

        ShortUrl beforeRedirect = shortUrlRepo.findById(id).orElseThrow();

        assertThat(beforeRedirect.getClickCount()).isZero();

        mockMvc.perform(
                        get("/s/public/" + shortKey)
                ).andExpect(status().isFound())
                .andExpect(header().string(
                        "Location",
                        "https://spring.io/projects/spring-boot"
                ));

        clickCountFlusher.flush();
        ShortUrl afterRedirect = shortUrlRepo.findById(id).orElseThrow();

        assertThat(afterRedirect.getClickCount()).isEqualTo(1L);

    }

    @Test
    void publicUrl_unknownShortKey_returnsNotFound() throws Exception {
        mockMvc.perform(
                get("/s/public/unknown-short-key")
        ).andExpect(status().isNotFound());
    }


    @Test
    void privateUrl_ownerCanAccessOrignalUrl() throws Exception {
        String ownerEmail = "private-owner-" + UUID.randomUUID() + "@example.com";
        String ownerToken = signupAndGetAccessToken(ownerEmail);

        String createResponse = mockMvc.perform(
                        post("/api/v1/shorten/")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "originalUrl": "https://example.com/private-destination",
                                          "isPrivate": true,
                                          "expirationInDays": 7
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode createdUrl = objectMapper.readTree(createResponse);
        String shortKey = createdUrl.get("shortKey").asText();

        assertThat(createdUrl.get("isPrivate").asBoolean()).isTrue();

        mockMvc.perform(
                        get("/s/" + shortKey)
                                .header("Authorization", "Bearer " + ownerToken)
                )
                .andExpect(status().isOk())
                .andExpect(content()
                        .string("https://example.com/private-destination"));

    }


    @Test
    void privateUrl_wrongAuthenticatedUserCannotAccessOrignalUrl() throws Exception {
        String ownerEmail = "private-owner-" + UUID.randomUUID() + "@example.com";
        String ownerToken = signupAndGetAccessToken(ownerEmail);

        String createResponse = mockMvc.perform(
                        post("/api/v1/shorten/")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "originalUrl": "https://example.com/private-destination",
                                          "isPrivate": true,
                                          "expirationInDays": 7
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode createdUrl = objectMapper.readTree(createResponse);
        String shortKey = createdUrl.get("shortKey").asText();

        String otherEmail = "private-other-" + UUID.randomUUID() + "@example.com";
        String otherToken = signupAndGetAccessToken(otherEmail);

        mockMvc.perform(
                get("/s/" + shortKey)
                        .header("Authorization", "Bearer " + otherToken)
        ).andExpect(status().isNotFound());


    }

    @Test
    void privateUrl_publicEndpointReturnsNotFound() throws Exception {
        String ownerEmail = "private-public-" + UUID.randomUUID() + "@example.com";
        String ownerToken = signupAndGetAccessToken(ownerEmail);

        String createResponse = mockMvc.perform(
                        post("/api/v1/shorten/")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "originalUrl": "https://example.com/private-destination",
                                          "isPrivate": true,
                                          "expirationInDays": 7
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode createdUrl = objectMapper.readTree(createResponse);
        String shortKey = createdUrl.get("shortKey").asText();

        mockMvc.perform(
                get("/s/public/" + shortKey)
        ).andExpect(status().isNotFound());


    }

    @Test
    void urlOwner_canUpdateDestinationUrl() throws Exception {
        String email = "update-owner-" + UUID.randomUUID() + "@example.com";
        String token = signupAndGetAccessToken(email);

        String createResponse = mockMvc.perform(
                        post("/api/v1/shorten/")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "originalUrl": "https://example.com/old-destination",
                                          "isPrivate": false,
                                          "expirationInDays": 7
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String shortKey = objectMapper.readTree(createResponse).get("shortKey").asText();
        String updateResponse = mockMvc.perform(
                        put("/api/v1/shorten/" + shortKey)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "originalUrl": "https://example.com/new-destination"
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode updatedUrl = objectMapper.readTree(updateResponse);

        assertThat(updatedUrl.get("originalUrl").asText()).isEqualTo("https://example.com/new-destination");

        mockMvc.perform(
                        get("/s/public/" + shortKey)
                ).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/new-destination"));

    }

    @Test
    void urlOwner_cannotBeUpdatedByAnotherUser() throws Exception {
        String ownerToken = signupAndGetAccessToken("update-owner" + UUID.randomUUID() + "@example.com");
        String otherToken = signupAndGetAccessToken("update-other-" + UUID.randomUUID() + "@example.com");


        String createResponse = mockMvc.perform(
                        post("/api/v1/shorten/")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "originalUrl": "https://example.com/original",
                                          "isPrivate": false,
                                          "expirationInDays": 7
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();


        String shortKey = objectMapper.readTree(createResponse).get("shortKey").asText();

        mockMvc.perform(
                        put("/api/v1/shorten/" + shortKey)
                                .header("Authorization", "Bearer " + otherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "originalUrl": "https://example.com/hacked"
                                        }
                                        """)
                )
                .andExpect(status().isForbidden());
    }


    @Test
    void urlOwner_canDeleteUrl_andDeletedUrlIsNotReturned() throws Exception {
        String token = signupAndGetAccessToken("delete-user-" + UUID.randomUUID() + "@example.com");

        String createResponse = mockMvc.perform(
                        post("/api/v1/shorten/")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "originalUrl": "https://example.com/to-delete",
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
        String id = createdUrl.get("id").asText();
        String shortKey = createdUrl.get("shortKey").asText();

        mockMvc.perform(
                delete("/api/v1/shorten/delete-urls")
                        .header("Authorization", "Bearer " + token)
                        .param("ids", id)
        ).andExpect(status().isOk());

        ShortUrl deletedUrl = shortUrlRepo.findById(id).orElseThrow();
        assertThat(deletedUrl.getStatus()).isEqualTo(UrlStatus.DELETED);
        mockMvc.perform(get("/s/public/" + shortKey)).andExpect(status().isNotFound());

        JsonNode myUrls = objectMapper.readTree(
                mockMvc.perform(
                                get("/api/v1/shorten/my-urls")
                                        .header("Authorization", "Bearer " + token)
                        ).andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
        );

        assertThat(myUrls.get("data")).noneMatch(url -> url.get("id").asText().equals(id));


    }


    @Test
    void protectedEndpoint_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/shorten/my-urls")).andExpect(status().isUnauthorized());
    }

    @Test
    void createUrl_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(
                        post("/api/v1/shorten/")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "originalUrl": "https://example.com",
                                          "isPrivate": false,
                                          "expirationInDays": 7
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized());
    }


    @Test
    void publicUrlList_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/shorten/")).andExpect(status().isUnauthorized());
    }

    @Test
    void createUrl_blankOriginalUrl_returnsBadRequest() throws Exception {
        String token = signupAndGetAccessToken("validation-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(
                post("/api/v1/shorten/")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "originalUrl": "",
                                  "isPrivate": false,
                                  "expirationInDays": 7
                                }
                                """)
        ).andExpect(status().isBadRequest());
    }

    @Test
    void createUrl_expirationAbove365Days_returnsBadRequest() throws Exception {
        String token = signupAndGetAccessToken("expiration-" + UUID.randomUUID() + "@example.com");
        mockMvc.perform(
                post("/api/v1/shorten/")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "originalUrl": "https://example.com",
                                  "isPrivate": false,
                                  "expirationInDays": 366
                                }
                                """)
        ).andExpect(status().isBadRequest());
    }

    @Test
    void expiredPublicUrl_returnsNotFound() throws Exception {
        ShortUrl expiredUrl = new ShortUrl();
        expiredUrl.setShortKey("expired1");
        expiredUrl.setOriginalUrl("https://example.com/expired");
        expiredUrl.setIsPrivate(false);
        expiredUrl.setExpiresAt(Instant.now().minusSeconds(60));
        expiredUrl.setClickCount(0L);
        expiredUrl.setCreatedAt(Instant.now());
        expiredUrl.setStatus(UrlStatus.ACTIVE);
        expiredUrl.setCreatedBy(null);

        shortUrlRepo.saveAndFlush(expiredUrl);

        mockMvc.perform(get("/s/public/expired1"))
                .andExpect(status().isNotFound());

        ShortUrl storedUrl = shortUrlRepo
                .findByShortKey("expired1")
                .orElseThrow();

        assertThat(storedUrl.getClickCount()).isZero();
    }



}
