package vn.edu.hsf302.lawrag.ingestion.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchErrorCode;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Converts an allowlisted fetch into text. Only a PDF text layer is accepted;
 * a scanned PDF is explicitly rejected with UNSUPPORTED_SCAN_PDF (no OCR).
 */
@Component
public class WebContentExtractor {

    private final UntrustedContentSanitizer sanitizer;

    public WebContentExtractor(UntrustedContentSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public ExtractedWebContent extract(String contentType, byte[] body) {
        String mediaType = contentType == null
                ? ""
                : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        try {
            return switch (mediaType) {
                case "application/pdf" -> extractPdf(body);
                case "text/html", "application/xhtml+xml" -> extractHtml(body);
                case "text/plain" -> extractPlainText(body);
                default -> new ExtractedWebContent("", WebSearchErrorCode.PARSE_FAILED);
            };
        } catch (IOException | RuntimeException exception) {
            return new ExtractedWebContent("", WebSearchErrorCode.PARSE_FAILED);
        }
    }

    private ExtractedWebContent extractPdf(byte[] body) throws IOException {
        try (PDDocument document = PDDocument.load(body)) {
            if (document.isEncrypted()) {
                return new ExtractedWebContent("", WebSearchErrorCode.PARSE_FAILED);
            }
            String text = new PDFTextStripper().getText(document);
            String sanitized = sanitizer.sanitize(text);
            if (sanitized.isBlank()) {
                return new ExtractedWebContent("", WebSearchErrorCode.UNSUPPORTED_SCAN_PDF);
            }
            return new ExtractedWebContent(sanitized, null);
        }
    }

    private ExtractedWebContent extractHtml(byte[] body) {
        Document document = Jsoup.parse(decode(body));
        document.select("script, style, noscript, iframe, object, embed, svg, form").remove();
        String text = document.body() == null ? document.text() : document.body().wholeText();
        String sanitized = sanitizer.sanitize(text);
        if (sanitized.isBlank()) {
            return new ExtractedWebContent("", WebSearchErrorCode.PARSE_FAILED);
        }
        return new ExtractedWebContent(sanitized, null);
    }

    private ExtractedWebContent extractPlainText(byte[] body) {
        String sanitized = sanitizer.sanitize(decode(body));
        if (sanitized.isBlank()) {
            return new ExtractedWebContent("", WebSearchErrorCode.PARSE_FAILED);
        }
        return new ExtractedWebContent(sanitized, null);
    }

    /**
     * Source policy allows a controlled fallback when UTF-8 is not declared.
     * U+FFFD indicates malformed UTF-8; Windows-1258 then ISO-8859-1 are tried.
     */
    private static String decode(byte[] body) {
        String utf8 = new String(body, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) {
            return utf8;
        }
        try {
            return new String(body, Charset.forName("windows-1258"));
        } catch (RuntimeException exception) {
            return new String(body, StandardCharsets.ISO_8859_1);
        }
    }

    public record ExtractedWebContent(String text, WebSearchErrorCode errorCode) {
    }
}
