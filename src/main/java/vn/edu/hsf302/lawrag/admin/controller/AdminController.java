package vn.edu.hsf302.lawrag.admin.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.edu.hsf302.lawrag.auth.entity.UserRole;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import vn.edu.hsf302.lawrag.admin.service.AdminApprovalException;
import vn.edu.hsf302.lawrag.admin.service.AdminDashboardService;
import vn.edu.hsf302.lawrag.admin.service.AdminService;
import vn.edu.hsf302.lawrag.admin.service.AdminUserService;
import vn.edu.hsf302.lawrag.admin.dto.ApprovalCommand;
import vn.edu.hsf302.lawrag.admin.dto.Dashboard;
import vn.edu.hsf302.lawrag.admin.dto.PdfUploadCommand;
import vn.edu.hsf302.lawrag.admin.dto.UserAccountView;
import vn.edu.hsf302.lawrag.llm.service.LlmPersonaService;

/**
 * Core admin console routes: dashboard overview, RAG PDF management / document
 * quarantine approval gate, and user management. All routes require ROLE_ADMIN.
 */
@Controller
public class AdminController {

    private final AdminService adminService;
    private final AdminUserService adminUserService;
    private final AdminDashboardService dashboardService;
    private final LlmPersonaService personaService;

    public AdminController(AdminService adminService,
                           AdminUserService adminUserService,
                           AdminDashboardService dashboardService,
                           LlmPersonaService personaService) {
        this.adminService = adminService;
        this.adminUserService = adminUserService;
        this.dashboardService = dashboardService;
        this.personaService = personaService;
    }

    @GetMapping({"/admin", "/admin/"})
    public String index(Model model) {
        populate(model);
        model.addAttribute("stats", dashboardService.loadStats());
        model.addAttribute("personas", personaService.listAllPersonas());
        model.addAttribute("activeAdminTab", "overview");
        return "admin/index";
    }

    @GetMapping({"/admin/jobs", "/admin/web-search-jobs", "/admin/documents", "/admin/pdfs"})
    public String webSearchJobs(Model model) {
        populate(model);
        model.addAttribute("activeAdminTab", "jobs");
        return "admin/jobs";
    }

    @GetMapping("/admin/users")
    public String users(@RequestParam(name = "q", required = false) String query, Model model) {
        String cleanQuery = query == null ? "" : query.strip();
        List<UserAccountView> allUsers = adminUserService.listUsers();
        List<UserAccountView> filtered = cleanQuery.isEmpty()
                ? allUsers
                : allUsers.stream()
                        .filter(u -> u.username().toLowerCase(Locale.ROOT).contains(cleanQuery.toLowerCase(Locale.ROOT))
                                || u.displayName().toLowerCase(Locale.ROOT).contains(cleanQuery.toLowerCase(Locale.ROOT)))
                        .toList();
        model.addAttribute("users", filtered);
        model.addAttribute("totalUserCount", allUsers.size());
        model.addAttribute("query", cleanQuery);
        model.addAttribute("activeAdminTab", "users");
        return "admin/users";
    }

