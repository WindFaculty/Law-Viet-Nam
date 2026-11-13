package vn.edu.hsf302.lawrag;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Integration tests use a real local PostgreSQL + pgvector database for the
 * whole test JVM, never H2.
 *
 * <p>The target schema is reset once per test JVM in the static initializer so
 * Flyway migrations always run from a clean state without requiring Docker or
 * Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    static final String TEST_DB_URL = envOrDefault(
            "TEST_DB_URL", "jdbc:postgresql://localhost:5432/lawrag_test");
    static final String TEST_DB_USERNAME = envOrDefault(
            "TEST_DB_USERNAME", "lawrag");
    static final String TEST_DB_PASSWORD = envOrDefault(
            "TEST_DB_PASSWORD", "lawrag_local_dev");

    static {
        resetPublicSchema(TEST_DB_URL, TEST_DB_USERNAME, TEST_DB_PASSWORD);
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TEST_DB_URL);
        registry.add("spring.datasource.username", () -> TEST_DB_USERNAME);
        registry.add("spring.datasource.password", () -> TEST_DB_PASSWORD);
    }

    static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    static void resetPublicSchema(String url, String username, String password) {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
            // Dropping public can remove pgvector's namespace objects; recreate
            // the real extension before Flyway so the startup gate sees it.
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "Failed to reset local PostgreSQL schema at " + url
                            + ". Ensure PostgreSQL 18 + pgvector is running on localhost:5432 "
                            + "and the database exists.",
                    ex);
        }
    }
}
