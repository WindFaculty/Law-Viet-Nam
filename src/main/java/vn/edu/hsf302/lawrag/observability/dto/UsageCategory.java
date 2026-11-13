package vn.edu.hsf302.lawrag.observability.dto;

/**
 * Turn outcome used to attribute provider cost. The list follows plan §16.1;
 * LOCAL_FAILED is separate so a provider/budget failure is not reported as a
 * successful local answer.
 */
public enum UsageCategory {
    LOCAL_ANSWERED,
    LOCAL_AWAITING_CONFIRM,
    LOCAL_FAILED,
    CLARIFICATION,
    WEB_CANCELLED,
    WEB_COMPLETED,
    WEB_NO_EVIDENCE,
    WEB_FAILED
}
