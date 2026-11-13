package vn.edu.hsf302.lawrag.admin.dto;

public record ApprovalCommand(
        String docNumber,
        String issuingBody,
        String issueDate,
        String effectiveFrom,
        String effectiveTo,
        String effectiveStatus,
        String reason) {
}
