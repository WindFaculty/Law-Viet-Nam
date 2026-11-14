package vn.edu.hsf302.lawrag.admin.dto;

import java.time.LocalDate;
import java.util.UUID;

/** Read model for a retrieval & grounding decision trace in the admin log viewer. */
public record RagTraceLogView(
        UUID id,
        UUID messageId,
        String question,
        LocalDate applicableDate,
        String modelId,
        String promptVersion,
        String personaCode,
        boolean gatePassed,
        double maxCosine,
        String decision,
        String reasonCode,
        String messageStatus,
        String candidateScoresJson,
        String createdAtDisplay) {
}

