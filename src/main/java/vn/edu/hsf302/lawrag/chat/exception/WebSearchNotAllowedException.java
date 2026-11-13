package vn.edu.hsf302.lawrag.chat.exception;

/** Web search is disabled for this kind of visitor; no consent may be granted. */
public class WebSearchNotAllowedException extends RuntimeException {

    public WebSearchNotAllowedException() {
        super("Tìm kiếm Web hiện không khả dụng cho khách ẩn danh");
    }
}
