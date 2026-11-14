package vn.edu.hsf302.lawrag;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.hsf302.lawrag.chat.entity.ChatSession;
import vn.edu.hsf302.lawrag.chat.service.ChatSessionService;
import vn.edu.hsf302.lawrag.chat.service.MessageStateService;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;
import vn.edu.hsf302.lawrag.chat.service.MessageSubmissionService;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.ingestion.service.CorpusImportService;
import vn.edu.hsf302.lawrag.quota.service.QuotaReservationService;
import vn.edu.hsf302.lawrag.security.web.AnonymousIdentityFilter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate G5 evidence against real PostgreSQL + pgvector: the state-driven chat
 * fragments, consent endpoints, ownership, expiry, idempotency, quota/job
 * limits and reload/polling restoration.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class G5AcceptanceTest extends PostgresIntegrationTest {

    private static final String HELMET_QUESTION =
            "Người điều khiển xe máy có phải đội mũ bảo hiểm không?";
    private static final String PENALTY_QUESTION =
            "Theo Nghị định 238/2026/NĐ-CP, người điều khiển xe máy vượt đèn đỏ bị phạt bao nhiêu tiền?";
    private static final String CLARIFICATION_QUESTION = "Tôi bị phạt bao nhiêu tiền?";
    private static final String CONFIRM_PROMPT =
            "Kho dữ liệu của chúng tôi hiện tại chưa đủ thông tin để trả lời câu hỏi này. "
                    + "Bạn có muốn tra cứu trên Web không?";
    private static final Pattern SESSION_ID = Pattern.compile("data-session-id=\"([0-9a-f-]{36})\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CorpusImportService corpusImportService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private MessageSubmissionService messageSubmissionService;

    @Autowired
    private MessageStateService messageStateService;

    @Autowired
    private QuotaReservationService quotaReservationService;

    @BeforeAll
    void seedCorpusOnce() {
        corpusImportService.importCorpus();
    }

    @Test
    void localAnswerReturnsCompletedFragmentWithCitationLinksAndOneTurn() throws Exception {
        String anonId = newAnonId();
        ChatSession session = chatSessionService.createSession(owner(anonId));

        MvcResult result = mockMvc.perform(question(session.getId(), anonId, "g5-local", HELMET_QUESTION))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"COMPLETED\"")))
                .andExpect(content().string(containsString("Trích dẫn nguyên văn")))
                .andExpect(content().string(containsString("Mở nguyên văn và nguồn")))
                .andExpect(content().string(containsString("/legal/documents/")))
                .andExpect(content().string(not(containsString("Đồng ý tìm trên Web"))))
                .andReturn();
        assertThat(body(result)).doesNotContain("hx-trigger=\"every 2s\"");

        UUID messageId = aiMessageId(session.getId(), HELMET_QUESTION);
        assertThat(messageStatus(messageId)).isEqualTo("COMPLETED");
        assertThat(citationCount(messageId)).isEqualTo(1);
        BucketState bucket = bucket(owner(anonId));
        assertThat(bucket.usedCount()).isEqualTo(1);
        assertThat(bucket.reservedCount()).isZero();

        mockMvc.perform(get("/chat").param("sessionId", session.getId().toString()).cookie(cookie(anonId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(HELMET_QUESTION)))
                .andExpect(content().string(containsString("1 / 5")));
    }

    @Test
    void streamingSubmissionEmitsSseEventsAndFinalizesWithCitationAndQuota() throws Exception {
        String anonId = newAnonId();
        ChatSession session = chatSessionService.createSession(owner(anonId));

        MvcResult submitResult = mockMvc.perform(question(session.getId(), anonId, "g5-stream", HELMET_QUESTION)
                        .header("X-Chat-Stream", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"PROCESSING_LOCAL\"")))
                .andExpect(content().string(containsString("data-stream-url=\"/api/chat/messages/")))
                .andReturn();

        UUID messageId = aiMessageId(session.getId(), HELMET_QUESTION);
        assertThat(body(submitResult)).contains("data-stream-url=\"/api/chat/messages/" + messageId + "/stream\"");

        MvcResult streamStart = mockMvc.perform(get("/api/chat/messages/{id}/stream", messageId)
                        .cookie(cookie(anonId)))
                .andExpect(request().asyncStarted())
                .andReturn();
        streamStart.getAsyncResult(10_000);

        MvcResult streamResult = mockMvc.perform(asyncDispatch(streamStart))
                .andExpect(status().isOk())
                .andReturn();
        String sseBody = streamResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(sseBody).contains("event:stage");
        assertThat(sseBody).contains("event:delta");
        assertThat(sseBody).contains("event:complete");
        assertThat(sseBody).contains("\"status\":\"COMPLETED\"");

        assertThat(messageStatus(messageId)).isEqualTo("COMPLETED");
        assertThat(citationCount(messageId)).isEqualTo(1);
        BucketState bucket = bucket(owner(anonId));
        assertThat(bucket.usedCount()).isEqualTo(1);
        assertThat(bucket.reservedCount()).isZero();

        mockMvc.perform(get("/api/chat/messages/{id}/fragment", messageId).cookie(cookie(anonId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"COMPLETED\"")))
                .andExpect(content().string(containsString("Trích dẫn nguyên văn")));
    }

    @Test
    void repeatedSubmissionWithSameIdempotencyKeyReturnsTheSamePair() throws Exception {
        String anonId = newAnonId();
        ChatSession session = chatSessionService.createSession(owner(anonId));

        mockMvc.perform(question(session.getId(), anonId, "g5-idempotent", HELMET_QUESTION))
                .andExpect(status().isOk());
        UUID messageId = aiMessageId(session.getId(), HELMET_QUESTION);

        mockMvc.perform(question(session.getId(), anonId, "g5-idempotent", HELMET_QUESTION))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"message-" + messageId + "\"")));

        Integer messageCount = jdbcTemplate.queryForObject(
                "select count(*) from chat_messages where session_id = ?", Integer.class, session.getId());
        assertThat(messageCount).isEqualTo(2);
        BucketState bucket = bucket(owner(anonId));
        assertThat(bucket.usedCount()).isEqualTo(1);
        assertThat(bucket.reservedCount()).isZero();
    }

    @Test
    void missingEvidenceReturnsExactPromptWithButtonsAndNoJobOrQuotaUse() throws Exception {
        String anonId = newAnonId();
        ChatSession session = chatSessionService.createSession(owner(anonId));

        MvcResult result = mockMvc.perform(question(session.getId(), anonId, "g5-await", PENALTY_QUESTION))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(CONFIRM_PROMPT)))
                .andExpect(content().string(containsString("Đồng ý tìm trên Web")))
                .andExpect(content().string(containsString("Không cần")))
                .andExpect(content().string(containsString("hx-disabled-elt=\"find button\"")))
                .andExpect(content().string(containsString("name=\"questionHash\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("data-status=\"AWAITING_WEB_CONFIRM\"")))
                .andReturn();
        assertThat(body(result)).doesNotContain("hx-trigger=\"every 2s\"");

        UUID messageId = aiMessageId(session.getId(), PENALTY_QUESTION);
        String questionHash = questionHash(messageId);
        assertThat(questionHash).hasSize(64);
        assertThat(body(result)).contains(
                "hx-post=\"/api/chat/messages/" + messageId + "/confirm-web-search\"",
                "hx-post=\"/api/chat/messages/" + messageId + "/cancel-web-search\"");
        assertThat(((String) messageRow(messageId).get("suggested_query"))).isNotBlank();
        assertThat(jobCount(messageId)).isZero();
        assertThat(reservationStatus(messageId)).isEqualTo("RELEASED");
        BucketState bucket = bucket(owner(anonId));
        assertThat(bucket.usedCount()).isZero();
        assertThat(bucket.reservedCount()).isZero();

        // Reload restores the same confirmation buttons from PostgreSQL.
        mockMvc.perform(get("/chat").param("sessionId", session.getId().toString()).cookie(cookie(anonId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(CONFIRM_PROMPT)))
                .andExpect(content().string(containsString("Đồng ý tìm trên Web")))
                .andExpect(content().string(containsString(questionHash)));
    }

    @Test
    void cancelCreatesNoJobAndConsumesNoQuota() throws Exception {
        String anonId = newAnonId();
        AwaitingMessage awaiting = createAwaiting(anonId, "g5-cancel");

        mockMvc.perform(post("/api/chat/messages/{id}/cancel-web-search", awaiting.messageId())
                        .cookie(cookie(anonId))
                        .with(csrf())
                        .header("HX-Request", "true")
                        .param("questionHash", awaiting.questionHash()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"CANCELLED\"")))
                .andExpect(content().string(containsString("Không có job tìm Web nào được tạo")))
                .andExpect(content().string(not(containsString("hx-trigger=\"every 2s\""))));

        assertThat(messageStatus(awaiting.messageId())).isEqualTo("CANCELLED");
        assertThat(jobCount(awaiting.messageId())).isZero();
        assertThat(activeReservationCount(awaiting.messageId())).isZero();
        BucketState bucket = bucket(owner(anonId));
        assertThat(bucket.usedCount()).isZero();
        assertThat(bucket.reservedCount()).isZero();

        // A second cancel is an idempotent replay, not an error.
        mockMvc.perform(post("/api/chat/messages/{id}/cancel-web-search", awaiting.messageId())
                        .cookie(cookie(anonId))
                        .with(csrf())
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"CANCELLED\"")));
        assertThat(jobCount(awaiting.messageId())).isZero();
    }

    @Test
    void expiredConfirmationTransitionsToExpiredWithoutJobOrQuotaUse() throws Exception {
        String anonId = newAnonId();
        AwaitingMessage awaiting = createAwaiting(anonId, "g5-expired");
        jdbcTemplate.update(
                "update chat_messages set confirm_expires_at = now() - interval '1 minute' where id = ?",
                awaiting.messageId());

        mockMvc.perform(post("/api/chat/messages/{id}/confirm-web-search", awaiting.messageId())
                        .cookie(cookie(anonId))
                        .with(csrf())
                        .header("HX-Request", "true")
                        .param("questionHash", awaiting.questionHash()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"EXPIRED\"")))
                .andExpect(content().string(containsString("hết hạn sau 15 phút")))
                .andExpect(content().string(not(containsString("hx-trigger=\"every 2s\""))));

        assertThat(messageStatus(awaiting.messageId())).isEqualTo("EXPIRED");
        assertThat(jobCount(awaiting.messageId())).isZero();
        assertThat(activeReservationCount(awaiting.messageId())).isZero();
        BucketState bucket = bucket(owner(anonId));
        assertThat(bucket.usedCount()).isZero();
        assertThat(bucket.reservedCount()).isZero();
    }

    @Test
    void pollingFragmentReflectsExpiryWithoutCreatingAJob() throws Exception {
        String anonId = newAnonId();
        AwaitingMessage awaiting = createAwaiting(anonId, "g5-expired-poll");
        jdbcTemplate.update(
                "update chat_messages set confirm_expires_at = now() - interval '1 minute' where id = ?",
                awaiting.messageId());

        mockMvc.perform(get("/api/chat/messages/{id}/fragment", awaiting.messageId())
                        .cookie(cookie(anonId))
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"EXPIRED\"")))
                .andExpect(content().string(not(containsString("hx-trigger=\"every 2s\""))));

        assertThat(messageStatus(awaiting.messageId())).isEqualTo("EXPIRED");
        assertThat(jobCount(awaiting.messageId())).isZero();
        assertThat(bucket(owner(anonId)).reservedCount()).isZero();
    }

    @Test
    void repeatedConfirmCreatesExactlyOneJobAndOneReservation() throws Exception {
        String anonId = newAnonId();
        AwaitingMessage awaiting = createAwaiting(anonId, "g5-repeat-confirm");

        confirm(awaiting, anonId)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"WEB_QUEUED\"")))
                .andExpect(content().string(containsString("hx-trigger=\"every 2s\"")))
                .andExpect(content().string(containsString("hx-swap=\"outerHTML\"")));
        confirm(awaiting, anonId)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"WEB_QUEUED\"")));

        assertThat(messageStatus(awaiting.messageId())).isEqualTo("WEB_QUEUED");
        assertThat(jobCount(awaiting.messageId())).isEqualTo(1);
        assertThat(activeReservationCount(awaiting.messageId())).isEqualTo(1);
        BucketState bucket = bucket(owner(anonId));
        assertThat(bucket.reservedCount()).isEqualTo(1);
        assertThat(bucket.usedCount()).isZero();
    }

    @Test
    void concurrentConfirmCreatesAtMostOneJobAndOneReservation() throws Exception {
        String anonId = newAnonId();
        AwaitingMessage awaiting = createAwaiting(anonId, "g5-concurrent-confirm");

        List<Callable<Integer>> calls = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            calls.add(() -> confirm(awaiting, anonId).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = runConcurrently(calls);

        assertThat(statuses).allMatch(status -> status == 200);
        assertThat(messageStatus(awaiting.messageId())).isEqualTo("WEB_QUEUED");
        assertThat(jobCount(awaiting.messageId())).isEqualTo(1);
        assertThat(activeJobCount(awaiting.messageId())).isEqualTo(1);
        assertThat(activeReservationCount(awaiting.messageId())).isEqualTo(1);
        assertThat(bucket(owner(anonId)).reservedCount()).isEqualTo(1);
    }

    @Test
    void concurrentConfirmAndCancelHaveExactlyOneWinner() throws Exception {
        String anonId = newAnonId();
        AwaitingMessage awaiting = createAwaiting(anonId, "g5-confirm-cancel-race");

        List<Callable<Integer>> calls = List.of(
                () -> confirm(awaiting, anonId).andReturn().getResponse().getStatus(),
                () -> mockMvc.perform(post("/api/chat/messages/{id}/cancel-web-search",
                                awaiting.messageId())
                                .cookie(cookie(anonId))
                                .with(csrf())
                                .header("HX-Request", "true"))
                        .andReturn().getResponse().getStatus());
        List<Integer> statuses = runConcurrently(calls);

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        String finalStatus = messageStatus(awaiting.messageId());
        BucketState bucket = bucket(owner(anonId));
        if ("WEB_QUEUED".equals(finalStatus)) {
            assertThat(jobCount(awaiting.messageId())).isEqualTo(1);
            assertThat(bucket.reservedCount()).isEqualTo(1);
            assertThat(activeReservationCount(awaiting.messageId())).isEqualTo(1);
        } else {
            assertThat(finalStatus).isEqualTo("CANCELLED");
            assertThat(jobCount(awaiting.messageId())).isZero();
            assertThat(bucket.reservedCount()).isZero();
            assertThat(activeReservationCount(awaiting.messageId())).isZero();
        }
    }

    @Test
    void browserSuppliedQueryAndUrlAreIgnoredAndHashIsValidated() throws Exception {
        String anonId = newAnonId();
        AwaitingMessage awaiting = createAwaiting(anonId, "g5-malicious-params");
        String storedQuery = (String) messageRow(awaiting.messageId()).get("suggested_query");

        mockMvc.perform(post("/api/chat/messages/{id}/confirm-web-search", awaiting.messageId())
                        .cookie(cookie(anonId))
                        .with(csrf())
                        .header("HX-Request", "true")
                        .param("questionHash", "0".repeat(64))
                        .param("query", "query-do-trinh-duyet-gui")
                        .param("url", "http://attacker.invalid/steal")
                        .param("suggestedQuery", "query-do-trinh-duyet-gui"))
                .andExpect(status().isConflict());

        assertThat(jobCount(awaiting.messageId())).isZero();
        assertThat(messageStatus(awaiting.messageId())).isEqualTo("AWAITING_WEB_CONFIRM");

        mockMvc.perform(post("/api/chat/messages/{id}/confirm-web-search", awaiting.messageId())
                        .cookie(cookie(anonId))
                        .with(csrf())
                        .header("HX-Request", "true")
                        .param("questionHash", awaiting.questionHash())
                        .param("query", "query-do-trinh-duyet-gui")
                        .param("url", "http://attacker.invalid/steal")
                        .param("suggestedQuery", "query-do-trinh-duyet-gui"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"WEB_QUEUED\"")))
                .andExpect(content().string(not(containsString("attacker.invalid"))));

        assertThat(jobCount(awaiting.messageId())).isEqualTo(1);
        assertThat(messageRow(awaiting.messageId()).get("suggested_query")).isEqualTo(storedQuery);
        assertThat(storedQuery).isNotEqualTo("query-do-trinh-duyet-gui");
    }

    @Test
    void crossSessionAccessIsForbiddenOnSubmitConfirmCancelAndFragment() throws Exception {
        String ownerAnon = newAnonId();
        String intruderAnon = newAnonId();
        AwaitingMessage awaiting = createAwaiting(ownerAnon, "g5-cross-owner");

        mockMvc.perform(question(awaiting.sessionId(), intruderAnon, "g5-intruder-submit", "Câu hỏi khác")
                        .header("HX-Request", "true"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/chat/messages/{id}/confirm-web-search", awaiting.messageId())
                        .cookie(cookie(intruderAnon))
                        .with(csrf())
                        .header("HX-Request", "true")
                        .param("questionHash", awaiting.questionHash()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("tải lại trang chat")));

        mockMvc.perform(post("/api/chat/messages/{id}/cancel-web-search", awaiting.messageId())
                        .cookie(cookie(intruderAnon))
                        .with(csrf())
                        .header("HX-Request", "true"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/chat/messages/{id}/fragment", awaiting.messageId())
                        .cookie(cookie(intruderAnon))
                        .header("HX-Request", "true"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/chat/messages/{id}/retry-web-search", awaiting.messageId())
                        .cookie(cookie(intruderAnon))
                        .with(csrf())
                        .header("HX-Request", "true"))
                .andExpect(status().isForbidden());

        assertThat(messageStatus(awaiting.messageId())).isEqualTo("AWAITING_WEB_CONFIRM");
        assertThat(jobCount(awaiting.messageId())).isZero();
        assertThat(bucket(owner(ownerAnon)).usedCount()).isZero();
        assertThat(bucket(owner(ownerAnon)).reservedCount()).isZero();
    }

    @Test
    void missingCsrfOnConfirmIsRejectedWithHtmxReloadGuidance() throws Exception {
        String anonId = newAnonId();
        AwaitingMessage awaiting = createAwaiting(anonId, "g5-no-csrf");

        mockMvc.perform(post("/api/chat/messages/{id}/confirm-web-search", awaiting.messageId())
                        .cookie(cookie(anonId))
                        .header("HX-Request", "true")
                        .param("questionHash", awaiting.questionHash()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("data-error=\"access-denied\"")))
                .andExpect(content().string(containsString("tải lại trang")));

        assertThat(jobCount(awaiting.messageId())).isZero();
        assertThat(messageStatus(awaiting.messageId())).isEqualTo("AWAITING_WEB_CONFIRM");
    }

    @Test
    void reloadAndPollingRestoreHistoryAndStopPollingWhenTerminal() throws Exception {
        String anonId = newAnonId();
        AwaitingMessage awaiting = createAwaiting(anonId, "g5-reload-poll");
        confirm(awaiting, anonId).andExpect(status().isOk());

        mockMvc.perform(get("/chat")
                        .param("sessionId", awaiting.sessionId().toString())
                        .cookie(cookie(anonId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-session-id=\"" + awaiting.sessionId() + "\"")))
                .andExpect(content().string(containsString(PENALTY_QUESTION)))
                .andExpect(content().string(containsString("hx-trigger=\"every 2s\"")))
                .andExpect(content().string(containsString(
                        "/api/chat/messages/" + awaiting.messageId() + "/fragment")))
                .andExpect(content().string(containsString("hx-swap=\"outerHTML\"")))
                .andExpect(content().string(containsString("Lượt hôm nay:")))
                .andExpect(content().string(containsString("0 / 5")));

        mockMvc.perform(get("/api/chat/messages/{id}/fragment", awaiting.messageId())
                        .cookie(cookie(anonId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hx-trigger=\"every 2s\"")))
                .andExpect(content().string(containsString("data-status=\"WEB_QUEUED\"")));

        messageStateService.transition(awaiting.messageId(), owner(anonId), MessageStatus.WEB_SEARCHING);
        mockMvc.perform(get("/api/chat/messages/{id}/fragment", awaiting.messageId())
                        .cookie(cookie(anonId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"WEB_SEARCHING\"")))
                .andExpect(content().string(containsString("hx-trigger=\"every 2s\"")));
        jdbcTemplate.update("update web_search_jobs set job_status = 'SUCCEEDED' where message_id = ?",
                awaiting.messageId());
        messageStateService.complete(awaiting.messageId(), owner(anonId), "Câu trả lời đã lưu.");

        mockMvc.perform(get("/api/chat/messages/{id}/fragment", awaiting.messageId())
                        .cookie(cookie(anonId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"COMPLETED\"")))
                .andExpect(content().string(containsString("Câu trả lời đã lưu.")))
                .andExpect(content().string(not(containsString("hx-trigger=\"every 2s\""))));

        mockMvc.perform(get("/chat")
                        .param("sessionId", awaiting.sessionId().toString())
                        .cookie(cookie(anonId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"COMPLETED\"")))
                .andExpect(content().string(containsString("1 / 5")))
                .andExpect(content().string(not(containsString("hx-trigger=\"every 2s\""))));
    }

    @Test
    void processingLocalNeedsClarificationFailedAndNoEvidenceFragmentsAreStateAppropriate() throws Exception {
        String processingAnon = newAnonId();
        ChatSession processingSession = chatSessionService.createSession(owner(processingAnon));
        UUID processingId = messageSubmissionService.submitQuestion(
                        processingSession.getId(), owner(processingAnon), "g5-processing",
                        PENALTY_QUESTION)
                .aiMessage().getId();
        mockMvc.perform(get("/api/chat/messages/{id}/fragment", processingId).cookie(cookie(processingAnon)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"PROCESSING_LOCAL\"")))
                .andExpect(content().string(containsString("Đang tìm trong kho nội bộ")))
                .andExpect(content().string(containsString("hx-trigger=\"every 2s\"")));

        String clarificationAnon = newAnonId();
        ChatSession clarificationSession = chatSessionService.createSession(owner(clarificationAnon));
        mockMvc.perform(question(clarificationSession.getId(), clarificationAnon,
                        "g5-clarification", CLARIFICATION_QUESTION))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"NEEDS_CLARIFICATION\"")))
                .andExpect(content().string(containsString("Hãy bổ sung dữ kiện")))
                .andExpect(content().string(not(containsString("hx-trigger=\"every 2s\""))));

        String failedAnon = newAnonId();
        ChatSession failedSession = chatSessionService.createSession(owner(failedAnon));
        UUID failedId = messageSubmissionService.submitQuestion(
                        failedSession.getId(), owner(failedAnon), "g5-failed",
                        PENALTY_QUESTION)
                .aiMessage().getId();
        messageStateService.transition(failedId, owner(failedAnon), MessageStatus.FAILED);
        mockMvc.perform(get("/api/chat/messages/{id}/fragment", failedId).cookie(cookie(failedAnon)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"FAILED\"")))
                .andExpect(content().string(containsString("lỗi xử lý")))
                .andExpect(content().string(not(containsString("hx-trigger=\"every 2s\""))));

        String noEvidenceAnon = newAnonId();
        AwaitingMessage noEvidence = createAwaiting(noEvidenceAnon, "g5-no-evidence");
        messageStateService.confirmWebSearch(noEvidence.messageId(), owner(noEvidenceAnon));
        messageStateService.transition(noEvidence.messageId(), owner(noEvidenceAnon),
                MessageStatus.WEB_SEARCHING);
        messageStateService.transition(noEvidence.messageId(), owner(noEvidenceAnon),
                MessageStatus.NO_EVIDENCE);
        mockMvc.perform(get("/api/chat/messages/{id}/fragment", noEvidence.messageId())
                        .cookie(cookie(noEvidenceAnon)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"NO_EVIDENCE\"")))
                .andExpect(content().string(containsString("chưa đủ căn cứ")))
                .andExpect(content().string(not(containsString("hx-trigger=\"every 2s\""))));
    }

    @Test
    void webFailedShowsRetryAndRetryCapAndExpiryAreEnforced() throws Exception {
        String anonId = newAnonId();
        AwaitingMessage awaiting = createAwaiting(anonId, "g5-retry");
        messageStateService.confirmWebSearch(awaiting.messageId(), owner(anonId));
        failCurrentJob(awaiting.messageId(), owner(anonId));

        mockMvc.perform(get("/api/chat/messages/{id}/fragment", awaiting.messageId())
                        .cookie(cookie(anonId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"WEB_FAILED\"")))
                .andExpect(content().string(containsString("Thử lại tìm trên Web")))
                .andExpect(content().string(containsString("hx-disabled-elt=\"find button\"")))
                .andExpect(content().string(not(containsString("hx-trigger=\"every 2s\""))));

        mockMvc.perform(post("/api/chat/messages/{id}/retry-web-search", awaiting.messageId())
                        .cookie(cookie(anonId))
                        .with(csrf())
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"WEB_QUEUED\"")));

        assertThat(jobCount(awaiting.messageId())).isEqualTo(2);
        assertThat(activeJobCount(awaiting.messageId())).isEqualTo(1);
        assertThat(bucket(owner(anonId)).reservedCount()).isEqualTo(1);

        failCurrentJob(awaiting.messageId(), owner(anonId));
        mockMvc.perform(post("/api/chat/messages/{id}/retry-web-search", awaiting.messageId())
                        .cookie(cookie(anonId))
                        .with(csrf())
                        .header("HX-Request", "true"))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("giới hạn 2 lần thử")));
        assertThat(jobCount(awaiting.messageId())).isEqualTo(2);

        String expiredAnon = newAnonId();
        AwaitingMessage expired = createAwaiting(expiredAnon, "g5-retry-expired");
        messageStateService.confirmWebSearch(expired.messageId(), owner(expiredAnon));
        failCurrentJob(expired.messageId(), owner(expiredAnon));
        jdbcTemplate.update(
                "update chat_messages set confirm_expires_at = now() - interval '1 minute', "
                        + "confirmed_at = now() - interval '2 minute' where id = ?",
                expired.messageId());
        mockMvc.perform(post("/api/chat/messages/{id}/retry-web-search", expired.messageId())
                        .cookie(cookie(expiredAnon))
                        .with(csrf())
                        .header("HX-Request", "true"))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("Hạn xác nhận 15 phút đã hết")));
        assertThat(jobCount(expired.messageId())).isEqualTo(1);
    }

    @Test
    void unknownApiRouteReturns404HtmxFragment() throws Exception {
        mockMvc.perform(get("/api/chat/unknown-route")
                        .cookie(cookie(newAnonId()))
                        .header("HX-Request", "true"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Tải lại trang chat")));
    }

    @Test
    void quotaExhaustionReturns429HtmxFragment() throws Exception {
        String anonId = newAnonId();
        ChatSession session = chatSessionService.createSession(owner(anonId));
        mockMvc.perform(question(session.getId(), anonId, "g5-quota-seed", HELMET_QUESTION))
                .andExpect(status().isOk());
        jdbcTemplate.update("""
                update quota_buckets set used_count = 5, reserved_count = 0
                where anon_id = ? and quota_period = ?
                """, anonId, quotaReservationService.currentPeriod());

        mockMvc.perform(question(session.getId(), anonId, "g5-quota-exhausted", HELMET_QUESTION)
                        .header("HX-Request", "true"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string(containsString("hết 5 lượt hôm nay")));
    }

    @Test
    void dailyWebJobLimitIsEnforcedAtomically() throws Exception {
        String anonId = newAnonId();
        ChatSession session = chatSessionService.createSession(owner(anonId));
        List<AwaitingMessage> messages = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            UUID messageId = messageSubmissionService.submitQuestion(
                            session.getId(), owner(anonId), "g5-daily-job-" + index, PENALTY_QUESTION)
                    .aiMessage().getId();
            messageStateService.markAwaitingWebConfirm(messageId, owner(anonId),
                    "tìm nguồn mức phạt " + index);
            messages.add(new AwaitingMessage(session.getId(), messageId, questionHash(messageId)));
        }

        for (int index = 0; index < 3; index++) {
            confirm(messages.get(index), anonId).andExpect(status().isOk());
        }
        confirm(messages.get(3), anonId)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string(containsString("giới hạn 3 job tìm Web")));

        assertThat(messageStatus(messages.get(3).messageId())).isEqualTo("AWAITING_WEB_CONFIRM");
        BucketState bucket = bucket(owner(anonId));
        assertThat(bucket.reservedCount()).isEqualTo(3);
        assertThat(bucket.usedCount()).isZero();
        Integer totalJobs = jdbcTemplate.queryForObject("""
                select count(*) from web_search_jobs j
                join chat_messages m on m.id = j.message_id
                where m.session_id = ?
                """, Integer.class, session.getId());
        assertThat(totalJobs).isEqualTo(3);
    }

    @Test
    void pageCreatesAndReusesTheOwnersActiveSession() throws Exception {
        String anonId = newAnonId();

        MvcResult first = mockMvc.perform(get("/chat").cookie(cookie(anonId)))
                .andExpect(status().isOk())
                .andReturn();
        UUID sessionId = sessionIdOf(first);

        MvcResult second = mockMvc.perform(get("/chat").cookie(cookie(anonId)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(sessionIdOf(second)).isEqualTo(sessionId);

        mockMvc.perform(get("/chat")
                        .param("sessionId", sessionId.toString())
                        .cookie(cookie(anonId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-session-id=\"" + sessionId + "\"")));
    }

    @Test
    void multiTurnClarificationCompletesAndPreservesWebConfirmQuestionHash() throws Exception {
        String anonId = newAnonId();
        ChatSession session = chatSessionService.createSession(owner(anonId));

        mockMvc.perform(question(session.getId(), anonId, "g5-clarify-1", CLARIFICATION_QUESTION))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"NEEDS_CLARIFICATION\"")));

        mockMvc.perform(question(session.getId(), anonId, "g5-clarify-2", "chở quá số người"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"NEEDS_CLARIFICATION\"")));

        mockMvc.perform(question(session.getId(), anonId, "g5-clarify-3",
                        "22 tuổi xe 4 chỗ gia đình và chở 5 người"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"COMPLETED\"")))
                .andExpect(content().string(containsString("Trích dẫn nguyên văn")));

        UUID completedAiId = aiMessageId(session.getId(), "22 tuổi xe 4 chỗ gia đình và chở 5 người");
        assertThat(messageStatus(completedAiId)).isEqualTo("COMPLETED");
        assertThat(citationCount(completedAiId)).isGreaterThanOrEqualTo(1);
        assertThat(bucket(owner(anonId)).usedCount()).isEqualTo(1);
        assertThat(bucket(owner(anonId)).reservedCount()).isZero();

        // A clarification chain that subsequently transitions to AWAITING_WEB_CONFIRM must
        // keep questionHash aligned with the latest USER message so confirm-web-search succeeds.
        String webAnonId = newAnonId();
        ChatSession webSession = chatSessionService.createSession(owner(webAnonId));
        mockMvc.perform(question(webSession.getId(), webAnonId, "g5-clarify-web-1", CLARIFICATION_QUESTION))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"NEEDS_CLARIFICATION\"")));

        String followUpWithMissingDoc = "Nghị định 238/2026/NĐ-CP xe máy vượt đèn đỏ";
        mockMvc.perform(question(webSession.getId(), webAnonId, "g5-clarify-web-2", followUpWithMissingDoc))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"AWAITING_WEB_CONFIRM\"")));

        UUID awaitingId = aiMessageId(webSession.getId(), followUpWithMissingDoc);
        String hash = questionHash(awaitingId);
        confirm(new AwaitingMessage(webSession.getId(), awaitingId, hash), webAnonId)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-status=\"WEB_QUEUED\"")));
        assertThat(jobCount(awaitingId)).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private MockHttpServletRequestBuilder question(UUID sessionId, String anonId,
                                                   String idempotencyKey, String question) {
        return post("/api/chat/sessions/{id}/messages", sessionId)
                .cookie(cookie(anonId))
                .with(csrf())
                .header("HX-Request", "true")
                .param("message", question)
                .param("idempotencyKey", idempotencyKey);
    }

    private org.springframework.test.web.servlet.ResultActions confirm(
            AwaitingMessage awaiting, String anonId) throws Exception {
        return mockMvc.perform(post("/api/chat/messages/{id}/confirm-web-search", awaiting.messageId())
                .cookie(cookie(anonId))
                .with(csrf())
                .header("HX-Request", "true")
                .param("questionHash", awaiting.questionHash()));
    }

    private AwaitingMessage createAwaiting(String anonId, String key) {
        SessionOwner owner = owner(anonId);
        ChatSession session = chatSessionService.createSession(owner);
        UUID messageId = messageSubmissionService.submitQuestion(
                        session.getId(), owner, key, PENALTY_QUESTION)
                .aiMessage().getId();
        messageStateService.markAwaitingWebConfirm(
                messageId, owner, "mức phạt vượt đèn đỏ xe máy");
        return new AwaitingMessage(session.getId(), messageId, questionHash(messageId));
    }

    private void failCurrentJob(UUID messageId, SessionOwner owner) {
        jdbcTemplate.update("""
                update web_search_jobs set job_status = 'FAILED', error_code = 'PROVIDER_TIMEOUT'
                where message_id = ? and job_status in ('QUEUED', 'CLAIMED', 'RUNNING', 'RETRY_WAIT')
                """, messageId);
        messageStateService.transition(messageId, owner, MessageStatus.WEB_FAILED);
    }

    private SessionOwner owner(String anonId) {
        return SessionOwner.anonymous(anonId);
    }

    private String newAnonId() {
        return UUID.randomUUID().toString();
    }

    private Cookie cookie(String anonId) {
        return new Cookie(AnonymousIdentityFilter.COOKIE_NAME, anonId);
    }

    private UUID sessionIdOf(MvcResult result) {
        Matcher matcher = SESSION_ID.matcher(body(result));
        assertThat(matcher.find()).isTrue();
        return UUID.fromString(matcher.group(1));
    }

    private String body(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private UUID aiMessageId(UUID sessionId, String question) {
        return jdbcTemplate.queryForObject("""
                select ai.id
                from chat_messages ai
                join chat_messages q on q.id = ai.in_reply_to_id
                where ai.session_id = ? and ai.sender = 'AI' and q.content = ?
                order by ai.created_at desc
                limit 1
                """, UUID.class, sessionId, question);
    }

    private String questionHash(UUID messageId) {
        return jdbcTemplate.queryForObject(
                "select question_hash from chat_messages where id = ?", String.class, messageId);
    }

    private String messageStatus(UUID messageId) {
        return jdbcTemplate.queryForObject(
                "select message_status from chat_messages where id = ?", String.class, messageId);
    }

    private Map<String, Object> messageRow(UUID messageId) {
        return jdbcTemplate.queryForMap(
                "select message_status, suggested_query from chat_messages where id = ?", messageId);
    }

    private int citationCount(UUID messageId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from message_citations where message_id = ?", Integer.class, messageId);
        return count == null ? 0 : count;
    }

    private int jobCount(UUID messageId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from web_search_jobs where message_id = ?", Integer.class, messageId);
        return count == null ? 0 : count;
    }

    private int activeJobCount(UUID messageId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from web_search_jobs
                where message_id = ? and job_status in ('QUEUED', 'CLAIMED', 'RUNNING', 'RETRY_WAIT')
                """, Integer.class, messageId);
        return count == null ? 0 : count;
    }

    private int activeReservationCount(UUID messageId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from quota_reservations where message_id = ? and status = 'ACTIVE'",
                Integer.class, messageId);
        return count == null ? 0 : count;
    }

    private String reservationStatus(UUID messageId) {
        return jdbcTemplate.queryForObject("""
                select status from quota_reservations
                where message_id = ?
                order by created_at desc
                limit 1
                """, String.class, messageId);
    }

    private BucketState bucket(SessionOwner owner) {
        List<BucketState> rows = jdbcTemplate.query("""
                select limit_count, used_count, reserved_count
                from quota_buckets
                where anon_id = ? and quota_period = ?
                """,
                (resultSet, rowNumber) -> new BucketState(
                        resultSet.getInt("limit_count"),
                        resultSet.getInt("used_count"),
                        resultSet.getInt("reserved_count")),
                owner.ownerId(), quotaReservationService.currentPeriod());
        return rows.isEmpty() ? new BucketState(5, 0, 0) : rows.get(0);
    }

    private List<Integer> runConcurrently(List<Callable<Integer>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent test start timed out");
                    }
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                try {
                    statuses.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    throw new AssertionError("concurrent request failed", exception.getCause());
                }
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }

    private record AwaitingMessage(UUID sessionId, UUID messageId, String questionHash) {
    }

    private record BucketState(int limitCount, int usedCount, int reservedCount) {
    }
}
