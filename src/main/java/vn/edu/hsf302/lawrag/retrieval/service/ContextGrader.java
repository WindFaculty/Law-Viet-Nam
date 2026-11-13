package vn.edu.hsf302.lawrag.retrieval.service;

import org.springframework.stereotype.Service;
import vn.edu.hsf302.lawrag.gemini.service.GroundingClient;
import vn.edu.hsf302.lawrag.observability.service.CostBudgetService;

import java.time.format.DateTimeFormatter;
import java.util.List;
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

        GroundingClient.Request request = new GroundingClient.Request(
                analysis.originalQuestion(),
                context,
                analysis.applicableDate(),
                analysis.subjects(),
                analysis.topics(),
                analysis.articleCodes(),
                analysis.documentNumbers(),
                temporalNotes,
                buildPrompt(analysis, context, temporalNotes),
                PROMPT_VERSION);

        return groundingClient.evaluate(request);
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
                                      List<GroundingClient.TemporalNote> temporalNotes) {
        StringBuilder prompt = new StringBuilder(4_096);
        prompt.append("Bạn là bộ đánh giá căn cứ pháp luật giao thông đường bộ Việt Nam.\n")
                .append("Chỉ được chọn articleId có trong danh sách ngữ cảnh bên dưới. ")
                .append("Không được tạo URL, số hiệu hoặc ID mới. Không bật công cụ tìm kiếm.\n")
                .append("Quyết định ANSWER chỉ khi ngữ cảnh đủ căn cứ đúng đối tượng, ")
                .append("phạm vi và ngày áp dụng; nếu thiếu văn bản thì NEED_WEB_SEARCH; ")
                .append("nếu điều khoản chưa có hiệu lực hoặc hiệu lực không rõ thì ")
                .append("TEMPORAL_UNCERTAINTY; nếu câu hỏi thiếu dữ kiện quyết định thì ")
                .append("NEED_CLARIFICATION.\n")
                .append("Ngày áp dụng: ").append(analysis.applicableDate().format(DATE)).append('\n')
                .append("Câu hỏi: ").append(analysis.originalQuestion()).append('\n')
                .append("Tín hiệu: đối tượng=").append(analysis.subjects())
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
                .append("suggestedSearchQuery, clarificationQuestion.");
        return prompt.toString();
    }
}
