package vn.edu.hsf302.lawrag.admin.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import vn.edu.hsf302.lawrag.admin.dto.AdminAuditPageView;
import vn.edu.hsf302.lawrag.admin.dto.CostUsageLogView;
import vn.edu.hsf302.lawrag.admin.dto.RagTracePageView;
import vn.edu.hsf302.lawrag.admin.service.AdminLogService;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Unified System Logs viewer ({@code /admin/logs}) covering Audit Logs, RAG Traces, and Daily API Usage.
 */
@Controller
public class AdminLogController {

    private final AdminLogService logService;

    public AdminLogController(AdminLogService logService) {
        this.logService = logService;
    }

    @GetMapping("/admin/logs")
    public String logs(@RequestParam(name = "tab", defaultValue = "audit") String rawTab,
                       @RequestParam(name = "actor", required = false) String actor,
                       @RequestParam(name = "action", required = false) String action,
                       @RequestParam(name = "entityType", required = false) String entityType,
                       @RequestParam(name = "decision", required = false) String decision,
                       @RequestParam(name = "q", required = false) String query,
                       @RequestParam(name = "fromDate", required = false) String fromDateStr,
                       @RequestParam(name = "toDate", required = false) String toDateStr,
                       @RequestParam(name = "category", required = false) String category,
                       @RequestParam(name = "page", defaultValue = "1") int page,
                       Model model) {
        String tab = normalizeTab(rawTab);

        if ("rag".equals(tab)) {
            RagTracePageView ragPage = logService.searchRagTraces(decision, query, page);
            model.addAttribute("ragPage", ragPage);
            model.addAttribute("ragRows", ragPage.rows());
        } else if ("usage".equals(tab)) {
            LocalDate fromDate = parseDateOrNull(fromDateStr);
            LocalDate toDate = parseDateOrNull(toDateStr);
            List<CostUsageLogView> usageRows = logService.listCostUsage(fromDate, toDate, category);
            model.addAttribute("usageRows", usageRows);
            model.addAttribute("usageSummary", logService.summarizeCostUsage(usageRows));
            model.addAttribute("usageCategories", logService.distinctUsageCategories());
        } else {
            AdminAuditPageView auditPage = logService.searchAuditLogs(actor, action, entityType, page);
            model.addAttribute("auditPage", auditPage);
            model.addAttribute("auditRows", auditPage.rows());
            model.addAttribute("auditActions", logService.distinctAuditActions());
            model.addAttribute("auditEntityTypes", logService.distinctAuditEntityTypes());
        }

        model.addAttribute("logTab", tab);
        model.addAttribute("actor", actor == null ? "" : actor.strip());
        model.addAttribute("action", action == null ? "" : action.strip());
        model.addAttribute("entityType", entityType == null ? "" : entityType.strip());
        model.addAttribute("decision", decision == null ? "" : decision.strip());
        model.addAttribute("query", query == null ? "" : query.strip());
        model.addAttribute("fromDate", fromDateStr == null ? "" : fromDateStr.strip());
        model.addAttribute("toDate", toDateStr == null ? "" : toDateStr.strip());
        model.addAttribute("category", category == null ? "" : category.strip());
        model.addAttribute("activeAdminTab", "logs");
        return "admin/logs";
    }

    private static String normalizeTab(String raw) {
        if (raw == null) {
            return "audit";
        }
        String lower = raw.strip().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "rag", "usage" -> lower;
            default -> "audit";
        };
    }

    private static LocalDate parseDateOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}

