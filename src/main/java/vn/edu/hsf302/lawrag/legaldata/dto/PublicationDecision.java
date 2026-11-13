package vn.edu.hsf302.lawrag.legaldata.dto;

import java.util.List;

/**
 * Outcome of the QUARANTINED -> READY gate. Empty failures means the document
 * and all of its articles passed the publication checks.
 */
public record PublicationDecision(boolean ready, List<String> failures) {

    public PublicationDecision {
        failures = List.copyOf(failures);
    }

    public static PublicationDecision publishable() {
        return new PublicationDecision(true, List.of());
    }

    public static PublicationDecision quarantine(List<String> failures) {
        return new PublicationDecision(false, failures);
    }

    public String reasons() {
        return String.join("; ", failures);
    }
}
