package vn.edu.hsf302.lawrag.admin.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.edu.hsf302.lawrag.llm.entity.ResponseStyle;
import vn.edu.hsf302.lawrag.llm.service.LlmPersonaService;

import java.util.Locale;
import java.util.UUID;

/**
 * Admin console for configuring LLM personalities ({@code /admin/llm}).
 */
@Controller
@RequestMapping("/admin/llm")
public class AdminLlmController {

    private final LlmPersonaService personaService;

    public AdminLlmController(LlmPersonaService personaService) {
        this.personaService = personaService;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("personas", personaService.listAllPersonas());
        model.addAttribute("styles", ResponseStyle.values());
        model.addAttribute("activeAdminTab", "llm");
        return "admin/llm";
    }

    @PostMapping("/personas")
    public String create(@RequestParam(name = "code", defaultValue = "") String code,
                         @RequestParam(name = "name", defaultValue = "") String name,
                         @RequestParam(name = "description", defaultValue = "") String description,
                         @RequestParam(name = "systemInstruction", defaultValue = "") String systemInstruction,
                         @RequestParam(name = "responseStyle", defaultValue = "CUSTOM") String responseStyle,
                         @RequestParam(name = "displayOrder", defaultValue = "10") int displayOrder,
                         @RequestParam(name = "setAsDefault", defaultValue = "false") boolean setAsDefault,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        try {
            personaService.createPersona(
                    actor(authentication),
                    code,
                    name,
                    description,
                    systemInstruction,
                    parseStyle(responseStyle),
                    displayOrder,
                    setAsDefault);
            redirectAttributes.addFlashAttribute("message", "Đã tạo tính cách AI mới.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/llm";
    }

    @PostMapping("/personas/{id}/update")
    public String update(@PathVariable UUID id,
                         @RequestParam(name = "name", defaultValue = "") String name,
                         @RequestParam(name = "description", defaultValue = "") String description,
                         @RequestParam(name = "systemInstruction", defaultValue = "") String systemInstruction,
                         @RequestParam(name = "responseStyle", defaultValue = "CUSTOM") String responseStyle,
                         @RequestParam(name = "displayOrder", defaultValue = "10") int displayOrder,
                         @RequestParam(name = "active", defaultValue = "false") boolean active,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        try {
            personaService.updatePersona(
                    actor(authentication),
                    id,
                    name,
                    description,
                    systemInstruction,
                    parseStyle(responseStyle),
                    displayOrder,
                    active);
            redirectAttributes.addFlashAttribute("message", "Đã cập nhật tính cách AI.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/llm";
    }

    @PostMapping("/personas/{id}/set-default")
    public String setDefault(@PathVariable UUID id,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            var updated = personaService.setDefaultPersona(actor(authentication), id);
            redirectAttributes.addFlashAttribute("message",
                    "Đã đặt \"" + updated.name() + "\" làm tính cách mặc định của hệ thống.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/llm";
    }

    private static ResponseStyle parseStyle(String raw) {
        try {
            return ResponseStyle.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return ResponseStyle.CUSTOM;
        }
    }

    private static String actor(Authentication authentication) {
        return authentication == null ? "admin" : authentication.getName();
    }
}

