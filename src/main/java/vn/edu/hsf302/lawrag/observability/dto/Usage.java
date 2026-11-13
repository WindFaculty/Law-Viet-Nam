package vn.edu.hsf302.lawrag.observability.dto;

/**
 * One increment of measured provider work. All values are resource units
 * (calls, characters, estimated tokens, bytes), never invented currency.
 */
public record Usage(
        int embeddingCalls,
        long embeddingInputChars,
        int embeddingCacheHits,
        int groundingCalls,
        long groundingInputTokens,
        long groundingOutputTokens,
        int webSearchCalls,
        int fetchedUrls,
        long downloadedBytes) {

    public static final Usage NONE = new Usage(0, 0, 0, 0, 0, 0, 0, 0, 0);

    public static Usage embedding(long inputChars) {
        return new Usage(1, Math.max(0, inputChars), 0, 0, 0, 0, 0, 0, 0);
    }

    public static Usage embeddingCacheHit(long inputChars) {
        return new Usage(0, 0, 1, 0, 0, 0, 0, 0, 0);
    }

    public static Usage grounding(long inputTokens, long outputTokens) {
        return new Usage(0, 0, 0, 1, Math.max(0, inputTokens), Math.max(0, outputTokens), 0, 0, 0);
    }

    public static Usage webSearch(int searchCalls, int fetchedUrls, long downloadedBytes) {
        return new Usage(0, 0, 0, 0, 0, 0,
                Math.max(0, searchCalls), Math.max(0, fetchedUrls), Math.max(0, downloadedBytes));
    }

    public Usage plus(Usage other) {
        if (other == null) {
            return this;
        }
        return new Usage(
                embeddingCalls + other.embeddingCalls,
                embeddingInputChars + other.embeddingInputChars,
                embeddingCacheHits + other.embeddingCacheHits,
                groundingCalls + other.groundingCalls,
                groundingInputTokens + other.groundingInputTokens,
                groundingOutputTokens + other.groundingOutputTokens,
                webSearchCalls + other.webSearchCalls,
                fetchedUrls + other.fetchedUrls,
                downloadedBytes + other.downloadedBytes);
    }

    public boolean isEmpty() {
        return embeddingCalls == 0 && embeddingInputChars == 0 && embeddingCacheHits == 0
                && groundingCalls == 0 && groundingInputTokens == 0 && groundingOutputTokens == 0
                && webSearchCalls == 0 && fetchedUrls == 0 && downloadedBytes == 0;
    }
}
