package vn.edu.hsf302.lawrag.chat.dto;

import vn.edu.hsf302.lawrag.chat.entity.SessionOwnerType;

/**
 * Identity that owns a chat session. Every by-ID service operation receives the
 * caller's identity and compares it with the session owner before reading or writing.
 */
public record SessionOwner(SessionOwnerType type, String ownerId) {

    public SessionOwner {
        if (type == null) {
            throw new IllegalArgumentException("session owner type is required");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("session owner id is required");
        }
        int maximumLength = type == SessionOwnerType.ANON ? 64 : 100;
        if (ownerId.length() > maximumLength) {
            throw new IllegalArgumentException("session owner id is too long");
        }
    }

    public static SessionOwner anonymous(String anonId) {
        return new SessionOwner(SessionOwnerType.ANON, anonId);
    }

    public static SessionOwner account(String userId) {
        return new SessionOwner(SessionOwnerType.USER, userId);
    }
}
