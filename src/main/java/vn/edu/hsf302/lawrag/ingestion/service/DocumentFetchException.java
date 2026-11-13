package vn.edu.hsf302.lawrag.ingestion.service;

import vn.edu.hsf302.lawrag.websearch.entity.WebSearchErrorCode;

/** A fetch was refused or failed before any bytes became trusted content. */
public class DocumentFetchException extends RuntimeException {

    private final WebSearchErrorCode errorCode;

    public DocumentFetchException(WebSearchErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public DocumentFetchException(WebSearchErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public WebSearchErrorCode getErrorCode() {
        return errorCode;
    }
}
