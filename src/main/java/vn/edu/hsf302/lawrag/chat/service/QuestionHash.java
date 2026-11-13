package vn.edu.hsf302.lawrag.chat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Consent is bound to the exact question text. The hash is computed over the
 * trimmed question with runs of whitespace collapsed to one space.
 */
final class QuestionHash {

    private QuestionHash() {
    }

    static String of(String question) {
        String normalized = question == null ? "" : question.strip().replaceAll("\\s+", " ");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }
}
