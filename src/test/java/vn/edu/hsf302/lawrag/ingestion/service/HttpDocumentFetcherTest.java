package vn.edu.hsf302.lawrag.ingestion.service;

import org.junit.jupiter.api.Test;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchErrorCode;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import vn.edu.hsf302.lawrag.ingestion.config.IngestionProperties;

/** SSRF/allowlist/redirect contract from docs/source-policy.md §2–§3. */
class HttpDocumentFetcherTest {

    private static final IngestionProperties PROPERTIES = new IngestionProperties(
            true,
            15_728_640L,
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofSeconds(45),
            3,
            "test-parser",
            List.of("vanban.chinhphu.vn"),
            "LawRAG-test");

    private final SourceAllowlist allowlist = new SourceAllowlist(PROPERTIES);

    @Test
    void rejectsHttpNonAllowlistedAndIpLiteralTargets() {
        HttpDocumentFetcher fetcher = fetcher(publicResolver());

        assertRejected(fetcher, "http://vanban.chinhphu.vn/van-ban", WebSearchErrorCode.ALLOWLIST_REJECTED);
        assertRejected(fetcher, "https://evil.example/van-ban", WebSearchErrorCode.ALLOWLIST_REJECTED);
        assertRejected(fetcher, "https://localhost/van-ban", WebSearchErrorCode.SSRF_BLOCKED);
        assertRejected(fetcher, "https://127.0.0.1/van-ban", WebSearchErrorCode.SSRF_BLOCKED);
        assertRejected(fetcher, "https://169.254.169.254/latest/meta-data", WebSearchErrorCode.SSRF_BLOCKED);
        assertRejected(fetcher, "https://10.0.0.1/van-ban", WebSearchErrorCode.SSRF_BLOCKED);
        assertRejected(fetcher, "https://[::1]/van-ban", WebSearchErrorCode.SSRF_BLOCKED);
    }

    @Test
    void rejectsAllowlistedHostWhenDnsPointsToPrivateAddress() {
        HttpDocumentFetcher fetcher = fetcher(host -> List.of(InetAddress.getByName("10.0.0.5")));

        assertRejected(fetcher, "https://vanban.chinhphu.vn/van-ban", WebSearchErrorCode.SSRF_BLOCKED);
    }

    @Test
    void acceptsAnAllowlistedHostResolvingToAPublicAddress() {
        HttpDocumentFetcher fetcher = fetcher(publicResolver());

        assertThatCode(() -> fetcher.validateTarget(URI.create("https://vanban.chinhphu.vn/van-ban")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRedirectDowngradeOutsideHostPrivateIpAndTooManyHops() {
        HttpDocumentFetcher fetcher = fetcher(publicResolver());
        URI current = URI.create("https://vanban.chinhphu.vn/van-ban/a");

        assertThatThrownBy(() -> fetcher.validateRedirect(
                current, "http://vanban.chinhphu.vn/van-ban/b", 1))
                .isInstanceOfSatisfying(DocumentFetchException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(WebSearchErrorCode.SSRF_BLOCKED));
        assertThatThrownBy(() -> fetcher.validateRedirect(
                current, "https://evil.example/van-ban/b", 1))
                .isInstanceOfSatisfying(DocumentFetchException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(WebSearchErrorCode.ALLOWLIST_REJECTED));
        assertThatThrownBy(() -> fetcher.validateRedirect(
                current, "https://127.0.0.1/van-ban/b", 1))
                .isInstanceOfSatisfying(DocumentFetchException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(WebSearchErrorCode.SSRF_BLOCKED));
        assertThatThrownBy(() -> fetcher.validateRedirect(
                current, "https://vanban.chinhphu.vn/van-ban/b", 4))
                .isInstanceOfSatisfying(DocumentFetchException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(WebSearchErrorCode.SSRF_BLOCKED));
    }

    @Test
    void publicAddressClassifierBlocksMetadataAndNonPublicRanges() throws Exception {
        assertThat(HttpDocumentFetcher.isPublicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(HttpDocumentFetcher.isPublicAddress(InetAddress.getByName("2001:4860:4860::8888"))).isTrue();
        assertThat(HttpDocumentFetcher.isPublicAddress(InetAddress.getByName("0.0.0.0"))).isFalse();
        assertThat(HttpDocumentFetcher.isPublicAddress(InetAddress.getByName("10.0.0.1"))).isFalse();
        assertThat(HttpDocumentFetcher.isPublicAddress(InetAddress.getByName("172.16.0.1"))).isFalse();
        assertThat(HttpDocumentFetcher.isPublicAddress(InetAddress.getByName("192.168.1.1"))).isFalse();
        assertThat(HttpDocumentFetcher.isPublicAddress(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(HttpDocumentFetcher.isPublicAddress(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(HttpDocumentFetcher.isPublicAddress(InetAddress.getByName("::1"))).isFalse();
        assertThat(HttpDocumentFetcher.isPublicAddress(InetAddress.getByName("fc00::1"))).isFalse();
        assertThat(HttpDocumentFetcher.isPublicAddress(InetAddress.getByName("fe80::1"))).isFalse();
    }

    private HttpDocumentFetcher fetcher(HostAddressResolver resolver) {
        return new HttpDocumentFetcher(PROPERTIES, allowlist, resolver);
    }

    private static HostAddressResolver publicResolver() {
        return host -> List.of(InetAddress.getByName("93.184.216.34"));
    }

    private static void assertRejected(HttpDocumentFetcher fetcher, String url,
                                       WebSearchErrorCode expected) {
        assertThatThrownBy(() -> fetcher.validateTarget(URI.create(url)))
                .isInstanceOfSatisfying(DocumentFetchException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
