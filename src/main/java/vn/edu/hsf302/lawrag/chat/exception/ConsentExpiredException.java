package vn.edu.hsf302.lawrag.chat.exception;

import java.util.UUID;

/** The web-search consent window has elapsed; no job may be created. */
public class ConsentExpiredException extends RuntimeException {

    public ConsentExpiredException(UUID messageId) {
        super("Web-search consent for message " + messageId + " has expired");
    }
}
