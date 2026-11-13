package vn.edu.hsf302.lawrag.chat.dto;

/** One USER question and its AI answer, returned after message submission. */
public record ChatMessagePairView(ChatMessageView user, ChatMessageView assistant) {
}
