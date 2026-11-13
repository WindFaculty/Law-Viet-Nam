package vn.edu.hsf302.lawrag.chat.dto;

import vn.edu.hsf302.lawrag.chat.entity.ChatMessage;

public record SubmittedQuestion(ChatMessage userMessage, ChatMessage aiMessage, boolean replay) {
}
