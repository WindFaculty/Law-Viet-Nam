package vn.edu.hsf302.lawrag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.edu.hsf302.lawrag.gemini.config.GeminiProperties;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs after Flyway. Fails startup when pgvector is missing or when an existing
 * embedding column does not match the configured dimension.
 */
@Component
public class StartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);
    private static final Pattern VECTOR_TYPE = Pattern.compile("vector\\((\\d+)\\)");

    private final JdbcTemplate jdbcTemplate;
    private final GeminiProperties geminiProperties;

    public StartupValidator(JdbcTemplate jdbcTemplate, GeminiProperties geminiProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.geminiProperties = geminiProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        verifyPgvectorExtension();
        verifyEmbeddingColumnDimension();
        log.info("Startup validation passed: vector=present, embeddingDimensions={}, geminiEnabled={}",
                geminiProperties.embeddingDimensions(), geminiProperties.enabled());
    }

    private void verifyPgvectorExtension() {
        Boolean installed = jdbcTemplate.queryForObject(
                "select exists (select 1 from pg_extension where extname = 'vector')", Boolean.class);
        if (!Boolean.TRUE.equals(installed)) {
            throw new IllegalStateException(
                    "PostgreSQL extension 'vector' is not installed. Run the Flyway migrations against a pgvector database.");
        }
    }

    private void verifyEmbeddingColumnDimension() {
        Boolean tableExists = jdbcTemplate.queryForObject(
                "select to_regclass('public.article_embeddings') is not null", Boolean.class);
        if (!Boolean.TRUE.equals(tableExists)) {
            log.info("article_embeddings not created yet (Phase 2); embedding schema check deferred");
            return;
        }

        List<String> columnTypes = jdbcTemplate.queryForList("""
                select format_type(atttypid, atttypmod)
                from pg_attribute
                where attrelid = 'public.article_embeddings'::regclass
                  and attname = 'embedding'
                  and not attisdropped
                """, String.class);

        if (columnTypes.size() != 1) {
            throw new IllegalStateException("article_embeddings.embedding column is missing");
        }

        Matcher matcher = VECTOR_TYPE.matcher(columnTypes.get(0));
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "article_embeddings.embedding must be a vector column, found: " + columnTypes.get(0));
        }

        int schemaDimensions = Integer.parseInt(matcher.group(1));
        if (schemaDimensions != geminiProperties.embeddingDimensions()) {
            throw new IllegalStateException(
                    "Embedding dimension mismatch: schema=" + schemaDimensions
                            + ", configured=" + geminiProperties.embeddingDimensions());
        }
    }
}
