package vn.edu.hsf302.lawrag;

import org.junit.jupiter.api.Test;
import vn.edu.hsf302.lawrag.gemini.config.GeminiProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cheap repository guard against committing a real key. Runtime logging stays
 * safe by masking the key in GeminiProperties#toString.
 */
class SecretHygieneTest {

    private static final Pattern GOOGLE_API_KEY = Pattern.compile("AIza[0-9A-Za-z_-]{35}");
    private static final Pattern EMPTY_GOOGLE_KEY = Pattern.compile("(?m)^GOOGLE_API_KEY=\\s*$");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "xml", "yml", "yaml", "properties", "md", "json", "html", "css", "js",
            "sql", "example", "ps1", "sh", "cmd", "txt", "env", "gitignore");

    @Test
    void repositoryDoesNotContainAGoogleApiKey() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> textFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(this::isTextFile)
                    .filter(path -> !path.toString().contains(root.resolve("target").toString()))
                    .filter(path -> !path.toString().contains(root.resolve(".git").toString()))
                    .filter(path -> !path.getFileName().toString().equals(".env"))
                    .toList();

            assertThat(textFiles).isNotEmpty();
            for (Path file : textFiles) {
                String content = Files.readString(file);
                assertThat(GOOGLE_API_KEY.matcher(content).find())
                        .as("Google API key pattern found in %s", root.relativize(file))
                        .isFalse();
            }
        }
    }

    @Test
    void envExampleKeepsGoogleApiKeyEmpty() throws IOException {
        String example = Files.readString(Path.of(".env.example"));
        assertThat(EMPTY_GOOGLE_KEY.matcher(example).find()).isTrue();
    }

    @Test
    void propertiesToStringDoesNotExposeTheApiKey() {
        GeminiProperties properties = new GeminiProperties(
                true,
                "test-secret-value",
                "gemini-3.5-flash-lite",
                "gemini-embedding-001",
                "gemini-embedding-001@768:v1",
                768,
                Duration.ofSeconds(30));

        assertThat(properties.toString())
                .doesNotContain("test-secret-value")
                .contains("apiKey=***");
    }

    private boolean isTextFile(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return name.equals("mvnw") || name.equals(".gitignore") || name.equals(".env.example");
        }
        return TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }
}
