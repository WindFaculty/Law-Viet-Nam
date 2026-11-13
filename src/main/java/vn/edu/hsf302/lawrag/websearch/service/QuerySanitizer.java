package vn.edu.hsf302.lawrag.websearch.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Removes unnecessary personal data before a query leaves the application.
 * The worker never sends chat history; only the stored suggested query is used.
 */
@Component
public class QuerySanitizer {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+\\b");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+84|0)(?:[\\s.\\-]?\\d){9,10}(?!\\d)");
    private static final Pattern NATIONAL_ID = Pattern.compile(
            "(?<!\\d)\\d{9}(?:\\d{3})?(?!\\d)");
    private static final Pattern LICENSE_PLATE = Pattern.compile(
            "(?i)\\b\\d{2}[A-ZĐ]{1,2}[\\s.\\-]?\\d{3,5}(?:[.\\-]?\\d{2})?\\b");
    private static final int MAX_QUERY_LENGTH = 300;

    public String sanitize(String query) {
        String value = query == null ? "" : query;
        value = EMAIL.matcher(value).replaceAll(" ");
        value = PHONE.matcher(value).replaceAll(" ");
        value = LICENSE_PLATE.matcher(value).replaceAll(" ");
        value = NATIONAL_ID.matcher(value).replaceAll(" ");
        value = value.replaceAll("[\\p{Cntrl}]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (value.isEmpty()) {
            value = "quy định pháp luật giao thông đường bộ";
        }
        return value.length() <= MAX_QUERY_LENGTH ? value : value.substring(0, MAX_QUERY_LENGTH).strip();
    }
}
