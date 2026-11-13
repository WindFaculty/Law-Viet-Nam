package vn.edu.hsf302.lawrag.legaldata.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.legaldata.entity.SourceSnapshot;

public interface SourceSnapshotRepository extends JpaRepository<SourceSnapshot, UUID> {

    Optional<SourceSnapshot> findBySourceUrlAndContentSha256AndParserVersion(
            String sourceUrl, String contentSha256, String parserVersion);

    Optional<SourceSnapshot> findFirstByContentSha256AndParserVersion(
            String contentSha256, String parserVersion);
}
