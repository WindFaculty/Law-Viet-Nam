package vn.edu.hsf302.lawrag.websearch.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySanitizerTest {

    private final QuerySanitizer sanitizer = new QuerySanitizer();

    @Test
    void stripsEmailPhoneNationalIdAndLicensePlate() {
        String query = sanitizer.sanitize(
                "mức phạt xe máy 0912345678 test@example.com 123456789012 51A-123.45");

        assertThat(query).contains("mức phạt");
        assertThat(query).doesNotContain("0912345678");
        assertThat(query).doesNotContain("test@example.com");
        assertThat(query).doesNotContain("123456789012");
        assertThat(query).doesNotContain("51A-123.45");
    }

    @Test
    void fallsBackWhenNothingRemains() {
        assertThat(sanitizer.sanitize("0912345678 test@example.com"))
                .isEqualTo("quy định pháp luật giao thông đường bộ");
        assertThat(sanitizer.sanitize(null))
                .isEqualTo("quy định pháp luật giao thông đường bộ");
    }

    @Test
    void capsQueryLengthAndRemovesControlCharacters() {
        String query = sanitizer.sanitize("a\u0000b\n" + "x".repeat(500));

        assertThat(query).hasSizeLessThanOrEqualTo(300);
        assertThat(query).doesNotContain("\u0000");
    }
}
