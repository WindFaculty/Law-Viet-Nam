package vn.edu.hsf302.lawrag.forum.entity;

/** Lifecycle state tracking whether a post/request has received an official admin reply or resolution. */
public enum ForumRequestStatus {
    OPEN("Chờ phản hồi"),
    ANSWERED("Admin đã trả lời"),
    RESOLVED("Đã giải quyết");

    private final String label;

    ForumRequestStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

