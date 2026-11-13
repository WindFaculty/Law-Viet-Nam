package vn.edu.hsf302.lawrag.websearch.service;

/** The job deadline elapsed; the current worker must not publish anything. */
public class WebSearchJobDeadlineException extends RuntimeException {

    public WebSearchJobDeadlineException(String message) {
        super(message);
    }
}
