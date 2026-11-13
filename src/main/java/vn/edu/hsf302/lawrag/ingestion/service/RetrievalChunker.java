package vn.edu.hsf302.lawrag.ingestion.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an overly long legal unit into deterministic retrieval chunks. The
 * citation always keeps pointing at the whole {@code legal_articles} row; the
 * chunk index only exists to keep embeddings within the model input budget.
 */
public final class RetrievalChunker {

    public static final int MAX_CHARS = 1_200;
    private static final int MIN_BODY_CHARS = 200;

    private RetrievalChunker() {
    }

    public static List<String> chunk(String contextHeader, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String header = contextHeader == null || contextHeader.isBlank() ? "" : contextHeader.strip() + "\n";
        int bodyLimit = Math.max(MIN_BODY_CHARS, MAX_CHARS - header.length());
        String body = text.strip();

        List<String> bodies = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : body.split("\\R")) {
            appendParagraph(current, paragraph, bodyLimit, bodies);
        }
        if (!current.isEmpty()) {
            bodies.add(current.toString().strip());
        }
        if (bodies.isEmpty()) {
            bodies.add(body);
        }

        List<String> chunks = new ArrayList<>(bodies.size());
        for (String chunkBody : bodies) {
            chunks.add(header + chunkBody);
        }
        return List.copyOf(chunks);
    }

    private static void appendParagraph(StringBuilder current, String paragraph, int bodyLimit, List<String> bodies) {
        String remaining = paragraph;
        while (remaining.length() > bodyLimit) {
            int cut = lastBreak(remaining, bodyLimit);
            String head = remaining.substring(0, cut).strip();
            if (!head.isEmpty()) {
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(head);
            }
            if (!current.isEmpty()) {
                bodies.add(current.toString().strip());
                current.setLength(0);
            }
            remaining = remaining.substring(cut).strip();
        }
        if (remaining.isEmpty()) {
            return;
        }
        if (current.length() + remaining.length() + 1 > bodyLimit && !current.isEmpty()) {
            bodies.add(current.toString().strip());
            current.setLength(0);
        }
        if (!current.isEmpty()) {
            current.append('\n');
        }
        current.append(remaining);
    }

    private static int lastBreak(String text, int limit) {
        int whitespace = text.lastIndexOf(' ', limit);
        return whitespace > 0 ? whitespace : limit;
    }
}
