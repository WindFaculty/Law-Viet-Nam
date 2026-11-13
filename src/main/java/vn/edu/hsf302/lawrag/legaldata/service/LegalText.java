package vn.edu.hsf302.lawrag.legaldata.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared text rules: verbatim text keeps its content, only whitespace is
 * normalized. Hashes are always UTF-8 SHA-256 lowercase hex.
 */
public final class LegalText {

    private LegalText() {
    }

    public static String normalizeWhitespace(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").strip();
    }

    public static boolean matchesVerbatim(String snapshotContent, String rawText) {
        if (snapshotContent == null || rawText == null || rawText.isBlank()) {
            return false;
        }
        return normalizeWhitespace(snapshotContent).contains(normalizeWhitespace(rawText));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }
}
