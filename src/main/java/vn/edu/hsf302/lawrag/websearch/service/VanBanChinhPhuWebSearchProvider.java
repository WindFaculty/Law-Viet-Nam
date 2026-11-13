package vn.edu.hsf302.lawrag.websearch.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import vn.edu.hsf302.lawrag.ingestion.service.SourceAllowlist;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchErrorCode;
import vn.edu.hsf302.lawrag.websearch.config.WebSearchProperties;

/**
 * Site-native adapter for the public search form of vanban.chinhphu.vn.
 * Only URLs are returned; titles/snippets are never treated as legal evidence.
 * The form is ASP.NET/WebForms and may change, so the provider is replaceable
 * and a provider failure never silently switches to another host.
 */
@Component
@ConditionalOnExpression("${app.websearch.enabled:false} && '${app.websearch.provider:vanban}' == 'vanban'")
public class VanBanChinhPhuWebSearchProvider implements WebSearchProvider {

    private static final String SEARCH_ENDPOINT =
            "https://vanban.chinhphu.vn/tim-van-ban.aspx?keyword=";

    private final SourceAllowlist allowlist;
    private final WebSearchProperties properties;
    private final HttpClient httpClient;

    public VanBanChinhPhuWebSearchProvider(SourceAllowlist allowlist, WebSearchProperties properties) {
        this.allowlist = allowlist;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // The search endpoint is fixed; a redirect is treated as a provider
                // failure rather than followed to an unvalidated host.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        URI searchUri = URI.create(SEARCH_ENDPOINT
                + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(searchUri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", properties.userAgent())
                .header("Accept", "text/html")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException exception) {
            throw new WebSearchProviderException(WebSearchErrorCode.PROVIDER_TIMEOUT,
                    "search provider timed out", exception);
        } catch (IOException exception) {
            throw new WebSearchProviderException(WebSearchErrorCode.PROVIDER_5XX,
                    "search provider I/O failure", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WebSearchProviderException(WebSearchErrorCode.PROVIDER_5XX,
                    "search provider interrupted", exception);
        }

        if (response.statusCode() == 429) {
            throw new WebSearchProviderException(WebSearchErrorCode.PROVIDER_RATE_LIMITED,
                    "search provider rate limited");
        }
        if (response.statusCode() >= 500) {
            throw new WebSearchProviderException(WebSearchErrorCode.PROVIDER_5XX,
                    "search provider returned HTTP " + response.statusCode());
        }
        if (response.statusCode() != 200) {
            throw new WebSearchProviderException(WebSearchErrorCode.PROVIDER_5XX,
                    "search provider returned HTTP " + response.statusCode());
        }

        Document document = Jsoup.parse(response.body(), searchUri.toString());
        Map<String, SearchResult> unique = new LinkedHashMap<>();
        int limit = Math.min(Math.max(maxResults, 1), properties.maxResults());
        for (Element anchor : document.select("a[href]")) {
            if (unique.size() >= limit) {
                break;
            }
            URI candidate;
            try {
                candidate = allowlist.canonicalize(searchUri.resolve(anchor.attr("href")));
            } catch (RuntimeException exception) {
                continue;
            }
            if (!allowlist.isSearchResultAllowed(candidate) || unique.containsKey(candidate.toString())) {
                continue;
            }
            unique.put(candidate.toString(),
                    new SearchResult(candidate, anchor.text().strip(), unique.size() + 1));
        }
        return List.copyOf(new ArrayList<>(unique.values()));
    }
}
