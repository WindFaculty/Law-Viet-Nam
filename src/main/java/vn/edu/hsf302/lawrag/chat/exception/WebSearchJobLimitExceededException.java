package vn.edu.hsf302.lawrag.chat.exception;

/** The daily cap on confirmed web jobs (including failed jobs) is exhausted. */
public class WebSearchJobLimitExceededException extends RuntimeException {

    public WebSearchJobLimitExceededException(int limit) {
        super("Đã đạt giới hạn " + limit + " job tìm Web trong ngày");
    }
}
