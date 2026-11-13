package vn.edu.hsf302.lawrag.legaldata.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalArticle;
import vn.edu.hsf302.lawrag.legaldata.entity.VerificationStatus;

public interface LegalArticleRepository extends JpaRepository<LegalArticle, UUID> {

    List<LegalArticle> findByDocumentIdOrderByArticleCodeAsc(UUID documentId);

    Optional<LegalArticle> findByDocumentIdAndArticleCode(UUID documentId, String articleCode);

    List<LegalArticle> findByDocumentIdAndVerificationStatus(UUID documentId, VerificationStatus verificationStatus);

    List<LegalArticle> findByParentIdOrderByArticleCodeAsc(UUID parentId);
}
