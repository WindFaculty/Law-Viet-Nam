package vn.edu.hsf302.lawrag.websearch.entity;

/** Detailed pipeline stage stored on the job for operations and recovery. */
public enum WebSearchJobStage {
    QUEUED,
    CLAIM,
    SEARCH,
    FETCH,
    SNAPSHOT,
    PARSE,
    VALIDATE,
    EMBED,
    PUBLISH,
    RETRIEVE_AGAIN,
    FINALIZE
}
