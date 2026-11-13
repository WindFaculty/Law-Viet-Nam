package vn.edu.hsf302.lawrag.legaldata.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalDocumentRelation;

public interface LegalDocumentRelationRepository extends JpaRepository<LegalDocumentRelation, UUID> {

    List<LegalDocumentRelation> findBySourceDocumentId(UUID sourceDocumentId);

    List<LegalDocumentRelation> findByTargetDocumentId(UUID targetDocumentId);
}
