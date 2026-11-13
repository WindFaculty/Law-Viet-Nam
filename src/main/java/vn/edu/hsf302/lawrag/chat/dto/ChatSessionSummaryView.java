package vn.edu.hsf302.lawrag.chat.dto;

import java.util.UUID;

/** Lightweight summary of an owned conversation shown in the chat sidebar. */
public record ChatSessionSummaryView(
        UUID id,
        String title,
        String updatedAtDisplay,
        boolean active,
        long messageCount
) {
}

