package vn.edu.hsf302.lawrag.chat.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import vn.edu.hsf302.lawrag.chat.service.ChatConsentService;
import vn.edu.hsf302.lawrag.chat.dto.ChatOutcome;
import vn.edu.hsf302.lawrag.chat.service.ChatOwnerResolver;
import vn.edu.hsf302.lawrag.chat.service.ChatViewService;
import vn.edu.hsf302.lawrag.chat.service.LocalChatOrchestrator;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;

/**
 * HTMX endpoints. The server owns the suggested search query; query/url form
 * fields sent by a browser are never read by these methods.
 */
@Controller
@RequestMapping("/api/chat")
public class ChatApiController {

    private final LocalChatOrchestrator orchestrator;
    private final ChatViewService views;
    private final ChatConsentService consent;

    public ChatApiController(LocalChatOrchestrator orchestrator,
                             ChatViewService views,
                             ChatConsentService consent) {
        this.orchestrator = orchestrator;
        this.views = views;
        this.consent = consent;
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public String submit(@PathVariable UUID sessionId,
                         @RequestParam(name = "message", defaultValue = "") String message,
                         @RequestParam(name = "idempotencyKey", required = false) String idempotencyKey,
                         @RequestParam(name = "personaCode", required = false) String personaCode,
                         @RequestParam(name = "stream", required = false) Boolean stream,
                         HttpServletRequest request,
                         Model model) {
        SessionOwner owner = ChatOwnerResolver.current(request);
        boolean streamRequested = "true".equalsIgnoreCase(request.getHeader("X-Chat-Stream"))
                || Boolean.TRUE.equals(stream);
        ChatOutcome outcome = streamRequested
                ? orchestrator.submitForStream(sessionId, owner, idempotencyKey, message, personaCode)
                : orchestrator.ask(sessionId, owner, idempotencyKey, message, personaCode);
        model.addAttribute("pairView", views.pair(outcome.messageId(), owner));
        return "fragments/chat-message :: pair";
    }

    @GetMapping(value = "/messages/{messageId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID messageId, HttpServletRequest request) {
        return orchestrator.connectStream(messageId, ChatOwnerResolver.current(request));
    }

    @PostMapping("/messages/{messageId}/confirm-web-search")
    public String confirm(@PathVariable UUID messageId,
                          @RequestParam(name = "questionHash", required = false) String questionHash,
                          HttpServletRequest request,
                          Model model) {
        model.addAttribute("view", consent.confirmWebSearch(
                messageId, ChatOwnerResolver.current(request), questionHash));
        return "fragments/chat-message :: aiMessage";
    }

    @PostMapping("/messages/{messageId}/cancel-web-search")
    public String cancel(@PathVariable UUID messageId,
                         HttpServletRequest request,
                         Model model) {
        model.addAttribute("view", consent.cancelWebSearch(
                messageId, ChatOwnerResolver.current(request)));
        return "fragments/chat-message :: aiMessage";
    }

    @GetMapping("/messages/{messageId}/fragment")
    public String fragment(@PathVariable UUID messageId,
                           HttpServletRequest request,
                           Model model) {
        model.addAttribute("view", consent.fragment(
                messageId, ChatOwnerResolver.current(request)));
        return "fragments/chat-message :: aiMessage";
    }

    @PostMapping("/messages/{messageId}/retry-web-search")
    public String retry(@PathVariable UUID messageId,
                        HttpServletRequest request,
                        Model model) {
        model.addAttribute("view", consent.retryWebSearch(
                messageId, ChatOwnerResolver.current(request)));
        return "fragments/chat-message :: aiMessage";
    }
}
