package vn.edu.hsf302.lawrag.chat.dto;

import vn.edu.hsf302.lawrag.gemini.service.GroundingClient;

import java.util.List;
import java.util.UUID;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;

public record ChatOutcome(
        UUID messageId,
        MessageStatus status,
        GroundingClient.Outcome decision,
        GroundingClient.ReasonCode reasonCode,
        List<UUID> selectedArticleIds) {
}
