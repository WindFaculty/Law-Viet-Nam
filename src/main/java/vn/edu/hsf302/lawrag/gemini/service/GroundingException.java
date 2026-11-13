package vn.edu.hsf302.lawrag.gemini.service;

/** Provider/schema failure that must never be converted into a COMPLETED answer. */
public class GroundingException extends RuntimeException {

    public GroundingException(String message) {
        super(message);
    }

    public GroundingException(String message, Throwable cause) {
        super(message, cause);
    }
}