    @PostMapping("/admin/legal-documents/{documentVersionId}/approve")
    public String approve(@PathVariable UUID documentVersionId,
                          @RequestParam(name = "docNumber", required = false) String docNumber,
                          @RequestParam(name = "issuingBody", required = false) String issuingBody,
                          @RequestParam(name = "issueDate", required = false) String issueDate,
                          @RequestParam(name = "effectiveFrom", required = false) String effectiveFrom,
                          @RequestParam(name = "effectiveTo", required = false) String effectiveTo,
                          @RequestParam(name = "effectiveStatus", required = false) String effectiveStatus,
                          @RequestParam(name = "reason", required = false) String reason,
                          Authentication authentication,
                          RedirectAttributes redirectAttributes) {
        try {
            adminService.approve(documentVersionId,
                    new ApprovalCommand(
                            docNumber, issuingBody, issueDate,
                            effectiveFrom, effectiveTo, effectiveStatus, reason),
                    authentication == null ? "admin" : authentication.getName());
            redirectAttributes.addFlashAttribute("message",
                    "Đã duyệt văn bản " + documentVersionId + " sang READY và ghi audit.");
        } catch (AdminApprovalException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/web-search-jobs";
    }

    @PostMapping("/admin/legal-documents/upload")
    public String uploadPdf(@RequestParam("file") MultipartFile file,
                            @RequestParam(name = "title", required = false) String title,
                            @RequestParam(name = "docNumber", required = false) String docNumber,
                            @RequestParam(name = "issuingBody", required = false) String issuingBody,
                            @RequestParam(name = "issueDate", required = false) String issueDate,
                            @RequestParam(name = "effectiveFrom", required = false) String effectiveFrom,
                            @RequestParam(name = "effectiveTo", required = false) String effectiveTo,
                            @RequestParam(name = "effectiveStatus", required = false) String effectiveStatus,
                            @RequestParam(name = "sourceUrl", required = false) String sourceUrl,
                            @RequestParam(name = "runEmbedding", defaultValue = "false") boolean runEmbedding,
                            Authentication authentication,
                            RedirectAttributes redirectAttributes) {
        try {
            UUID documentId = adminService.uploadPdfDocument(
                    file,
                    new PdfUploadCommand(
                            title, docNumber, issuingBody, issueDate,
                            effectiveFrom, effectiveTo, effectiveStatus, sourceUrl, runEmbedding),
                    authentication == null ? "admin" : authentication.getName());
            redirectAttributes.addFlashAttribute("message",
                    "Đã tải lên tệp PDF và nạp vào kho RAG (" + documentId + ").");
        } catch (AdminApprovalException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/web-search-jobs";
    }

    @PostMapping("/admin/legal-documents/{documentVersionId}/embed")
    public String runEmbedding(@PathVariable UUID documentVersionId,
                               Authentication authentication,
                               RedirectAttributes redirectAttributes) {
        try {
            int totalVectors = adminService.runEmbedding(
                    documentVersionId,
                    authentication == null ? "admin" : authentication.getName());
            redirectAttributes.addFlashAttribute("message",
                    "Đã chạy embedding cho văn bản " + documentVersionId
                            + " (" + totalVectors + " vectors READY).");
        } catch (AdminApprovalException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/web-search-jobs";
    }

    @PostMapping("/admin/legal-documents/embed-all")
    public String runEmbeddingForAll(Authentication authentication,
                                     RedirectAttributes redirectAttributes) {
        try {
            int totalVectors = adminService.runEmbeddingForAllReady(
                    authentication == null ? "admin" : authentication.getName());
            redirectAttributes.addFlashAttribute("message",
                    "Đã chạy embedding cho toàn bộ tài liệu READY (tổng " + totalVectors + " vectors).");
        } catch (AdminApprovalException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/web-search-jobs";
    }

    @PostMapping("/admin/legal-documents/{documentVersionId}/delete")
    public String deleteDocument(@PathVariable UUID documentVersionId,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        try {
            adminService.deleteDocument(
                    documentVersionId,
                    authentication == null ? "admin" : authentication.getName());
            redirectAttributes.addFlashAttribute("message",
                    "Đã xóa tài liệu PDF " + documentVersionId + " khỏi kho RAG.");
        } catch (AdminApprovalException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/web-search-jobs";
    }

    @PostMapping("/admin/users/{userId}/role")
    public String updateUserRole(@PathVariable UUID userId,
                                 @RequestParam("role") String roleParam,
                                 @RequestParam(name = "returnTo", required = false) String returnTo,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        try {
            UserRole targetRole = UserRole.valueOf(roleParam.strip().toUpperCase());
            UserRole updated = adminUserService.updateRole(
                    userId,
                    targetRole,
                    authentication == null ? "admin" : authentication.getName());
            redirectAttributes.addFlashAttribute("message",
                    "Đã cập nhật vai trò tài khoản sang " + updated.name() + ".");
        } catch (IllegalArgumentException | AdminApprovalException exception) {
            redirectAttributes.addFlashAttribute("error",
                    exception instanceof AdminApprovalException ? exception.getMessage() : "Vai trò không hợp lệ.");
        }
        return "users".equals(returnTo) ? "redirect:/admin/users" : "redirect:/admin#users-heading";
    }

    @PostMapping("/admin/users/{userId}/toggle-enabled")
    public String toggleUserEnabled(@PathVariable UUID userId,
                                    @RequestParam(name = "returnTo", required = false) String returnTo,
                                    Authentication authentication,
                                    RedirectAttributes redirectAttributes) {
        try {
            boolean enabled = adminUserService.toggleEnabled(
                    userId,
                    authentication == null ? "admin" : authentication.getName());
            redirectAttributes.addFlashAttribute("message",
                    enabled ? "Đã mở khóa tài khoản." : "Đã khóa tài khoản.");
        } catch (AdminApprovalException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "users".equals(returnTo) ? "redirect:/admin/users" : "redirect:/admin#users-heading";
    }

    private void populate(Model model) {
        Dashboard dashboard = adminService.dashboard();
        model.addAttribute("jobs", dashboard.jobs());
        model.addAttribute("ragDocuments", dashboard.ragDocuments());
        model.addAttribute("quarantinedDocuments", dashboard.quarantinedDocuments());
        model.addAttribute("auditRows", dashboard.audit());
        model.addAttribute("users", adminUserService.listUsers());
    }
}
