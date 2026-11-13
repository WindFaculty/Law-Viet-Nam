package vn.edu.hsf302.lawrag.forum.entity;

import java.util.Optional;

public enum ForumCategory {
    THAO_LUAN("Thảo luận chung"),
    GIAO_THONG("Giao thông"),
    DAN_SU("Dân sự"),
    LAO_DONG("Lao động"),
    HANH_CHINH("Hành chính"),
    KHAC("Khác");

    private final String label;

    ForumCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static Optional<ForumCategory> fromNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ForumCategory.valueOf(raw.strip().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}

