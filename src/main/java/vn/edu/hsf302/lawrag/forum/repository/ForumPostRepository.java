package vn.edu.hsf302.lawrag.forum.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.forum.entity.ForumCategory;
import vn.edu.hsf302.lawrag.forum.entity.ForumPost;
import vn.edu.hsf302.lawrag.forum.entity.ForumPostStatus;
import vn.edu.hsf302.lawrag.forum.entity.ForumRequestStatus;

public interface ForumPostRepository extends JpaRepository<ForumPost, UUID>, JpaSpecificationExecutor<ForumPost> {

    @EntityGraph(attributePaths = "author")
    @Query("""
            SELECT p FROM ForumPost p
            WHERE p.status = vn.edu.hsf302.lawrag.forum.entity.ForumPostStatus.PUBLISHED
            ORDER BY p.pinned DESC, p.createdAt DESC
            """)
    Page<ForumPost> findAllWithAuthor(Pageable pageable);

    @EntityGraph(attributePaths = "author")
    @Query("""
            SELECT p FROM ForumPost p
            WHERE p.status = vn.edu.hsf302.lawrag.forum.entity.ForumPostStatus.PUBLISHED
              AND p.category = :category
            ORDER BY p.pinned DESC, p.createdAt DESC
            """)
    Page<ForumPost> findByCategoryWithAuthor(@Param("category") ForumCategory category, Pageable pageable);

    @EntityGraph(attributePaths = "author")
    @Query("""
            SELECT p FROM ForumPost p
            WHERE p.status = vn.edu.hsf302.lawrag.forum.entity.ForumPostStatus.PUBLISHED
              AND (lower(p.title) LIKE lower(concat('%', :keyword, '%'))
                   OR lower(p.content) LIKE lower(concat('%', :keyword, '%')))
            ORDER BY p.pinned DESC, p.createdAt DESC
            """)
    Page<ForumPost> searchByKeywordWithAuthor(@Param("keyword") String keyword, Pageable pageable);

    @EntityGraph(attributePaths = "author")
    @Query("""
            SELECT p FROM ForumPost p
            WHERE p.status = vn.edu.hsf302.lawrag.forum.entity.ForumPostStatus.PUBLISHED
              AND p.category = :category
              AND (lower(p.title) LIKE lower(concat('%', :keyword, '%'))
                   OR lower(p.content) LIKE lower(concat('%', :keyword, '%')))
            ORDER BY p.pinned DESC, p.createdAt DESC
            """)
    Page<ForumPost> searchByCategoryAndKeywordWithAuthor(@Param("category") ForumCategory category,
                                                         @Param("keyword") String keyword,
                                                         Pageable pageable);

    @Query("SELECT p FROM ForumPost p JOIN FETCH p.author WHERE p.id = :id")
    Optional<ForumPost> findByIdWithAuthor(@Param("id") UUID id);

    @Override
    @EntityGraph(attributePaths = "author")
    Page<ForumPost> findAll(Specification<ForumPost> spec, Pageable pageable);

    long countByStatus(ForumPostStatus status);

    long countByStatusAndRequestStatus(ForumPostStatus status, ForumRequestStatus requestStatus);
}
