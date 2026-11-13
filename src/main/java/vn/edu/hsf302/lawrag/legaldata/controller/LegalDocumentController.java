package vn.edu.hsf302.lawrag.legaldata.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import vn.edu.hsf302.lawrag.legaldata.dto.LegalArticleDetail;
import vn.edu.hsf302.lawrag.legaldata.service.LegalArticleDetailService;
import vn.edu.hsf302.lawrag.legaldata.entity.VerificationStatus;

/**
 * Internal citation viewer. READY documents are public; QUARANTINED or
 * REJECTED documents return 404 for anonymous users and are visible to
 * ROLE_ADMIN only.
 */
@Controller
public class LegalDocumentController {

    private final LegalArticleDetailService detailService;

    public LegalDocumentController(LegalArticleDetailService detailService) {
        this.detailService = detailService;
    }

    @GetMapping("/legal/documents/{documentVersionId}/articles/{articleId}")
    public String articleDetail(@PathVariable UUID documentVersionId,
                                @PathVariable UUID articleId,
                                Authentication authentication,
                                Model model) {
        LegalArticleDetail detail = detailService.find(documentVersionId, articleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean admin = isAdmin(authentication);
        if (detail.verificationStatus() != VerificationStatus.READY
                || detail.articleVerificationStatus() != VerificationStatus.READY) {
            if (!admin) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        }
        model.addAttribute("detail", detail);
        model.addAttribute("adminView", admin);
        return "legal/article-detail";
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
