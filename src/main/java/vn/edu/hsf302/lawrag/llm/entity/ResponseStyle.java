package vn.edu.hsf302.lawrag.llm.entity;

/**
 * Response style presets used both in prompt guidance and deterministic offline rendering.
 */
public enum ResponseStyle {
    PROFESSIONAL("Chuyên nghiệp"),
    CONCISE("Ngắn gọn"),
    DETAILED("Đầy đủ"),
    CUSTOM("Tùy chỉnh");

    private final String label;

    ResponseStyle(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

