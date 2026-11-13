package vn.edu.hsf302.lawrag.websearch.service;

import vn.edu.hsf302.lawrag.websearch.entity.WebSearchErrorCode;

/** Provider failure classified for bounded retry or terminal finalization. */
public class WebSearchProviderException extends RuntimeException {

    private final WebSearchErrorCode errorCode;

    public WebSearchProviderException(WebSearchErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public WebSearchProviderException(WebSearchErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public WebSearchErrorCode getErrorCode() {
        return errorCode;
    }
}
