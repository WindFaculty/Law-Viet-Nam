package vn.edu.hsf302.lawrag.retrieval.service;

import org.springframework.stereotype.Service;
import vn.edu.hsf302.lawrag.gemini.service.GroundingClient;
import vn.edu.hsf302.lawrag.observability.service.CostBudgetService;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import vn.edu.hsf302.lawrag.llm.entity.LlmPersona;
import vn.edu.hsf302.lawrag.retrieval.dto.ClarificationTurn;
import vn.edu.hsf302.lawrag.retrieval.dto.QueryAnalysis;
import vn.edu.hsf302.lawrag.retrieval.dto.RetrievalCandidate;
import vn.edu.hsf302.lawrag.retrieval.dto.RetrievalResult;

/**
 * Builds the versioned, numbered context and delegates the decision to either
 * the real Gemini adapter or the deterministic offline grader. No Google
 * Search/URL grounding tool is requested here.
 */
@Service
public class ContextGrader {

    public static final String PROMPT_VERSION = "context-grader-v1";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final GroundingClient groundingClient;
    private final CostBudgetService budget;

    public ContextGrader(GroundingClient groundingClient, CostBudgetService budget) {
        this.groundingClient = groundingClient;
        this.budget = budget;
    }

    public GroundingClient.Decision grade(QueryAnalysis analysis, RetrievalResult retrieval) {
        return grade(analysis, retrieval, null);
    }

    public GroundingClient.Decision grade(
            QueryAnalysis analysis,
            RetrievalResult retrieval,
            LlmPersona persona) {
        return grade(analysis, retrieval, persona, List.of());
    }

    public GroundingClient.Decision grade(
            QueryAnalysis analysis,
            RetrievalResult retrieval,
            LlmPersona persona,
            List<ClarificationTurn> clarificationHistory) {
        GroundingClient.Request request = buildGroundingRequest(
                analysis, retrieval, persona, clarificationHistory);
        return groundingClient.evaluate(request);
    }

    public GroundingClient.Decision gradeStream(
            QueryAnalysis analysis,
            RetrievalResult retrieval,
            LlmPersona persona,
            Consumer<String> onAnswerDelta) {
        return gradeStream(analysis, retrieval, persona, List.of(), onAnswerDelta);
    }

    public GroundingClient.Decision gradeStream(
            QueryAnalysis analysis,
            RetrievalResult retrieval,
            LlmPersona persona,
            List<ClarificationTurn> clarificationHistory,
            Consumer<String> onAnswerDelta) {
        GroundingClient.Request request = buildGroundingRequest(
                analysis, retrieval, persona, clarificationHistory);
        return groundingClient.evaluateStream(request, onAnswerDelta);
    }

    private GroundingClient.Request buildGroundingRequest(
            QueryAnalysis analysis,
            RetrievalResult retrieval,
            LlmPersona persona,
            List<ClarificationTurn> clarificationHistory) {
        // Fail closed: no unvalidated answer may be published when the grader is
        // disabled or its daily budget is exhausted.
        budget.requireGeminiCall();
        List<GroundingClient.ContextArticle> context = toContext(retrieval.contextCandidates());
        List<GroundingClient.TemporalNote> temporalNotes = retrieval.temporalIssues().stream()
                .map(issue -> new GroundingClient.TemporalNote(
                        issue.articleId() == null ? null : issue.articleId().toString(),
                        issue.articleCode(),
                        issue.locator(),
                        issue.effectiveFrom(),
                        issue.effectiveTo(),
                        issue.note()))
                .toList();

        return new GroundingClient.Request(
                analysis.originalQuestion(),
                context,
                analysis.applicableDate(),
                analysis.subjects(),
                analysis.topics(),
                analysis.articleCodes(),
                analysis.documentNumbers(),
                temporalNotes,
                buildPrompt(analysis, context, temporalNotes, persona, clarificationHistory),
                PROMPT_VERSION);
    }

    private List<GroundingClient.ContextArticle> toContext(List<RetrievalCandidate> candidates) {
        return java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(index -> {
                    RetrievalCandidate candidate = candidates.get(index);
                    return new GroundingClient.ContextArticle(
                            index + 1,
                            candidate.articleId().toString(),
                            candidate.documentVersionId().toString(),
                            candidate.documentNumber(),
                            candidate.documentTitle(),
                            candidate.articleCode(),
                            candidate.hierarchyLevel().name(),
                            candidate.locator(),
                            candidate.scope(),
                            candidate.effectiveFrom(),
                            candidate.effectiveTo(),
                            candidate.rawText());
                })
                .toList();
    }

