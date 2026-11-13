package vn.edu.hsf302.lawrag;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate G1 evidence: real context startup, Flyway + pgvector, health, chat page,
 * CSRF, admin authorization, request correlation and anonymous identity.
 */
class G1AcceptanceTest extends PostgresIntegrationTest {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayMigrationRanAndPgvectorIsInstalled() {
        Boolean vectorInstalled = jdbcTemplate.queryForObject(
                "select exists (select 1 from pg_extension where extname = 'vector')", Boolean.class);
        assertThat(vectorInstalled).isTrue();

        Integer appliedV1 = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = true", Integer.class);
        assertThat(appliedV1).isEqualTo(1);
    }

    @Test
    void actuatorHealthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void chatPageRendersWithIdentityHealthHtmxAndCsrf() throws Exception {
        MvcResult result = mockMvc.perform(get("/chat"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Khung chat")))
                .andExpect(content().string(containsString("Khách ẩn danh")))
                .andExpect(content().string(containsString("/js/htmx.min.js")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("Hệ thống:")))
                .andExpect(content().string(containsString("UP")))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(cookie().exists("anon_id"))
                .andExpect(cookie().httpOnly("anon_id", true))
                .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains("SameSite=Lax")
                .doesNotContain("Secure");
    }

    @Test
    void vendoredHtmxAssetIsPublic() throws Exception {
        mockMvc.perform(get("/js/htmx.min.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("htmx")));
    }

    @Test
    void postWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/chat/skeleton").param("message", "thử"))
                .andExpect(status().isForbidden());
    }

    @Test
    void htmxRequestWithoutCsrfTokenGetsAnHtmlFragment() throws Exception {
        mockMvc.perform(post("/api/chat/skeleton")
                        .header("HX-Request", "true")
                        .param("message", "thử"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("data-error=\"access-denied\"")))
                .andExpect(content().string(containsString("Mã yêu cầu")));
    }

    @Test
    void postWithCsrfTokenReturnsSkeletonFragment() throws Exception {
        mockMvc.perform(post("/api/chat/skeleton")
                        .with(csrf())
                        .param("message", "Người đi xe máy có phải đội mũ bảo hiểm không?"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Khung chat đã sẵn sàng")))
                .andExpect(content().string(containsString("Người đi xe máy có phải đội mũ bảo hiểm không?")));
    }

    @Test
    void adminRoutesRequireRoleAdmin() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin").with(user("normal").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quản trị hệ thống")));
    }

    @Test
    void validIncomingRequestIdIsEchoed() throws Exception {
        mockMvc.perform(get("/chat").header("X-Request-Id", "test-request-1234"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "test-request-1234"));
    }

    @Test
    void unsafeIncomingRequestIdIsReplaced() throws Exception {
        MvcResult result = mockMvc.perform(get("/chat").header("X-Request-Id", "bad<script>"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN.pattern())))
                .andReturn();

        String responseId = result.getResponse().getHeader("X-Request-Id");
        assertThat(responseId).isNotEqualTo("bad<script>");
        assertThat(UUID.fromString(responseId)).isNotNull();
        assertThat(responseId).doesNotContain("<");
    }

    @Test
    void invalidAnonymousCookieIsReplacedWithHttpOnlySameSiteCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/chat").cookie(new Cookie("anon_id", "not-a-uuid")))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("anon_id"))
                .andExpect(cookie().httpOnly("anon_id", true))
                .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("SameSite=Lax");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Khách ẩn danh");
    }
}
