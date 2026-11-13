package vn.edu.hsf302.lawrag.admin.service;

/** A rejected or failed admin approval that leaves the document unchanged. */
public class AdminApprovalException extends RuntimeException {

    public AdminApprovalException(String message) {
        super(message);
    }
}
