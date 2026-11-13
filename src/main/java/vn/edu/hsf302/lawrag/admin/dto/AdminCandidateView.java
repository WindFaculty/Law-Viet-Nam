package vn.edu.hsf302.lawrag.admin.dto;

public record AdminCandidateView(
        String url,
        int rank,
        boolean accepted,
        String rejectionReason,
        String resultCode) {
}
