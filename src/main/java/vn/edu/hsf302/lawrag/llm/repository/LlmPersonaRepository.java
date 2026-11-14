package vn.edu.hsf302.lawrag.llm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import vn.edu.hsf302.lawrag.llm.entity.LlmPersona;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LlmPersonaRepository extends JpaRepository<LlmPersona, UUID> {

    List<LlmPersona> findAllByOrderByDisplayOrderAscNameAsc();

    List<LlmPersona> findByActiveTrueOrderByDisplayOrderAscNameAsc();

    Optional<LlmPersona> findByCodeIgnoreCase(String code);

    Optional<LlmPersona> findFirstByDefaultPersonaTrue();

    Optional<LlmPersona> findFirstByActiveTrueOrderByDisplayOrderAscNameAsc();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE LlmPersona p SET p.defaultPersona = false WHERE p.defaultPersona = true")
    void clearDefaultFlags();
}

