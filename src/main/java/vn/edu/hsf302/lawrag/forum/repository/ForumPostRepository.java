package vn.edu.hsf302.lawrag.forum.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.forum.entity.ForumCategory;
import vn.edu.hsf302.lawrag.forum.entity.ForumPost;

public interface ForumPostRepository extends JpaRepository<ForumPost, UUID> {

    @Query("""
            SELECT p FROM ForumPost p
            JOIN FETCH p.author
            ORDER BY p.pinned DESC, p.createdAt DESC
            """)
    List<ForumPost> findAllWithAuthor();

    @Query("""
            SELECT p FROM ForumPost p
            JOIN FETCH p.author
            WHERE p.category = :category
            ORDER BY p.pinned DESC, p.createdAt DESC
            """)
    List<ForumPost> findByCategoryWithAuthor(@Param("category") ForumCategory category);

    @Query("""
            SELECT p FROM ForumPost p
            JOIN FETCH p.author
            WHERE lower(p.title) LIKE lower(concat('%', :keyword, '%'))
               OR lower(p.content) LIKE lower(concat('%', :keyword, '%'))
            ORDER BY p.pinned DESC, p.createdAt DESC
            """)
    List<ForumPost> searchByKeywordWithAuthor(@Param("keyword") String keyword);

    @Query("""
            SELECT p FROM ForumPost p
            JOIN FETCH p.author
            WHERE p.category = :category
              AND (lower(p.title) LIKE lower(concat('%', :keyword, '%'))
                   OR lower(p.content) LIKE lower(concat('%', :keyword, '%')))
            ORDER BY p.pinned DESC, p.createdAt DESC
            """)
    List<ForumPost> searchByCategoryAndKeywordWithAuthor(@Param("category") ForumCategory category,
                                                         @Param("keyword") String keyword);

    @Query("SELECT p FROM ForumPost p JOIN FETCH p.author WHERE p.id = :id")
    Optional<ForumPost> findByIdWithAuthor(@Param("id") UUID id);
}
