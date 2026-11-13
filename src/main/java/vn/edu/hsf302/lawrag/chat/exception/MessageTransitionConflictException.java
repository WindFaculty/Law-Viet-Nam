package vn.edu.hsf302.lawrag.chat.exception;

import java.util.UUID;

/**
 * A concurrent transition changed the optimistic version first. The losing
 * transaction has been rolled back including its quota and job side effects.
 */
public class MessageTransitionConflictException extends RuntimeException {

    public MessageTransitionConflictException(UUID messageId) {
        super("Message " + messageId + " was changed by another transaction");
    }
}
