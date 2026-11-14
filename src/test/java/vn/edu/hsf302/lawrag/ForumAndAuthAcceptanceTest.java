package vn.edu.hsf302.lawrag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.edu.hsf302.lawrag.auth.entity.AppUser;
import vn.edu.hsf302.lawrag.auth.repository.AppUserRepository;
import vn.edu.hsf302.lawrag.auth.entity.UserRole;
import vn.edu.hsf302.lawrag.forum.entity.ForumCategory;
import vn.edu.hsf302.lawrag.forum.service.ForumService;

class ForumAndAuthAcceptanceTest extends PostgresIntegrationTest {

    private static final Pattern FORUM_POST_PATH = Pattern.compile("/forum/([0-9a-f-]{36})");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private ForumService forumService;

    @Test
    void topbarShowsQaForumAndLoginForGuests() throws Exception {
        mockMvc.perform(get("/chat"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hỏi đáp")))
                .andExpect(content().string(containsString("Diễn đàn")))
                .andExpect(content().string(containsString("Đăng nhập")))
                .andExpect(content().string(not(containsString(">Quản trị<"))));
    }

    @Test
    void loginAndRegisterPagesAreAccessibleAndInvalidCsrfRedirectsToExpired() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đăng nhập")));

        mockMvc.perform(get("/login/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đăng nhập")));

        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đăng ký tài khoản")));

        mockMvc.perform(post("/login")
                        .param("username", "admin")
                        .param("password", "admin-test-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void forumListAndDetailArePubliclyReadableAndFilterable() throws Exception {
        mockMvc.perform(get("/forum"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Chào mừng đến với Diễn đàn Pháp Luật Việt Nam")))
                .andExpect(content().string(containsString("Đăng nhập")));

        mockMvc.perform(get("/forum").param("category", "GIAO_THONG"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mũ bảo hiểm")))
                .andExpect(content().string(not(containsString("Chào mừng đến với Diễn đàn Pháp Luật Việt Nam"))));

        mockMvc.perform(get("/forum/10000000-0000-0000-0000-000000000001"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ban biên tập Pháp Luật Việt Nam")));
    }

    @Test
    void anonymousCannotCreateForumPost() throws Exception {
        mockMvc.perform(post("/forum/posts")
                        .with(csrf())
                        .param("title", "Câu hỏi kiểm thử chưa đăng nhập")
                        .param("category", "THAO_LUAN")
                        .param("content", "Nội dung này phải bị chặn vì chưa đăng nhập."))
                .andExpect(status().isForbidden());
    }

    @Test
    void registerLoginCreatePostCommentDeleteAndChatOwnershipFlow() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "user_" + suffix;
        String otherUser = "other_" + suffix;

        // Register first user
        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", username)
                        .param("displayName", "Nguyễn Minh " + suffix)
                        .param("password", "secret123")
                        .param("confirmPassword", "secret123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?registered"));

        // Duplicate username is rejected cleanly on form
        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", username)
                        .param("displayName", "Trùng tên")
                        .param("password", "secret123")
                        .param("confirmPassword", "secret123"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("đã được sử dụng")));

        // Register second user
        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", otherUser)
                        .param("displayName", "Trần Bình")
                        .param("password", "secret123")
                        .param("confirmPassword", "secret123"))
                .andExpect(status().is3xxRedirection());

        // Login as first user -> lands on /chat
        MvcResult loginResult = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", username)
                        .param("password", "secret123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // Chat page shows authenticated USER identity without Admin link, and /admin is forbidden
        MvcResult chatResult = mockMvc.perform(get("/chat").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nguyễn Minh " + suffix)))
                .andExpect(content().string(containsString("Thành viên (USER)")))
                .andExpect(content().string(containsString("Hội thoại mới")))
                .andExpect(content().string(containsString("Lịch sử hội thoại")))
                .andExpect(content().string(containsString("Đăng xuất")))
                .andExpect(content().string(not(containsString(">Quản trị<"))))
                .andReturn();

        Matcher sessionMatcher = Pattern.compile("data-session-id=\"([0-9a-f-]{36})\"")
                .matcher(chatResult.getResponse().getContentAsString());
        assertThat(sessionMatcher.find()).isTrue();
        String firstSessionId = sessionMatcher.group(1);

        // GET /chat/new redirects cleanly without 404
        mockMvc.perform(get("/chat/new").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat"));

        // When current session is empty, POST /chat/new reuses it instead of creating empty duplicates
        mockMvc.perform(post("/chat/new")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat?sessionId=" + firstSessionId));

        // After submitting a message, POST /chat/new creates a fresh session and keeps history
        mockMvc.perform(post("/api/chat/sessions/" + firstSessionId + "/messages")
                        .session(session)
                        .with(csrf())
                        .param("message", "Người đi xe máy có phải đội mũ bảo hiểm không?")
                        .param("idempotencyKey", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        MvcResult newChatResult = mockMvc.perform(post("/chat/new")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String newChatLocation = newChatResult.getResponse().getRedirectedUrl();
        assertThat(newChatLocation).startsWith("/chat?sessionId=");
        String secondSessionId = newChatLocation.substring("/chat?sessionId=".length());
        assertThat(secondSessionId).isNotEqualTo(firstSessionId);

        mockMvc.perform(get("/chat").param("sessionId", secondSessionId).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Người đi xe máy có phải đội mũ bảo hiểm")));

        // Closing the first session works for owner and returns to chat; second session stays intact
        mockMvc.perform(post("/chat/sessions/" + firstSessionId + "/delete")
                        .session(session)
                        .with(csrf())
                        .param("activeSessionId", secondSessionId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat?sessionId=" + secondSessionId));

        mockMvc.perform(get("/chat").param("sessionId", firstSessionId).session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin").session(session))
                .andExpect(status().isForbidden());

        // Create a forum post
        MvcResult createResult = mockMvc.perform(post("/forum/posts")
                        .session(session)
                        .with(csrf())
                        .param("title", "Thảo luận về xử lý vi phạm giao thông " + suffix)
                        .param("category", "GIAO_THONG")
                        .param("content", "Xin hỏi thủ tục tra cứu quyết định xử phạt vi phạm hành chính như thế nào?"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String redirected = createResult.getResponse().getRedirectedUrl();
        assertThat(redirected).isNotNull();
        Matcher matcher = FORUM_POST_PATH.matcher(redirected);
        assertThat(matcher.find()).isTrue();
        String postId = matcher.group(1);

        // Add comment on that post
        mockMvc.perform(post("/forum/posts/" + postId + "/comments")
                        .session(session)
                        .with(csrf())
                        .param("content", "Cảm ơn mọi người đã giải đáp chi tiết."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forum/" + postId + "#comments"));

        mockMvc.perform(get("/forum/" + postId).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Thảo luận về xử lý vi phạm giao thông " + suffix)))
                .andExpect(content().string(containsString("Cảm ơn mọi người đã giải đáp chi tiết.")));

        // Login as second user and verify cannot delete first user's post
        MvcResult otherLogin = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", otherUser)
                        .param("password", "secret123"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession otherSession = (MockHttpSession) otherLogin.getRequest().getSession(false);

        mockMvc.perform(post("/forum/posts/" + postId + "/delete")
                        .session(otherSession)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        // Original author deletes post and logs out
        mockMvc.perform(post("/forum/posts/" + postId + "/delete")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forum"));

        mockMvc.perform(get("/forum/" + postId))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void adminLoginRoutesToAdminAndSupportsRoleAndAccountManagement() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "member_" + suffix;

        // Register a normal USER
        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", username)
                        .param("displayName", "Lê Văn " + suffix)
                        .param("password", "secret123")
                        .param("confirmPassword", "secret123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?registered"));

        // Wrong password redirects to /login?error
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "admin")
                        .param("password", "wrong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        // Bootstrapped ADMIN logs in and lands on /admin
        MvcResult adminLogin = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "admin")
                        .param("password", "admin-test-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"))
                .andReturn();

        MockHttpSession adminSession = (MockHttpSession) adminLogin.getRequest().getSession(false);
        assertThat(adminSession).isNotNull();

        // Admin console shows both ADMIN and USER accounts + Admin link in topbar
        mockMvc.perform(get("/admin").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">Quản trị<")))
                .andExpect(content().string(containsString("Quản lý tài khoản")))
                .andExpect(content().string(containsString("@" + username)));

        // USER creates a post, USER cannot pin it, ADMIN can pin and unpin it
        MvcResult userLogin = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", username)
                        .param("password", "secret123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat"))
                .andReturn();
        MockHttpSession userSession = (MockHttpSession) userLogin.getRequest().getSession(false);

        MvcResult postResult = mockMvc.perform(post("/forum/posts")
                        .session(userSession)
                        .with(csrf())
                        .param("title", "Câu hỏi kiểm duyệt admin " + suffix)
                        .param("category", "THAO_LUAN")
                        .param("content", "Nội dung cần quản trị viên kiểm tra và ghim lên đầu."))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Matcher matcher = FORUM_POST_PATH.matcher(postResult.getResponse().getRedirectedUrl());
        assertThat(matcher.find()).isTrue();
        String postId = matcher.group(1);

        mockMvc.perform(post("/forum/posts/" + postId + "/pin")
                        .session(userSession)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/forum/posts/" + postId + "/pin")
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forum/" + postId));

        // Admin promotes USER -> ADMIN, then demotes back -> USER, and locks -> unlocks USER
        AppUser member = userRepository.findByUsername(username).orElseThrow();
        mockMvc.perform(post("/admin/users/" + member.getId() + "/role")
                        .session(adminSession)
                        .with(csrf())
                        .param("role", "ADMIN"))
                .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findByUsername(username).orElseThrow().getRole()).isEqualTo(UserRole.ADMIN);

        mockMvc.perform(post("/admin/users/" + member.getId() + "/role")
                        .session(adminSession)
                        .with(csrf())
                        .param("role", "USER"))
                .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findByUsername(username).orElseThrow().getRole()).isEqualTo(UserRole.USER);

        // Lock user -> login must fail
        mockMvc.perform(post("/admin/users/" + member.getId() + "/toggle-enabled")
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findByUsername(username).orElseThrow().isEnabled()).isFalse();

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", username)
                        .param("password", "secret123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        // Unlock user -> login succeeds again
        mockMvc.perform(post("/admin/users/" + member.getId() + "/toggle-enabled")
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", username)
                        .param("password", "secret123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat"));

        // Admin deletes user's post cleanly
        mockMvc.perform(post("/forum/posts/" + postId + "/delete")
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forum"));
    }

    @Test
    void forumListLimitsTenPostsPerPageWithScrollableRegionAndPagination() throws Exception {
        String marker = "pagetest-" + UUID.randomUUID().toString().substring(0, 8);
        List<UUID> createdIds = new ArrayList<>();
        try {
            for (int i = 1; i <= 12; i++) {
                createdIds.add(forumService.createPost(
                        "admin",
                        "Chủ đề kiểm thử phân trang " + marker + " #" + i,
                        ForumCategory.KHAC,
                        "Nội dung kiểm thử phân trang cho diễn đàn số " + i + " (" + marker + ")."));
            }

            MvcResult page1 = mockMvc.perform(get("/forum")
                            .param("category", "KHAC")
                            .param("q", marker))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("forum-scroll-region")))
                    .andExpect(content().string(containsString("forum-pagination")))
                    .andExpect(content().string(containsString("Trang <strong>1</strong> / <strong>2</strong>")))
                    .andExpect(content().string(containsString("<span>10</span> bài/trang")))
                    .andExpect(content().string(containsString("<strong>12</strong> bài viết")))
                    .andReturn();
            String page1Html = page1.getResponse().getContentAsString();
            assertThat(countOccurrences(page1Html, "class=\"card forum-post-card\"")).isEqualTo(10);

            MvcResult page2 = mockMvc.perform(get("/forum")
                            .param("category", "KHAC")
                            .param("q", marker)
                            .param("page", "2"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Trang <strong>2</strong> / <strong>2</strong>")))
                    .andReturn();
            String page2Html = page2.getResponse().getContentAsString();
            assertThat(countOccurrences(page2Html, "class=\"card forum-post-card\"")).isEqualTo(2);
        } finally {
            for (UUID id : createdIds) {
                forumService.deletePost(id, "admin", true);
            }
        }
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}

