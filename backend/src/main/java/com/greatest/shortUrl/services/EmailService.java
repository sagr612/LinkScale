package com.greatest.shortUrl.services;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class EmailService {

    @Value("${brevo.api-key}")
    private String apiKey;

    @Value("${app.mail.sender-email:noreply@shorturl.com}")
    private String senderEmail;

    private final RestClient restClient = RestClient.create();

    @PostConstruct
    public void init() {
        log.info("Brevo key loaded: {}...{}",
                apiKey.substring(0, 10),
                apiKey.substring(apiKey.length() - 4));
    }

    public void sendEmail(String to, String subject, String htmlBody) {
        Map<String, Object> payload = Map.of(
                "sender", Map.of("name", "ShortURL", "email", senderEmail),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "htmlContent", htmlBody
        );

        try {
            restClient.post()
                    .uri("https://api.brevo.com/v3/smtp/email")
                    .header("api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Email sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send email: {}", e.getMessage());
            throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
        }
    }
}