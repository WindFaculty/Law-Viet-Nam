package vn.edu.hsf302.lawrag.chat.exception;

import java.util.UUID;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;

/** The requested move is not part of the AI message state machine. */
public class InvalidMessageTransitionException extends RuntimeException {

    public InvalidMessageTransitionException(UUID messageId, MessageStatus from, MessageStatus to) {
        super("Message " + messageId + " cannot move from " + from + " to " + to);
    }
}
