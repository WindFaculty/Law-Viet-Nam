package vn.edu.hsf302.lawrag.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;
import vn.edu.hsf302.lawrag.chat.entity.MessageCitation;

public interface MessageCitationRepository extends JpaRepository<MessageCitation, UUID> {

    List<MessageCitation> findByMessageIdOrderByCitationOrderAsc(UUID messageId);
}
