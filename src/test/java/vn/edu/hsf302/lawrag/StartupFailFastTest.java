package vn.edu.hsf302.lawrag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A wrong embedding dimension must abort startup instead of silently writing
 * vectors that can never be compared with the schema.
 */
class StartupFailFastTest {

    private static final String FAILFAST_DB_URL = PostgresIntegrationTest.envOrDefault(
            "TEST_FAILFAST_DB_URL", "jdbc:postgresql://localhost:5432/lawrag_failfast");
    private static final String DB_USERNAME = PostgresIntegrationTest.TEST_DB_USERNAME;
    private static final String DB_PASSWORD = PostgresIntegrationTest.TEST_DB_PASSWORD;

    @BeforeAll
    static void resetDatabase() {
        PostgresIntegrationTest.resetPublicSchema(FAILFAST_DB_URL, DB_USERNAME, DB_PASSWORD);
    }

    @Test
    void applicationFailsWhenEmbeddingDimensionIsNot768() {
        assertThatThrownBy(() -> startApplication(
                "--app.gemini.embedding-dimensions=384"))
                .hasStackTraceContaining("768");
    }

    @Test
    void applicationFailsWhenGeminiIsEnabledWithoutApiKey() {
        assertThatThrownBy(() -> startApplication(
                "--app.gemini.enabled=true",
                "--app.gemini.api-key="))
                .hasStackTraceContaining("GOOGLE_API_KEY");
    }

    private void startApplication(String... extraArguments) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.profiles.active=test",
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--logging.level.org.springframework.boot.diagnostics.LoggingFailureAnalysisReporter=OFF",
                "--spring.datasource.url=" + FAILFAST_DB_URL,
                "--spring.datasource.username=" + DB_USERNAME,
                "--spring.datasource.password=" + DB_PASSWORD));
        arguments.addAll(List.of(extraArguments));

        new SpringApplicationBuilder(LawRagApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(arguments.toArray(String[]::new));
    }
}
