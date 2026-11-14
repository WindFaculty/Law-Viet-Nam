package vn.edu.hsf302.lawrag.llm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-configurable LLM personality that adjusts answer tone, structure, and verbosity
 * while keeping strict grounding and verbatim citation rules intact.
 */
@Entity
@Table(name = "llm_personas")
public class LlmPersona {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "system_instruction", nullable = false, columnDefinition = "text")
    private String systemInstruction;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_style", nullable = false, length = 32)
    private ResponseStyle responseStyle = ResponseStyle.CUSTOM;

    @Column(name = "is_default", nullable = false)
    private boolean defaultPersona = false;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LlmPersona() {
    }

    public LlmPersona(
            String code,
            String name,
            String description,
            String systemInstruction,
            ResponseStyle responseStyle,
            boolean defaultPersona,
            boolean active,
            int displayOrder) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.systemInstruction = systemInstruction;
        this.responseStyle = responseStyle == null ? ResponseStyle.CUSTOM : responseStyle;
        this.defaultPersona = defaultPersona;
        this.active = active;
        this.displayOrder = displayOrder;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSystemInstruction() {
        return systemInstruction;
    }

    public void setSystemInstruction(String systemInstruction) {
        this.systemInstruction = systemInstruction;
    }

    public ResponseStyle getResponseStyle() {
        return responseStyle;
    }

    public void setResponseStyle(ResponseStyle responseStyle) {
        this.responseStyle = responseStyle == null ? ResponseStyle.CUSTOM : responseStyle;
    }

    public boolean isDefaultPersona() {
        return defaultPersona;
    }

    public void setDefaultPersona(boolean defaultPersona) {
        this.defaultPersona = defaultPersona;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

