package vn.edu.hsf302.lawrag.retrieval.service;

/** A model answer that cannot be published as a durable legal answer. */
public class CitationValidationException extends RuntimeException {

    public CitationValidationException(String message) {
        super(message);
    }
}
