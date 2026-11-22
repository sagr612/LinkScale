package com.greatest.shortUrl.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Singleton Container Pattern — containers start ONCE for the entire test suite JVM.
 *
 * Why not @Testcontainers + @Container?
 *   With @Container on static fields in an abstract class, JUnit 5 starts and STOPS
 *   containers after each concrete test class finishes. The next test class then gets
 *   new containers on different ports. Spring's context cache still holds the Redisson
 *   client from the old (now dead) ports → WriteRedisConnection errors.
 *
 * With the static{} initializer approach:
 *   - Containers start once when this class is loaded (first test class that extends it)
 *   - Ports never change → Spring's context cache is valid for all test classes
 *   - Redisson client stays connected throughout the entire suite
 */
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shorturl_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            // Wait until Redis logs "Ready to accept connections" before Spring context
            // initializes. Without this, Redisson's connection pool connects to Redis
            // before it's ready → channels fail → WriteRedisConnection errors.
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Database
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",  POSTGRES::getUsername);
        registry.add("spring.datasource.password",  POSTGRES::getPassword);

        // Redis — ports are fixed for the JVM lifetime, so Spring context cache stays valid
        registry.add("spring.data.redis.host",         REDIS::getHost);
        registry.add("spring.data.redis.port",         () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password",     () -> "");
        registry.add("spring.data.redis.ssl.enabled",  () -> false);

        // Flyway — run real migrations against the Postgres container
        registry.add("spring.flyway.enabled",            () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto",    () -> "validate");

        // App properties — cover all ${...} placeholders from application.properties
        // so no PlaceholderResolutionException ever occurs in integration tests
        registry.add("app.frontend.url",            () -> "http://localhost:5173");
        registry.add("app.validate-original-url",   () -> false);
        registry.add("app.default-expiry-in-days",  () -> 30);
        registry.add("jwt.secret",                  () -> "dGVzdC1qd3Qtc2VjcmV0LWtleS1mb3ItaHMtMjU2LXRlc3Rz");
        registry.add("jwt.expirationMs",            () -> 86400000);
        registry.add("brevo.api-key",               () -> "test-brevo-key-for-integration-tests");
    }
}
