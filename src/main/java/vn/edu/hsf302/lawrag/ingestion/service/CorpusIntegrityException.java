package vn.edu.hsf302.lawrag.ingestion.service;

/**
 * Fails closed when the corpus file no longer matches the hash or structure
 * recorded in the manifest.
 */
public class CorpusIntegrityException extends RuntimeException {

    public CorpusIntegrityException(String message) {
        super(message);
    }

    public CorpusIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
