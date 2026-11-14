package vn.edu.hsf302.lawrag.admin.dto;

import java.util.List;

public record Dashboard(
        List<AdminJobView> jobs,
        List<RagDocumentView> ragDocuments,
        List<QuarantinedDocumentView> quarantinedDocuments,
        List<AuditRow> audit) {

    public Dashboard(List<AdminJobView> jobs,
                     List<QuarantinedDocumentView> quarantinedDocuments,
                     List<AuditRow> audit) {
        this(jobs, List.of(), quarantinedDocuments, audit);
    }
}
