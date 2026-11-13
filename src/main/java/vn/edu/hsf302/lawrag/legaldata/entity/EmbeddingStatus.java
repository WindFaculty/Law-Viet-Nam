package vn.edu.hsf302.lawrag.legaldata.entity;

/** Indexing state of an embedding row. Only READY vectors are searchable. */
public enum EmbeddingStatus {
    PENDING,
    READY,
    STALE,
    FAILED
}
