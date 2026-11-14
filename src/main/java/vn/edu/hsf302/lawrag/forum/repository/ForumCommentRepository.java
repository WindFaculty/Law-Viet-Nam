package vn.edu.hsf302.lawrag.forum.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.forum.entity.ForumComment;

public interface ForumCommentRepository extends JpaRepository<ForumComment, UUID> {

    @Query("""
            SELECT c FROM ForumComment c
            JOIN FETCH c.author
            LEFT JOIN FETCH c.parentComment
            WHERE c.post.id = :postId
            ORDER BY c.createdAt ASC
            """)
    List<ForumComment> findByPostIdWithAuthor(@Param("postId") UUID postId);

    @Query("""
            SELECT c.post.id, COUNT(c)
            FROM ForumComment c
            WHERE c.post.id IN :postIds
            GROUP BY c.post.id
            """)
    List<Object[]> countByPostIds(@Param("postIds") List<UUID> postIds);

    @Query("""
            SELECT c.post.id, COUNT(c)
            FROM ForumComment c
            WHERE c.post.id IN :postIds
              AND c.officialAdminReply = true
            GROUP BY c.post.id
            """)
    List<Object[]> countOfficialRepliesByPostIds(@Param("postIds") List<UUID> postIds);

    boolean existsByPostIdAndOfficialAdminReplyTrue(UUID postId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ForumComment c SET c.acceptedAnswer = false WHERE c.post.id = :postId AND c.acceptedAnswer = true")
    void clearAcceptedAnswers(@Param("postId") UUID postId);

    @Query("SELECT c FROM ForumComment c JOIN FETCH c.author JOIN FETCH c.post WHERE c.id = :id")
    Optional<ForumComment> findByIdWithAuthorAndPost(@Param("id") UUID id);
}

