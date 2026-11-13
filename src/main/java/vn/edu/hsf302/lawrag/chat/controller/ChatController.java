package vn.edu.hsf302.lawrag.chat.controller;

import java.util.UUID;

import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import vn.edu.hsf302.lawrag.chat.dto.ChatConversationView;
import vn.edu.hsf302.lawrag.chat.service.ChatOwnerResolver;
import vn.edu.hsf302.lawrag.chat.entity.ChatSession;
import vn.edu.hsf302.lawrag.chat.service.ChatSessionService;
import vn.edu.hsf302.lawrag.chat.service.ChatViewService;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;

@Controller
public class ChatController {

    private final HealthEndpoint healthEndpoint;
    private final ChatSessionService sessions;
    private final ChatViewService views;

    public ChatController(HealthEndpoint healthEndpoint,
                          ChatSessionService sessions,
                          ChatViewService views) {
        this.healthEndpoint = healthEndpoint;
        this.sessions = sessions;
        this.views = views;
    }

    @GetMapping({"/", "/chat"})
    public String chat(@RequestParam(name = "sessionId", required = false) UUID sessionId,
                       HttpServletRequest request,
                       Model model) {
        SessionOwner owner = ChatOwnerResolver.current(request);
        ChatSession session = sessionId != null
                ? sessions.requireOwnedSession(sessionId, owner)
                : sessions.findActiveSession(owner).orElseGet(() -> sessions.createSession(owner));

        ChatConversationView conversation = views.conversation(session.getId(), owner);
        HealthComponent health = healthEndpoint.health();

        model.addAttribute("healthStatus", health.getStatus().getCode());
        model.addAttribute("healthUp", "UP".equals(health.getStatus().getCode()));
        model.addAttribute("sessionId", session.getId());
        model.addAttribute("messages", conversation.messages());
        model.addAttribute("quotaUsed", conversation.quotaUsed());
        model.addAttribute("quotaLimit", conversation.quotaLimit());
        model.addAttribute("webSearchAllowed", conversation.webSearchAllowed());
        model.addAttribute("recentSessions", views.listRecentSessions(owner, session.getId()));
        model.addAttribute("idempotencyKey", UUID.randomUUID().toString());
        return "chat/index";
    }

    @GetMapping("/chat/new")
    public String newConversationFallback() {
        return "redirect:/chat";
    }

    @PostMapping("/chat/new")
    public String newConversation(HttpServletRequest request) {
        SessionOwner owner = ChatOwnerResolver.current(request);
        ChatSession session = sessions.createOrReuseEmptySession(owner);
        return "redirect:/chat?sessionId=" + session.getId();
    }

    @PostMapping("/chat/sessions/{sessionId}/delete")
    public String deleteConversation(@PathVariable UUID sessionId,
                                     @RequestParam(name = "activeSessionId", required = false) UUID activeSessionId,
                                     HttpServletRequest request) {
        SessionOwner owner = ChatOwnerResolver.current(request);
        sessions.closeSession(sessionId, owner);
        if (activeSessionId != null && !activeSessionId.equals(sessionId)) {
            return "redirect:/chat?sessionId=" + activeSessionId;
        }
        return "redirect:/chat";
    }

    /** Legacy Phase 1 skeleton endpoint kept for the G1 regression test. */
    @PostMapping("/api/chat/skeleton")
    public String skeleton(@RequestParam(name = "message", defaultValue = "") String message, Model model) {
        String normalized = message.strip();
        if (normalized.length() > 500) {
            normalized = normalized.substring(0, 500);
        }
        model.addAttribute("skeletonMessage", normalized);
        return "fragments/chat-skeleton :: response";
    }
}
