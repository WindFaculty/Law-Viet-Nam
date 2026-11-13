package vn.edu.hsf302.lawrag.chat.exception;

/** A manual retry is not permitted (expired consent or the retry cap). */
public class WebSearchRetryNotAllowedException extends RuntimeException {

    public WebSearchRetryNotAllowedException(String message) {
        super(message);
    }
}
