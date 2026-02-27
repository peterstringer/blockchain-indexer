package com.peterstringer.blockchain.indexer.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for all integration tests.
 *
 * <p>Boots the full Spring application context against a Testcontainers
 * PostgreSQL instance with Flyway migrations applied. All subclasses
 * share a single container via the static {@link #postgres} field.
 *
 * <p>The container is started once in a static initializer and kept alive
 * for the entire JVM lifetime (Testcontainers registers a shutdown hook).
 * This avoids port changes between test classes that would invalidate
 * the cached Spring context's HikariCP connection pool.
 *
 * <p>Uses the {@code integration} profile, which enables Flyway,
 * configures demo mode with small block ranges, and validates the
 * JPA schema against the migrated database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
public abstract class AbstractIntegrationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("indexer_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
