package vn.edu.hsf302.lawrag.llm.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hsf302.lawrag.admin.repository.AdminAuditRepository;
import vn.edu.hsf302.lawrag.llm.dto.LlmPersonaView;
import vn.edu.hsf302.lawrag.llm.entity.LlmPersona;
import vn.edu.hsf302.lawrag.llm.entity.ResponseStyle;
import vn.edu.hsf302.lawrag.llm.repository.LlmPersonaRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolves and manages configurable LLM personalities (default, active list, and admin CRUD).
 */
@Service
public class LlmPersonaService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9_]{2,64}$");

    private final LlmPersonaRepository repository;
    private final AdminAuditRepository audit;

    public LlmPersonaService(LlmPersonaRepository repository, AdminAuditRepository audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<LlmPersonaView> listActivePersonas() {
        return repository.findByActiveTrueOrderByDisplayOrderAscNameAsc()
                .stream()
                .map(LlmPersonaView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LlmPersonaView> listAllPersonas() {
        return repository.findAllByOrderByDisplayOrderAscNameAsc()
                .stream()
                .map(LlmPersonaView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, LlmPersonaView> mapAllByCode() {
        Map<String, LlmPersonaView> out = new LinkedHashMap<>();
        for (LlmPersona p : repository.findAllByOrderByDisplayOrderAscNameAsc()) {
            out.put(p.getCode(), LlmPersonaView.from(p));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public LlmPersona getDefaultPersona() {
        return repository.findFirstByDefaultPersonaTrue()
                .or(() -> repository.findFirstByActiveTrueOrderByDisplayOrderAscNameAsc())
                .orElseGet(() -> new LlmPersona(
                        "CHUYEN_NGHIEP",
                        "Chuyên nghiệp",
                        "Chuẩn mực pháp lý, khách quan, đi thẳng từ kết luận đến căn cứ điều khoản.",
                        "Trình bày bằng văn phong pháp lý chuẩn mực, khách quan, đi thẳng vào kết luận và căn cứ điều khoản.",
                        ResponseStyle.PROFESSIONAL,
                        true,
                        true,
                        1));
    }

    /**
     * Resolves user-selected persona on chat submission; rejects inactive or unknown explicit codes.
     */
    @Transactional(readOnly = true)
    public LlmPersona resolveForSelection(String requestedCode) {
        if (requestedCode == null || requestedCode.isBlank()) {
            return getDefaultPersona();
        }
        String normalized = requestedCode.strip().toUpperCase(Locale.ROOT);
        LlmPersona found = repository.findByCodeIgnoreCase(normalized)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tính cách AI: " + normalized));
        if (!found.isActive()) {
            throw new IllegalArgumentException("Tính cách AI đang tạm khóa: " + found.getName());
        }
        return found;
    }

    /**
     * Resolves a stored persona code without failing if it was later deactivated, falling back to default.
     */
    @Transactional(readOnly = true)
    public LlmPersona resolveOrDefault(String code) {
        if (code == null || code.isBlank()) {
            return getDefaultPersona();
        }
        return repository.findByCodeIgnoreCase(code.strip())
                .orElseGet(this::getDefaultPersona);
    }

    @Transactional
    public LlmPersonaView createPersona(
            String actor,
            String rawCode,
            String rawName,
            String rawDescription,
            String rawInstruction,
            ResponseStyle style,
            int displayOrder,
            boolean setAsDefault) {
        String code = normalizeCode(rawCode);
        String name = requireTrimmed(rawName, 100, "Tên tính cách");
        String description = requireTrimmed(rawDescription, 255, "Mô tả ngắn");
        String instruction = requireTrimmed(rawInstruction, 4000, "Hướng dẫn hệ thống");

        if (repository.findByCodeIgnoreCase(code).isPresent()) {
            throw new IllegalArgumentException("Mã tính cách đã tồn tại: " + code);
        }

        if (setAsDefault) {
            // Partial unique index allows only one row with is_default = true.
            repository.clearDefaultFlags();
        }

        LlmPersona saved = repository.saveAndFlush(new LlmPersona(
                code,
                name,
                description,
                instruction,
                style,
                setAsDefault,
                true,
                displayOrder));

        audit.record(actor, "LLM_PERSONA_CREATE", "LLM_PERSONA", saved.getId(),
                "{\"code\":\"" + code + "\",\"style\":\"" + saved.getResponseStyle().name()
                        + "\",\"default\":" + setAsDefault + "}");
        return LlmPersonaView.from(saved);
    }

    @Transactional
    public LlmPersonaView updatePersona(
            String actor,
            UUID id,
            String rawName,
            String rawDescription,
            String rawInstruction,
            ResponseStyle style,
            int displayOrder,
            boolean active) {
        LlmPersona persona = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tính cách AI: " + id));
        if (persona.isDefaultPersona() && !active) {
            throw new IllegalArgumentException(
                    "Không thể tắt tính cách đang làm mặc định. Hãy chọn tính cách khác làm mặc định trước.");
        }

        persona.setName(requireTrimmed(rawName, 100, "Tên tính cách"));
        persona.setDescription(requireTrimmed(rawDescription, 255, "Mô tả ngắn"));
        persona.setSystemInstruction(requireTrimmed(rawInstruction, 4000, "Hướng dẫn hệ thống"));
        persona.setResponseStyle(style);
        persona.setDisplayOrder(displayOrder);
        persona.setActive(active);

        LlmPersona saved = repository.saveAndFlush(persona);
        audit.record(actor, "LLM_PERSONA_UPDATE", "LLM_PERSONA", saved.getId(),
                "{\"code\":\"" + saved.getCode() + "\",\"style\":\"" + saved.getResponseStyle().name()
                        + "\",\"active\":" + active + "}");
        return LlmPersonaView.from(saved);
    }

    @Transactional
    public LlmPersonaView setDefaultPersona(String actor, UUID id) {
        LlmPersona persona = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tính cách AI: " + id));
        persona.setActive(true);
        repository.clearDefaultFlags();
        persona.setDefaultPersona(true);
        LlmPersona saved = repository.saveAndFlush(persona);

        audit.record(actor, "LLM_PERSONA_SET_DEFAULT", "LLM_PERSONA", saved.getId(),
                "{\"code\":\"" + saved.getCode() + "\"}");
        return LlmPersonaView.from(saved);
    }

    private static String normalizeCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new IllegalArgumentException("Mã tính cách không được để trống.");
        }
        String code = rawCode.strip().toUpperCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("Mã tính cách chỉ được gồm chữ in hoa A-Z, số 0-9 và dấu gạch dưới (_), dài 2–64 ký tự.");
        }
        return code;
    }

    private static String requireTrimmed(String raw, int maxLen, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " không được để trống.");
        }
        String clean = raw.strip();
        if (clean.length() > maxLen) {
            throw new IllegalArgumentException(label + " tối đa " + maxLen + " ký tự.");
        }
        return clean;
    }
}

