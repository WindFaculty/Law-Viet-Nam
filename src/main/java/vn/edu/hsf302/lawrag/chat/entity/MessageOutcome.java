package vn.edu.hsf302.lawrag.chat.entity;

/** Business result recorded alongside the terminal (or consenting) status. */
public enum MessageOutcome {
    ANSWERED,
    OUT_OF_SCOPE,
    AWAITING_WEB_CONFIRM,
    NEEDS_CLARIFICATION,
    CANCELLED,
    EXPIRED,
    NO_EVIDENCE,
    WEB_FAILED,
    FAILED
}
