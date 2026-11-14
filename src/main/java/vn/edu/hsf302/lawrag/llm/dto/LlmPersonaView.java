package vn.edu.hsf302.lawrag.llm.dto;

import vn.edu.hsf302.lawrag.llm.entity.LlmPersona;
import vn.edu.hsf302.lawrag.llm.entity.ResponseStyle;

import java.time.Instant;
import java.util.UUID;

/** View projection for displaying or editing an LLM personality. */
public record LlmPersonaView(
        UUID id,
        String code,
        String name,
        String description,
        String systemInstruction,
        ResponseStyle responseStyle,
        String styleLabel,
        boolean defaultPersona,
        boolean active,
        int displayOrder,
        Instant updatedAt) {

    public static LlmPersonaView from(LlmPersona persona) {
        return new LlmPersonaView(
                persona.getId(),
                persona.getCode(),
                persona.getName(),
                persona.getDescription(),
                persona.getSystemInstruction(),
                persona.getResponseStyle(),
                persona.getResponseStyle().label(),
                persona.isDefaultPersona(),
                persona.isActive(),
                persona.getDisplayOrder(),
                persona.getUpdatedAt());
    }
}

