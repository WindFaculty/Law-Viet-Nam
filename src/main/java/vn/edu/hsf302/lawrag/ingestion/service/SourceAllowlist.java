package vn.edu.hsf302.lawrag.ingestion.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import vn.edu.hsf302.lawrag.ingestion.config.IngestionProperties;

/**
 * Exact-host allowlist from docs/source-policy.md §2. No wildcard, no suffix
 * match, no host inferred from model output.
 */
@Component
public class SourceAllowlist {

    /** Hosts a document fetcher may connect to. */
    public static final Set<String> FETCH_HOSTS = Set.of(
            "vanban.chinhphu.vn",
            "vbpl.vn",
            "congbao.chinhphu.vn",
            "datafiles.chinhphu.vn");

    /** Hosts allowed to appear as a provider discovery result. */
    public static final Set<String> SEARCH_RESULT_HOSTS = Set.of(
            "vanban.chinhphu.vn",
            "vbpl.vn",
            "congbao.chinhphu.vn");

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "fbclid", "gclid", "ref", "source");

    private final IngestionProperties properties;

    public SourceAllowlist(IngestionProperties properties) {
        this.properties = properties;
    }

    /**
     * @return a stable rejection code, or {@code null} when the URL is allowed
     */
    public String rejectionReason(URI uri, boolean searchResult) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return "MALFORMED_URL";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "SCHEME_NOT_HTTPS";
        }
        if (uri.getUserInfo() != null) {
            return "USERINFO_NOT_ALLOWED";
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return "PORT_NOT_ALLOWED";
        }
        String host = normalizeHost(uri.getHost());
        if (host.isEmpty() || host.contains(" ") || host.startsWith("[") || host.contains(":")) {
            return "IP_LITERAL_OR_INVALID_HOST";
        }
        if (isNumericHost(host) || host.equals("localhost") || host.endsWith(".localhost")) {
            return "IP_LITERAL_OR_INVALID_HOST";
        }
        if (host.equals("www.vbpl.vn")) {
            return searchResult ? null : "ALIAS_NOT_FETCHABLE";
        }
        Set<String> allowed = searchResult ? SEARCH_RESULT_HOSTS : FETCH_HOSTS;
        return allowed.contains(host) ? null : "HOST_NOT_ALLOWED";
    }

    public boolean isAllowed(URI uri) {
        return rejectionReason(uri, false) == null;
    }

    public boolean isSearchResultAllowed(URI uri) {
        return rejectionReason(uri, true) == null;
    }

    public boolean isAutoPublishHost(String host) {
        String normalized = normalizeHost(host);
        return properties.autoPublishHosts().stream()
                .map(SourceAllowlist::normalizeHost)
                .anyMatch(normalized::equals);
    }

    /** Lowercase scheme/host, drop fragment and tracking parameters. */
    public URI canonicalize(URI uri) {
        if (uri == null || uri.getHost() == null) {
            throw new IllegalArgumentException("URL must be absolute and have a host");
        }
        String host = normalizeHost(uri.getHost());
        if (host.equals("www.vbpl.vn")) {
            host = "vbpl.vn";
        }
        String query = canonicalQuery(uri.getRawQuery());
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String rebuilt = "https://" + host + path + (query == null ? "" : "?" + query);
        try {
            return new URI(rebuilt);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("cannot canonicalize URL", exception);
        }
    }

    private static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            String key = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (normalizedKey.startsWith("utm_") || TRACKING_PARAMS.contains(normalizedKey)) {
                continue;
            }
            kept.add(pair);
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }

    private static boolean isNumericHost(String host) {
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return true;
        }
        return host.matches("0[xX][0-9a-fA-F]+") || host.matches("\\d+");
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
