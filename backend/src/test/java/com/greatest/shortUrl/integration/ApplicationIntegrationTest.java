package com.greatest.shortUrl.integration;

import com.greatest.shortUrl.ShortUrlApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        classes = ShortUrlApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("integration")
class ApplicationIntegrationTest extends AbstractIntegrationTest {

    @Test
    void applicationContextLoadsWithPostgresAndRedis() {
    }
}
