package vn.edu.hsf302.lawrag.websearch.service;

/** The worker no longer owns the active lease; it must stop without writing. */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(String message) {
        super(message);
    }
}