    private static String buildPrompt(QueryAnalysis analysis,
                                      List<GroundingClient.ContextArticle> context,
                                      List<GroundingClient.TemporalNote> temporalNotes,
                                      LlmPersona persona,
                                      List<ClarificationTurn> clarificationHistory) {
        StringBuilder prompt = new StringBuilder(4_096);
        prompt.append("Bạn là trợ lý pháp luật giao thông đường bộ Việt Nam.\n")
                .append("Nhiệm vụ: đọc kỹ các điều khoản pháp luật trong phần NGỮ CẢNH RAG bên dưới để trả lời câu hỏi của người dùng.\n");
        if (persona != null) {
            prompt.append("Phong cách trả lời mong muốn (")
                    .append(persona.getName())
                    .append(" — ")
                    .append(persona.getResponseStyle().name())
                    .append("): ")
                    .append(persona.getSystemInstruction())
                    .append('\n')
                    .append("Lưu ý: Phong cách chỉ điều chỉnh giọng văn và độ chi tiết của trường answer; vẫn TUYỆT ĐỐI giữ nguyên mọi quy tắc grounding bên dưới.\n");
        }
        prompt.append("Quy tắc bắt buộc:\n")
                .append("1. Chỉ được sử dụng thông tin và articleId có trong NGỮ CẢNH RAG bên dưới. ")
                .append("Tuyệt đối không dùng kiến thức ngoài ngữ cảnh, không tạo URL, số hiệu hoặc ID mới. Không bật công cụ tìm kiếm.\n")
                .append("2. Nếu NGỮ CẢNH RAG có đủ căn cứ đúng đối tượng, hành vi, phạm vi và ngày áp dụng để trả lời câu hỏi:\n")
                .append("   - decision = \"ANSWER\", reasonCode = \"SUFFICIENT\".\n")
                .append("   - selectedArticleIds = danh sách UUID articleId chính xác từ ngữ cảnh đã dùng để trả lời.\n")
                .append("   - answer = câu trả lời bằng tiếng Việt rõ ràng, chính xác, trả lời trực tiếp câu hỏi và nêu rõ căn cứ Điều/Khoản/Điểm + số hiệu văn bản dựa hoàn toàn trên ngữ cảnh RAG.\n")
                .append("3. Nếu NGỮ CẢNH RAG không có văn bản/điều khoản phù hợp hoặc sai đối tượng/phạm vi hoặc điều khoản chưa có hiệu lực/đã hết hiệu lực tại ngày áp dụng:\n")
                .append("   - decision = \"NEED_WEB_SEARCH\", selectedArticleIds = [], answer = \"\".\n")
                .append("   - reasonCode = \"NO_MATCH\" (thiếu văn bản), \"WRONG_SCOPE\" (sai đối tượng/phạm vi), hoặc \"TEMPORAL_UNCERTAINTY\" (chưa/hết hiệu lực).\n")
                .append("   - suggestedSearchQuery = từ khóa tìm kiếm tiếng Việt ngắn gọn trên cổng văn bản Chính phủ.\n")
                .append("4. Nếu câu hỏi thiếu dữ kiện quyết định nên chưa thể xác định quy định áp dụng:\n")
                .append("   - decision = \"NEED_CLARIFICATION\", selectedArticleIds = [], reasonCode = \"MISSING_FACTS\", answer = \"\".\n")
                .append("   - clarificationQuestion = câu hỏi làm rõ ngắn gọn.\n");
        if (clarificationHistory != null && !clarificationHistory.isEmpty()) {
            prompt.append("   - LƯU Ý GIỮ NGỮ CẢNH: Phải tổng hợp đầy đủ dữ kiện từ câu hỏi gốc ban đầu và tất cả các lượt trả lời làm rõ bên dưới. ")
                    .append("TUYỆT ĐỐI không hỏi lại các dữ kiện mà người dùng đã cung cấp ở lượt trước. ")
                    .append("Nếu ngữ cảnh RAG hiện tại không có điều khoản chi tiết hơn để phân biệt, hãy chuyển sang decision = \"NEED_WEB_SEARCH\" (reasonCode = \"NO_MATCH\" hoặc \"WRONG_SCOPE\") thay vì lặp lại \"NEED_CLARIFICATION\".\n");
        }
        prompt.append("Ngày áp dụng: ").append(analysis.applicableDate().format(DATE)).append('\n')
                .append("Câu hỏi: ").append(analysis.originalQuestion()).append('\n');
        if (clarificationHistory != null && !clarificationHistory.isEmpty()) {
            prompt.append("Lịch sử hội thoại làm rõ trước đó trong cùng phiên:\n");
            for (int index = 0; index < clarificationHistory.size(); index++) {
                ClarificationTurn turn = clarificationHistory.get(index);
                prompt.append("- Lượt ").append(index + 1)
                        .append(" — Người dùng: ").append(turn.userQuestion()).append('\n');
                if (turn.aiClarification() != null && !turn.aiClarification().isBlank()) {
                    prompt.append("  Hệ thống hỏi làm rõ: ").append(turn.aiClarification()).append('\n');
                }
            }
            prompt.append("Phản hồi bổ sung mới nhất của người dùng: ")
                    .append(extractLatestUserReply(analysis.originalQuestion(), clarificationHistory))
                    .append('\n');
        }
        prompt.append("Tín hiệu: đối tượng=").append(analysis.subjects())
                .append("; chủ đề=").append(analysis.topics())
                .append("; số hiệu=").append(analysis.documentNumbers())
                .append("; điều khoản yêu cầu=").append(analysis.articleCodes())
                .append('\n');

        if (temporalNotes.isEmpty()) {
            prompt.append("Ghi chú hiệu lực: không có.\n");
        } else {
            prompt.append("Ghi chú hiệu lực (không đưa các mục này vào selectedArticleIds):\n");
            for (GroundingClient.TemporalNote note : temporalNotes) {
                prompt.append("- ").append(note.locator()).append(" (").append(note.articleCode())
                        .append("): ").append(note.note()).append('\n');
            }
        }

        prompt.append("\nNgữ cảnh đã đánh số:\n");
        if (context.isEmpty()) {
            prompt.append("(không có ứng viên đủ điều kiện)\n");
        }
        for (GroundingClient.ContextArticle article : context) {
            prompt.append('[').append(article.ordinal()).append("] articleId=")
                    .append(article.articleId())
                    .append(" | văn bản=").append(article.documentTitle())
                    .append(" | số hiệu=").append(article.documentNumber())
                    .append(" | vị trí=").append(article.locator())
                    .append(" | cấp=").append(article.hierarchyLevel())
                    .append(" | hiệu lực từ=").append(article.effectiveFrom())
                    .append(" đến=").append(article.effectiveTo())
                    .append(" | phạm vi=").append(article.scope())
                    .append("\nNguyên văn: ").append(article.rawText()).append('\n');
        }

        prompt.append("\nTrả về JSON theo schema: decision, selectedArticleIds, reasonCode, ")
                .append("answer, suggestedSearchQuery, clarificationQuestion.");
        return prompt.toString();
    }

    private static String extractLatestUserReply(String combinedQuestion,
                                                 List<ClarificationTurn> clarificationHistory) {
        if (combinedQuestion == null) {
            return "";
        }
        List<String> priorSegments = new ArrayList<>();
        String lastAdded = null;
        for (ClarificationTurn turn : clarificationHistory) {
            String segment = turn.userQuestion() == null ? "" : turn.userQuestion().strip();
            if (!segment.isEmpty() && !segment.equals(lastAdded)) {
                priorSegments.add(segment);
                lastAdded = segment;
            }
        }
        String prefix = String.join(" — ", priorSegments);
        if (!prefix.isEmpty()) {
            if (combinedQuestion.equals(prefix)) {
                return priorSegments.get(priorSegments.size() - 1);
            }
            if (combinedQuestion.startsWith(prefix + " — ")) {
                return combinedQuestion.substring(prefix.length() + 3);
            }
        }
        int lastSeparator = combinedQuestion.lastIndexOf(" — ");
        return lastSeparator >= 0 ? combinedQuestion.substring(lastSeparator + 3) : combinedQuestion;
    }
}
