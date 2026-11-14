package vn.edu.hsf302.lawrag.chat.dto;

import java.util.List;
import java.util.UUID;

/** Whole conversation plus the quota badge shown on page load. */
public record ChatConversationView(
        UUID sessionId,
        List<ChatMessageView> messages,
        int quotaUsed,
        int quotaLimit,
        boolean webSearchAllowed,
        String personaCode) {

    public ChatConversationView(
            UUID sessionId,
            List<ChatMessageView> messages,
            int quotaUsed,
            int quotaLimit,
            boolean webSearchAllowed) {
        this(sessionId, messages, quotaUsed, quotaLimit, webSearchAllowed, null);
    }

    public ChatConversationView {
        messages = List.copyOf(messages);
    }
}
