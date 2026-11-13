package vn.edu.hsf302.lawrag.chat.dto;

import java.util.List;
import java.util.UUID;

/** Whole conversation plus the quota badge shown on page load. */
public record ChatConversationView(
        UUID sessionId,
        List<ChatMessageView> messages,
        int quotaUsed,
        int quotaLimit,
        boolean webSearchAllowed) {

    public ChatConversationView {
        messages = List.copyOf(messages);
    }
}
