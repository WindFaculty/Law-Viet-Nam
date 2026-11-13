package vn.edu.hsf302.lawrag.chat.entity;

import vn.edu.hsf302.lawrag.chat.service.MessageStateService;

/**
 * Lifecycle of the AI answer. The transitions below are the only legal ones;
 * every other move is rejected by {@code MessageStateService}.
 */
public enum MessageStatus {
    PROCESSING_LOCAL,
    AWAITING_WEB_CONFIRM,
    WEB_QUEUED,
    WEB_SEARCHING,
    COMPLETED,
    CANCELLED,
    EXPIRED,
    NO_EVIDENCE,
    WEB_FAILED,
    FAILED,
    NEEDS_CLARIFICATION;

    public boolean canTransitionTo(MessageStatus target) {
        return switch (this) {
            case PROCESSING_LOCAL -> target == COMPLETED
                    || target == AWAITING_WEB_CONFIRM
                    || target == NEEDS_CLARIFICATION
                    || target == FAILED;
            case AWAITING_WEB_CONFIRM -> target == WEB_QUEUED
                    || target == CANCELLED
                    || target == EXPIRED;
            case WEB_QUEUED -> target == WEB_SEARCHING
                    || target == WEB_FAILED
                    || target == FAILED;
            case WEB_SEARCHING -> target == COMPLETED
                    || target == NO_EVIDENCE
                    || target == WEB_FAILED
                    || target == FAILED;
            // A manual retry after a transient worker failure re-queues the same message.
            case WEB_FAILED -> target == WEB_QUEUED;
            default -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, CANCELLED, EXPIRED, NO_EVIDENCE, FAILED, NEEDS_CLARIFICATION -> true;
            default -> false;
        };
    }
}
