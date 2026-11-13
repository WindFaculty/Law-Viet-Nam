package vn.edu.hsf302.lawrag.websearch.service;

import java.net.URI;
import java.util.List;

/**
 * Adapter boundary for URL discovery. Snippets and titles are not legal evidence;
 * only the resulting URLs may be fetched after a valid user consent.
 */
public interface WebSearchProvider {

    List<SearchResult> search(String query, int maxResults);

    record SearchResult(URI url, String title, int rank) {

        public SearchResult {
            if (url == null || url.getHost() == null) {
                throw new IllegalArgumentException("search result URL must be absolute");
            }
            rank = rank < 1 ? 1 : rank;
        }
    }
}
