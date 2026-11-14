package vn.edu.hsf302.lawrag.forum.entity;

/** Moderation visibility state of a forum post. */
public enum ForumPostStatus {
    PUBLISHED("Đang hiển thị"),
    REMOVED("Đã gỡ");

    private final String label;

    ForumPostStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

