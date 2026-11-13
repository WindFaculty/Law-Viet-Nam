-- Phase 8: a polite out-of-domain refusal is COMPLETED with outcome OUT_OF_SCOPE.
-- No new message status is added; quota is released instead of committed.
ALTER TABLE chat_messages DROP CONSTRAINT ck_chat_messages_outcome;
ALTER TABLE chat_messages ADD CONSTRAINT ck_chat_messages_outcome CHECK (
    outcome IS NULL OR outcome IN (
        'ANSWERED',
        'OUT_OF_SCOPE',
        'AWAITING_WEB_CONFIRM',
        'NEEDS_CLARIFICATION',
        'CANCELLED',
        'EXPIRED',
        'NO_EVIDENCE',
        'WEB_FAILED',
        'FAILED'
    )
);
