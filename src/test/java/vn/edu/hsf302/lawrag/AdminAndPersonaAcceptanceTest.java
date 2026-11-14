package vn.edu.hsf302.lawrag;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.hsf302.lawrag.forum.entity.ForumCategory;
import vn.edu.hsf302.lawrag.forum.service.ForumService;
import vn.edu.hsf302.lawrag.ingestion.service.CorpusImportService;
import vn.edu.hsf302.lawrag.llm.dto.LlmPersonaView;
import vn.edu.hsf302.lawrag.llm.service.LlmPersonaService;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAndPersonaAcceptanceTest extends PostgresIntegrationTest {

    private static final Pattern FORUM_POST_PATH = Pattern.compile("/forum/([0-9a-f-]{36})");
    private static final Pattern COMMENT_ANCHOR_ID = Pattern.compile("id=\"comment-([0-9a-f-]{36})\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ForumService forumService;

    @Autowired
    private LlmPersonaService personaService;

    @Autowired
    private CorpusImportService corpusImportService;

    @Test
    void adminRbacLogsForumModerationAndAiDraftFlowWorkEndToEnd() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String userName = "fmod_user_" + suffix;
        MockHttpSession userSession = registerAndLogin(userName, "Thành viên Test");
        MockHttpSession adminSession = login("admin", "admin-test-password", "/admin");

        // Regular USER is blocked from all new admin routes
        mockMvc.perform(get("/admin/forum").session(userSession)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/logs").session(userSession)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/users").session(userSession)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/llm").session(userSession)).andExpect(status().isForbidden());

        // User creates a forum question -> appears as OPEN ("Chờ phản hồi")
        String title = "Thắc mắc biển báo đường cấm " + suffix;
        MvcResult postResult = mockMvc.perform(post("/forum/posts")
                        .session(userSession)
                        .with(csrf())
                        .param("title", title)
                        .param("category", ForumCategory.GIAO_THONG.name())
                        .param("content", "Cho hỏi mức xử phạt khi đi vào đường có biển báo cấm xe máy."))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        UUID postId = extractPostId(postResult.getResponse().getRedirectedUrl());

        // Admin dashboard, logs & open forum queue render cleanly with vertical left sidebar
        mockMvc.perform(get("/admin").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"admin-workspace\"")))
                .andExpect(content().string(containsString("class=\"admin-sidebar\"")))
                .andExpect(content().string(containsString("Ngân sách API &amp; Trạng thái AI hôm nay")))
                .andExpect(content().string(containsString("Quản lý tài khoản")));

        mockMvc.perform(get("/admin/forum").session(adminSession)
                        .param("requestStatus", "OPEN")
                        .param("q", suffix))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(title)))
                .andExpect(content().string(containsString("Gợi ý trả lời bằng RAG/AI")));

        // AI draft endpoint returns JSON safely without auto-posting to DB
        long commentsBeforeDraft = count("select count(*) from forum_comments where post_id = ?", postId);
        mockMvc.perform(post("/admin/forum/posts/{id}/ai-draft", postId)
                        .session(adminSession)
                        .with(csrf())
                        .param("personaCode", "CHUYEN_NGHIEP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertThat(count("select count(*) from forum_comments where post_id = ?", postId))
                .isEqualTo(commentsBeforeDraft);

        // Admin posts an official reply -> transitions OPEN -> ANSWERED + records audit
        mockMvc.perform(post("/admin/forum/posts/{id}/reply", postId)
                        .session(adminSession)
                        .with(csrf())
                        .param("content", "Ban quản trị phản hồi chính thức căn cứ Luật Trật tự ATGT 2024."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/forum"));

        assertThat(jdbcTemplate.queryForObject(
                "select request_status from forum_posts where id = ?", String.class, postId))
                .isEqualTo("ANSWERED");
        assertThat(count(
                "select count(*) from forum_comments where post_id = ? and official_admin_reply = true", postId))
                .isEqualTo(1L);
        assertThat(count(
                "select count(*) from admin_audit_log where action = 'FORUM_ADMIN_REPLY'"))
                .isGreaterThanOrEqualTo(1L);

        mockMvc.perform(get("/forum/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Phản hồi chính thức")))
                .andExpect(content().string(containsString("Quản trị viên")));

        // Admin soft-removes post -> hidden from public forum (404), visible in /admin/forum?status=REMOVED
        mockMvc.perform(post("/admin/forum/posts/{id}/remove", postId)
                        .session(adminSession)
                        .with(csrf())
                        .param("reason", "Tạm gỡ để rà soát nội dung " + suffix))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/forum").param("q", suffix))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(title))));
        mockMvc.perform(get("/forum/{id}", postId).session(userSession))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/forum").session(adminSession).param("status", "REMOVED"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tạm gỡ để rà soát nội dung " + suffix)));

        // Admin restores post -> visible publicly again
        mockMvc.perform(post("/admin/forum/posts/{id}/restore", postId)
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/forum/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(title)));

        // Admin hard-deletes post -> permanently removed + logged in /admin/logs?tab=audit
        mockMvc.perform(post("/admin/forum/posts/{id}/delete", postId)
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(count("select count(*) from forum_posts where id = ?", postId)).isZero();

        mockMvc.perform(get("/admin/logs").session(adminSession)
                        .param("tab", "audit")
                        .param("action", "FORUM_REMOVE_POST"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("FORUM_REMOVE_POST")));

        mockMvc.perform(get("/admin/logs").session(adminSession).param("tab", "rag"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Truy vết quyết định RAG / LLM")));

        mockMvc.perform(get("/admin/logs").session(adminSession).param("tab", "usage"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chi phí API &amp; Token theo ngày")))
                .andExpect(content().string(containsString("Tổng hợp sử dụng API")))
                .andExpect(content().string(containsString("Tổng Token Gemini")));
    }

    @Test
    void forumUsersCanReplyInThreadsEditAndAcceptUsefulAnswer() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String author = "fthread_a_" + suffix;
        String replier = "fthread_b_" + suffix;
        MockHttpSession authorSession = registerAndLogin(author, "Tác giả Thread");
        MockHttpSession replierSession = registerAndLogin(replier, "Người Trả lời");

        UUID postId = forumService.createPost(
                author,
                "Hỏi về chuyển làn không xi-nhan " + suffix,
                ForumCategory.GIAO_THONG,
                "Xin hỏi ô tô chuyển làn không có tín hiệu báo trước phạt thế nào?");

        // Replier submits a top-level comment
        mockMvc.perform(post("/forum/posts/{id}/comments", postId)
                        .session(replierSession)
                        .with(csrf())
                        .param("content", "Cần căn cứ khoản xử phạt chuyển làn xe ô tô trong Nghị định mới."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forum/" + postId + "#comments"));

        String detailHtml = mockMvc.perform(get("/forum/{id}", postId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher cm = COMMENT_ANCHOR_ID.matcher(detailHtml);
        assertThat(cm.find()).isTrue();
        UUID commentId = UUID.fromString(cm.group(1));

        // Author replies directly to Replier's comment (nested reply)
        mockMvc.perform(post("/forum/posts/{id}/comments", postId)
                        .session(authorSession)
                        .with(csrf())
                        .param("parentCommentId", commentId.toString())
                        .param("content", "Cảm ơn bạn, mình đã tra đúng Điều khoản đó."))
                .andExpect(status().is3xxRedirection());

        // Non-owner cannot edit another user's comment (403)
        mockMvc.perform(post("/forum/comments/{id}/edit", commentId)
                        .session(authorSession)
                        .with(csrf())
                        .param("content", "Sửa trái phép bình luận người khác"))
                .andExpect(status().isForbidden());

        // Replier can edit their own comment and mark edited_at
        mockMvc.perform(post("/forum/comments/{id}/edit", commentId)
                        .session(replierSession)
                        .with(csrf())
                        .param("content", "Đã bổ sung căn cứ cụ thể theo Nghị định 168/2024."))
                .andExpect(status().is3xxRedirection());

        // Non-author (replier) cannot accept answer (403); Post author CAN accept answer
        mockMvc.perform(post("/forum/comments/{id}/accept", commentId)
                        .session(replierSession)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/forum/comments/{id}/accept", commentId)
                        .session(authorSession)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/forum/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Câu trả lời hữu ích")))
                .andExpect(content().string(containsString("Đã giải quyết")))
                .andExpect(content().string(containsString("↳ Trả lời Người Trả lời")))
                .andExpect(content().string(containsString("Đã bổ sung căn cứ cụ thể theo Nghị định 168/2024.")));
    }

    @Test
    void llmPersonaSelectionPersistsAcrossChatAndAdminCanConfigureDefault() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MockHttpSession userSession = registerAndLogin("persona_u_" + suffix, "Persona Tester");
        MockHttpSession adminSession = login("admin", "admin-test-password", "/admin");

        // Chat page renders all 3 initial personas inside the left sidebar below sample prompts and links to #question-form
        String chatHtml = mockMvc.perform(get("/chat").session(userSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chuyên nghiệp")))
                .andExpect(content().string(containsString("Ngắn gọn")))
                .andExpect(content().string(containsString("Đầy đủ")))
                .andExpect(content().string(containsString("class=\"persona-btn-group\"")))
                .andExpect(content().string(containsString("type=\"radio\"")))
                .andExpect(content().string(containsString("form=\"question-form\"")))
                .andReturn().getResponse().getContentAsString();

        assertThat(chatHtml.indexOf("class=\"side-card prompts-card\""))
                .isLessThan(chatHtml.indexOf("class=\"side-card persona-card\""));
        assertThat(chatHtml.indexOf("class=\"side-card persona-card\""))
                .isLessThan(chatHtml.indexOf("</aside>"));

        Matcher sessionMatcher = Pattern.compile("data-session-id=\"([0-9a-f-]{36})\"").matcher(chatHtml);
        assertThat(sessionMatcher.find()).isTrue();
        UUID sessionId = UUID.fromString(sessionMatcher.group(1));

        // Submitting with personaCode=NGAN_GON stores it on session, messages & retrieval trace
        mockMvc.perform(post("/api/chat/sessions/{id}/messages", sessionId)
                        .session(userSession)
                        .with(csrf())
                        .param("message", "Xe đạp đi ngược chiều xử lý sao?")
                        .param("idempotencyKey", "key-ngan-gon-" + suffix)
                        .param("personaCode", "NGAN_GON"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ngắn gọn")));

        assertThat(jdbcTemplate.queryForObject(
                "select persona_code from chat_sessions where id = ?", String.class, sessionId))
                .isEqualTo("NGAN_GON");
        assertThat(count(
                "select count(*) from retrieval_traces t join chat_messages m on m.id = t.message_id "
                        + "where m.session_id = ? and t.persona_code = 'NGAN_GON'",
                sessionId)).isGreaterThanOrEqualTo(1L);

        // Admin creates a custom persona, switches default, then restores CHUYEN_NGHIEP cleanly
        String customCode = ("CUSTOM_" + suffix).toUpperCase().replace("-", "_");
        LlmPersonaView originalDefault = personaService.listAllPersonas().stream()
                .filter(LlmPersonaView::defaultPersona)
                .findFirst()
                .orElseThrow();

        try {
            mockMvc.perform(post("/admin/llm/personas")
                            .session(adminSession)
                            .with(csrf())
                            .param("code", customCode)
                            .param("name", "Thân thiện " + suffix)
                            .param("description", "Giải thích dễ hiểu cho người mới.")
                            .param("systemInstruction", "Trả lời thân thiện, dễ hiểu, ví dụ ngắn.")
                            .param("responseStyle", "CUSTOM")
                            .param("displayOrder", "99")
                            .param("setAsDefault", "true"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/llm"));

            assertThat(personaService.getDefaultPersona().getCode()).isEqualTo(customCode);
            assertThat(count(
                    "select count(*) from admin_audit_log where action = 'LLM_PERSONA_CREATE'"))
                    .isGreaterThanOrEqualTo(1L);
        } finally {
            personaService.setDefaultPersona("admin", originalDefault.id());
            jdbcTemplate.update("delete from llm_personas where code = ?", customCode);
        }
    }

    @Test
    void adminCanListUploadEmbedAndDeleteRagPdfDocuments() throws Exception {
        corpusImportService.importCorpus();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MockHttpSession adminSession = login("admin", "admin-test-password", "/admin");
        MockHttpSession userSession = registerAndLogin("pdf_user_" + suffix, "Regular User");

        // Regular user cannot upload, embed, or delete RAG PDF documents
        MockMultipartFile blockedPdf = new MockMultipartFile(
                "file", "blocked.pdf", "application/pdf",
                buildPdfBytes("Noi dung khong duoc phep"));
        mockMvc.perform(multipart("/admin/legal-documents/upload")
                        .file(blockedPdf)
                        .session(userSession)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        // Existing RAG PDF documents render instead of "Job tìm Web"
        mockMvc.perform(get("/admin/web-search-jobs").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tài liệu PDF đang sử dụng cho RAG")))
                .andExpect(content().string(containsString("Thêm tài liệu PDF mới vào kho RAG")))
                .andExpect(content().string(containsString("36/2024/QH15")))
                .andExpect(content().string(containsString("168/2024/NĐ-CP")))
                .andExpect(content().string(not(containsString("Chưa có job tìm Web nào."))));

        // 1) Upload a PDF without immediate embedding -> READY, 0 vectors initially
        String docNumber1 = "99-" + suffix + "/PDF-RAG";
        MockMultipartFile pdfWithoutEmbed = new MockMultipartFile(
                "file",
                "quy-dinh-" + suffix + ".pdf",
                "application/pdf",
                buildPdfBytes("Dieu 1. Pham vi thu nghiem PDF RAG", "1. Nguoi dieu khien phuong tien phai tuan thu."));

        mockMvc.perform(multipart("/admin/legal-documents/upload")
                        .file(pdfWithoutEmbed)
                        .session(adminSession)
                        .with(csrf())
                        .param("title", "Tài liệu PDF RAG " + suffix)
                        .param("docNumber", docNumber1)
                        .param("issuingBody", "Chính phủ")
                        .param("issueDate", "2026-01-01")
                        .param("effectiveFrom", "2026-01-15")
                        .param("effectiveStatus", "EFFECTIVE")
                        .param("runEmbedding", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/web-search-jobs"));

        UUID docId1 = jdbcTemplate.queryForObject(
                "select id from legal_documents where doc_number = ?", UUID.class, docNumber1);
        assertThat(docId1).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "select verification_status from legal_documents where id = ?", String.class, docId1))
                .isEqualTo("READY");
        assertThat(count("select count(*) from legal_articles where document_id = ?", docId1))
                .isGreaterThanOrEqualTo(2L);
        assertThat(count(
                "select count(*) from article_embeddings e join legal_articles a on a.id = e.article_id where a.document_id = ?",
                docId1)).isZero();

        mockMvc.perform(get("/admin/web-search-jobs").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(docNumber1)))
                .andExpect(content().string(containsString("Chưa embedding (0)")));

        // 2) Trigger manual embedding for that document
        mockMvc.perform(post("/admin/legal-documents/{id}/embed", docId1)
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/web-search-jobs"));

        assertThat(count(
                "select count(*) from article_embeddings e join legal_articles a on a.id = e.article_id "
                        + "where a.document_id = ? and e.status = 'READY'",
                docId1)).isGreaterThan(0L);
        assertThat(count(
                "select count(*) from admin_audit_log where entity_id = ? and action = 'EMBED_LEGAL_DOCUMENT'",
                docId1)).isEqualTo(1L);

        // Trigger batch embedding across all READY documents
        mockMvc.perform(post("/admin/legal-documents/embed-all")
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/web-search-jobs"));
        assertThat(count("select count(*) from admin_audit_log where action = 'EMBED_ALL_READY_DOCUMENTS'"))
                .isGreaterThanOrEqualTo(1L);

        // 3) Upload a custom PDF without "Điều 1." headers and with runEmbedding=true
        String docNumber2 = "98-" + suffix + "/PDF-CUSTOM";
        MockMultipartFile plainPdf = new MockMultipartFile(
                "file",
                "custom-plain-" + suffix + ".pdf",
                "application/pdf",
                buildPdfBytes("Thong bao noi bo ve an toan giao thong duong bo."));

        mockMvc.perform(multipart("/admin/legal-documents/upload")
                        .file(plainPdf)
                        .session(adminSession)
                        .with(csrf())
                        .param("title", "Hướng dẫn nội bộ " + suffix)
                        .param("docNumber", docNumber2)
                        .param("issuingBody", "Bộ Giao thông")
                        .param("issueDate", "2026-02-01")
                        .param("effectiveFrom", "2026-02-01")
                        .param("effectiveStatus", "EFFECTIVE")
                        .param("runEmbedding", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/web-search-jobs"));

        UUID docId2 = jdbcTemplate.queryForObject(
                "select id from legal_documents where doc_number = ?", UUID.class, docNumber2);
        assertThat(count(
                "select count(*) from article_embeddings e join legal_articles a on a.id = e.article_id "
                        + "where a.document_id = ? and e.status = 'READY'",
                docId2)).isGreaterThan(0L);

        // 4) Delete both uploaded PDF documents cleanly
        mockMvc.perform(post("/admin/legal-documents/{id}/delete", docId1)
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/web-search-jobs"));
        mockMvc.perform(post("/admin/legal-documents/{id}/delete", docId2)
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/web-search-jobs"));

        assertThat(count("select count(*) from legal_documents where id in (?, ?)", docId1, docId2)).isZero();
        assertThat(count("select count(*) from legal_articles where document_id in (?, ?)", docId1, docId2)).isZero();
        assertThat(count(
                "select count(*) from admin_audit_log where entity_id = ? and action = 'DELETE_LEGAL_DOCUMENT'",
                docId1)).isEqualTo(1L);
    }

    private static byte[] buildPdfBytes(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(50, 700);
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) {
                        stream.newLineAtOffset(0, -18);
                    }
                    stream.showText(new String(lines[i].getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII));
                }
                stream.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private MockHttpSession registerAndLogin(String username, String displayName) throws Exception {
        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", username)
                        .param("displayName", displayName)
                        .param("password", "secret123")
                        .param("confirmPassword", "secret123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?registered"));
        return login(username, "secret123", "/chat");
    }

    private MockHttpSession login(String username, String password, String expectedRedirect) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedRedirect))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static UUID extractPostId(String redirectedUrl) {
        assertThat(redirectedUrl).isNotNull();
        Matcher m = FORUM_POST_PATH.matcher(redirectedUrl);
        assertThat(m.find()).isTrue();
        return UUID.fromString(m.group(1));
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
