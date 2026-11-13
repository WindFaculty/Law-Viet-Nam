package vn.edu.hsf302.lawrag.chat.exception;

/**
 * Raised when the caller does not own the addressed session or message.
 * Not-found is reported the same way so IDs cannot be probed across owners.
 */
public class ChatAccessDeniedException extends RuntimeException {

    public ChatAccessDeniedException(String message) {
        super(message);
    }
}
