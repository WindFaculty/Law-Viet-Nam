package vn.edu.hsf302.lawrag.ingestion.service;

import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts only metadata that is explicitly present in the fetched text.
 * Missing fields stay null; nothing is inferred from the file name or AI.
 */
@Component
public class WebMetadataExtractor {

    private static final Pattern DOC_NUMBER = Pattern.compile(
            "(?im)^\\s*(?:Số|Luật số|Nghị định số|Thông tư số|Quyết định số)\\s*[:.]?\\s*"
                    + "([0-9]{1,5}\\s*/\\s*[0-9]{4}\\s*/\\s*[A-ZĐ][A-ZĐ0-9-]*)");
    private static final Pattern ISSUING_BODY = Pattern.compile(
            "(?im)^\\s*(QUỐC HỘI|CHÍNH PHỦ|ỦY BAN THƯỜNG VỤ QUỐC HỘI|"
                    + "BỘ \\p{Lu}[\\p{Lu} \\-]{2,60})\\s*$");
    private static final Pattern ISSUE_DATE_NUMERIC = Pattern.compile(
            "(?i)(?:ban hành|hà nội,?)\\s*(?:ngày\\s*)?(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})");
    private static final Pattern ISSUE_DATE_WORDS = Pattern.compile(
            "(?i)\\bngày\\s*(\\d{1,2})\\s*tháng\\s*(\\d{1,2})\\s*năm\\s*(\\d{4})");
    private static final Pattern EFFECTIVE_FROM_NUMERIC = Pattern.compile(
            "(?i)có hiệu lực(?: thi hành)?(?: kể)? từ ngày\\s*"
                    + "(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})");
    private static final Pattern EFFECTIVE_FROM_WORDS = Pattern.compile(
            "(?i)có hiệu lực(?: thi hành)?(?: kể)? từ ngày\\s*"
                    + "(\\d{1,2})\\s*tháng\\s*(\\d{1,2})\\s*năm\\s*(\\d{4})");
    private static final Pattern EFFECTIVE_TO = Pattern.compile(
            "(?i)(?:hết hiệu lực|bãi bỏ).{0,40}?ngày\\s*"
                    + "(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})");
    private static final Pattern TITLE_LINE = Pattern.compile(
            "(?i)^(LUẬT|BỘ LUẬT|NGHỊ ĐỊNH|THÔNG TƯ|QUYẾT ĐỊNH|PHÁP LỆNH)\\b.{3,250}$");

    public WebDocumentMetadata extract(String text) {
        if (text == null || text.isBlank()) {
            return new WebDocumentMetadata(null, null, null, null, null, null);
        }
        List<String> lines = text.lines().toList();
        return new WebDocumentMetadata(
                title(lines),
                normalizeDocNumber(firstGroup(DOC_NUMBER, text)),
                firstGroup(ISSUING_BODY, text),
                firstDate(ISSUE_DATE_NUMERIC, text, ISSUE_DATE_WORDS),
                firstDate(EFFECTIVE_FROM_NUMERIC, text, EFFECTIVE_FROM_WORDS),
                firstDate(EFFECTIVE_TO, text, null));
    }

    private static String title(List<String> lines) {
        for (String line : lines) {
            String stripped = line.strip();
            if (TITLE_LINE.matcher(stripped).matches()) {
                return stripped;
            }
        }
        return lines.stream()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String normalizeDocNumber(String value) {
        return value == null ? null : value.replaceAll("\\s*/\\s*", "/").strip();
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private static LocalDate firstDate(Pattern numeric, String text, Pattern words) {
        Matcher matcher = numeric.matcher(text);
        if (matcher.find()) {
            return date(matcher.group(1), matcher.group(2), matcher.group(3));
        }
        if (words != null) {
            Matcher wordMatcher = words.matcher(text);
            if (wordMatcher.find()) {
                return date(wordMatcher.group(1), wordMatcher.group(2), wordMatcher.group(3));
            }
        }
        return null;
    }

    private static LocalDate date(String day, String month, String year) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        } catch (DateTimeException | NumberFormatException exception) {
            return null;
        }
    }

    public record WebDocumentMetadata(
            String title,
            String docNumber,
            String issuingBody,
            LocalDate issueDate,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }
}
