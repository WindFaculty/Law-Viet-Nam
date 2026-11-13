package vn.edu.hsf302.lawrag.ingestion.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Web text is untrusted data. Scripts are removed and lines that try to act as
 * instructions are dropped before the text becomes a snapshot/article. The
 * application never reads web content as a system prompt or tool policy.
 */
@Component
public class UntrustedContentSanitizer {

    private static final Pattern SCRIPT_OR_COMMENT = Pattern.compile(
            "(?is)<script\\b.*?</script>|<style\\b.*?</style>|<!--.*?-->");

    private static final List<Pattern> INJECTION_LINES = List.of(
            Pattern.compile("(?i)\\b(ignore|disregard|forget)\\b.{0,40}"
                    + "\\b(previous|prior|above)\\b.{0,40}\\b(instruction|prompt|rule)s?\\b"),
            Pattern.compile("(?i)\\b(system|developer)\\s+(prompt|message|instruction)s?\\b"),
            Pattern.compile("(?i)\\b(you are now|act as|pretend to be)\\b.{0,40}\\b(admin|system|assistant)\\b"),
            Pattern.compile("(?i)\\b(bo qua|quen)\\b.{0,30}\\b(huong dan|chi dan|system)\\b"),
            Pattern.compile("(?i)\\b(set|change|increase|decrease|grant|revoke)\\b.{0,30}"
                    + "\\b(quota|permission|tool)s?\\b"),
            Pattern.compile("(?i)\\b(cap quyen|thay doi quota|tang quota|giam quota)\\b"));

    public String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String withoutScripts = SCRIPT_OR_COMMENT.matcher(text).replaceAll("\n");
        String cleaned = withoutScripts.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]+", " ");
        List<String> kept = new ArrayList<>();
        for (String line : cleaned.split("\\R", -1)) {
            String stripped = line.strip();
            if (isInjection(stripped)) {
                continue;
            }
            kept.add(line.replaceAll("[ \\t]+", " ").stripTrailing());
        }
        return String.join("\n", kept)
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    public boolean containsInjection(String text) {
        if (text == null) {
            return false;
        }
        for (String line : text.split("\\R")) {
            if (isInjection(line)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInjection(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        for (Pattern pattern : INJECTION_LINES) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }
}
