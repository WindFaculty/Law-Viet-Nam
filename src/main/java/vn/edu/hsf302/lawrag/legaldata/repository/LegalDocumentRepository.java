package vn.edu.hsf302.lawrag.legaldata.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalDocument;
import vn.edu.hsf302.lawrag.legaldata.entity.VerificationStatus;

public interface LegalDocumentRepository extends JpaRepository<LegalDocument, UUID> {

    List<LegalDocument> findByDocumentKeyOrderByEffectiveFromDesc(String documentKey);

    Optional<LegalDocument> findByDocumentKeyAndSnapshotId(String documentKey, UUID snapshotId);

    List<LegalDocument> findByVerificationStatus(VerificationStatus verificationStatus);
}
