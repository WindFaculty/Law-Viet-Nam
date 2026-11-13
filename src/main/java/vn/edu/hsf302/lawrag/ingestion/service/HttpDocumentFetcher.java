package vn.edu.hsf302.lawrag.ingestion.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchErrorCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import vn.edu.hsf302.lawrag.ingestion.config.IngestionProperties;

/**
 * HTTPS-only fetcher for the four allowlisted hosts. Redirects are followed
 * manually (max 3), each hop revalidates scheme/host/port/DNS/IP, private and
 * metadata addresses are rejected, and the body is capped while streaming.
 */
@Component
@ConditionalOnProperty(prefix = "app.ingestion", name = "enabled", havingValue = "true")
public class HttpDocumentFetcher implements DocumentFetcher {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "text/html", "application/xhtml+xml", "application/pdf", "text/plain");

    private final IngestionProperties properties;
    private final SourceAllowlist allowlist;
    private final HostAddressResolver addressResolver;
    private final HttpClient httpClient;

    public HttpDocumentFetcher(IngestionProperties properties,
                               SourceAllowlist allowlist,
                               HostAddressResolver addressResolver) {
        this.properties = properties;
        this.allowlist = allowlist;
        this.addressResolver = addressResolver;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new NoProxySelector())
                .build();
    }

    @Override
    public FetchedDocument fetch(URI url) {
        URI current;
        try {
            current = allowlist.canonicalize(url);
        } catch (RuntimeException exception) {
            throw new DocumentFetchException(WebSearchErrorCode.ALLOWLIST_REJECTED,
                    "cannot canonicalize URL", exception);
        }
        Instant deadline = Instant.now().plus(properties.totalTimeout());
        for (int hop = 0; hop <= properties.maxRedirects(); hop++) {
            if (Instant.now().isAfter(deadline)) {
                throw new DocumentFetchException(WebSearchErrorCode.FETCH_TIMEOUT,
                        "total fetch deadline exceeded");
            }
            validateTarget(current);

            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(properties.readTimeout())
                    .header("User-Agent", properties.userAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/pdf,text/plain")
                    .GET()
                    .build();

            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException exception) {
                throw new DocumentFetchException(WebSearchErrorCode.FETCH_TIMEOUT,
                        "fetch timed out: " + current, exception);
            } catch (IOException exception) {
                throw new DocumentFetchException(WebSearchErrorCode.FETCH_FAILED,
                        "fetch failed: " + current, exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new DocumentFetchException(WebSearchErrorCode.FETCH_FAILED,
                        "fetch interrupted: " + current, exception);
            }

            int status = response.statusCode();
            if (isRedirect(status)) {
                if (hop == properties.maxRedirects()) {
                    closeQuietly(response.body());
                    throw new DocumentFetchException(WebSearchErrorCode.SSRF_BLOCKED,
                            "redirect limit exceeded");
                }
                String location = response.headers().firstValue("Location").orElse(null);
                closeQuietly(response.body());
                if (location == null || location.isBlank()) {
                    throw new DocumentFetchException(WebSearchErrorCode.SSRF_BLOCKED,
                            "redirect without Location");
                }
                current = validateRedirect(current, location, hop + 1);
                continue;
            }

            if (status == 429) {
                closeQuietly(response.body());
                throw new DocumentFetchException(WebSearchErrorCode.PROVIDER_RATE_LIMITED,
                        "source rate limited: HTTP 429");
            }
            if (status >= 500) {
                closeQuietly(response.body());
                throw new DocumentFetchException(WebSearchErrorCode.PROVIDER_5XX,
                        "source returned HTTP " + status);
            }
            if (status != 200) {
                closeQuietly(response.body());
                throw new DocumentFetchException(WebSearchErrorCode.FETCH_FAILED,
                        "source returned HTTP " + status);
            }

            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (contentLength > properties.maxBytes()) {
                closeQuietly(response.body());
                throw new DocumentFetchException(WebSearchErrorCode.FETCH_FAILED,
                        "response exceeds size cap");
            }
            byte[] body = readLimited(response.body());
            String contentType = normalizeContentType(
                    response.headers().firstValue("Content-Type").orElse(""), current, body);
            return new FetchedDocument(current, contentType, body, Instant.now());
        }
        throw new DocumentFetchException(WebSearchErrorCode.SSRF_BLOCKED, "redirect limit exceeded");
    }

    void validateTarget(URI uri) {
        String rejection = allowlist.rejectionReason(uri, false);
        if (rejection != null) {
            WebSearchErrorCode code = rejection.contains("IP_LITERAL")
                    ? WebSearchErrorCode.SSRF_BLOCKED
                    : WebSearchErrorCode.ALLOWLIST_REJECTED;
            throw new DocumentFetchException(code, "URL rejected: " + rejection);
        }
        List<InetAddress> addresses;
        try {
            addresses = addressResolver.resolve(uri.getHost());
        } catch (UnknownHostException exception) {
            throw new DocumentFetchException(WebSearchErrorCode.SSRF_BLOCKED,
                    "DNS resolution failed", exception);
        }
        if (addresses.isEmpty()) {
            throw new DocumentFetchException(WebSearchErrorCode.SSRF_BLOCKED, "DNS returned no address");
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new DocumentFetchException(WebSearchErrorCode.SSRF_BLOCKED,
                        "DNS points to a non-public address: " + address.getHostAddress());
            }
        }
    }

    URI validateRedirect(URI current, String location, int hop) {
        if (hop > properties.maxRedirects()) {
            throw new DocumentFetchException(WebSearchErrorCode.SSRF_BLOCKED, "redirect limit exceeded");
        }
        URI next;
        try {
            next = current.resolve(location.strip());
        } catch (IllegalArgumentException exception) {
            throw new DocumentFetchException(WebSearchErrorCode.SSRF_BLOCKED, "invalid redirect", exception);
        }
        if (next.getScheme() == null || !"https".equalsIgnoreCase(next.getScheme())) {
            throw new DocumentFetchException(WebSearchErrorCode.SSRF_BLOCKED,
                    "redirect downgrade is not allowed");
        }
        validateTarget(next);
        return allowlist.canonicalize(next);
    }

    private byte[] readLimited(InputStream input) {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > properties.maxBytes()) {
                    throw new DocumentFetchException(WebSearchErrorCode.FETCH_FAILED,
                            "response exceeds size cap");
                }
                output.write(buffer, 0, read);
            }
            byte[] body = output.toByteArray();
            if (body.length == 0) {
                throw new DocumentFetchException(WebSearchErrorCode.FETCH_FAILED, "empty response");
            }
            return body;
        } catch (IOException exception) {
            throw new DocumentFetchException(WebSearchErrorCode.FETCH_FAILED,
                    "cannot read response body", exception);
        }
    }

    private static String normalizeContentType(String raw, URI uri, byte[] body) {
        String mediaType = raw == null
                ? ""
                : raw.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (mediaType.equals("application/octet-stream") && looksLikePdf(body)
                && uri.getPath() != null && uri.getPath().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return "application/pdf";
        }
        if (!ALLOWED_CONTENT_TYPES.contains(mediaType)) {
            throw new DocumentFetchException(WebSearchErrorCode.FETCH_FAILED,
                    "content type is not allowed: " + mediaType);
        }
        return mediaType;
    }

    private static boolean looksLikePdf(byte[] body) {
        return body.length >= 4 && body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F';
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The response is already discarded.
        }
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isPublicIpv4(bytes);
        }
        if (bytes.length == 16) {
            if (isIpv4Mapped(bytes)) {
                return isPublicIpv4(Arrays.copyOfRange(bytes, 12, 16));
            }
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return false;
            }
            if (first == 0xfe && (bytes[1] & 0xc0) == 0x80) {
                return false;
            }
            return first != 0 && first != 0xff;
        }
        return false;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        int a = bytes[0] & 0xff;
        int b = bytes[1] & 0xff;
        int c = bytes[2] & 0xff;
        if (a == 0 || a == 10 || a == 127) {
            return false;
        }
        if (a == 100 && b >= 64 && b <= 127) {
            return false;
        }
        if (a == 169 && b == 254) {
            return false;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return false;
        }
        if (a == 192 && b == 168) {
            return false;
        }
        if (a == 192 && b == 0 && (c == 0 || c == 2)) {
            return false;
        }
        if (a == 198 && (b == 18 || b == 19)) {
            return false;
        }
        if (a == 198 && b == 51 && c == 100) {
            return false;
        }
        if (a == 203 && b == 0 && c == 113) {
            return false;
        }
        return a < 224;
    }

    private static final class NoProxySelector extends ProxySelector {

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // No proxy is used.
        }
    }
}
