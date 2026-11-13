package vn.edu.hsf302.lawrag.ingestion.service;

import org.springframework.stereotype.Service;
import vn.edu.hsf302.lawrag.gemini.service.EmbeddingClient;
import vn.edu.hsf302.lawrag.gemini.config.GeminiProperties;
import vn.edu.hsf302.lawrag.legaldata.repository.ArticleEmbeddingRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.EmbeddingStatus;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalArticle;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalArticleRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalDocument;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalDocumentRepository;
import vn.edu.hsf302.lawrag.legaldata.service.LegalText;
import vn.edu.hsf302.lawrag.legaldata.entity.VerificationStatus;
import vn.edu.hsf302.lawrag.observability.service.CostBudgetService;
import vn.edu.hsf302.lawrag.observability.dto.Usage;
import vn.edu.hsf302.lawrag.observability.dto.UsageCategory;

import java.util.List;
import java.util.UUID;

/**
 * Idempotent embedding index for READY articles. The natural identity
 * (article, model, space, chunk, text hash) is the duplicate guard; a retried
 * job never pays for or stores the same chunk twice.
 */
@Service
public class ArticleEmbeddingIndexer {

    private final ArticleEmbeddingRepository embeddings;
    private final LegalArticleRepository articles;
    private final LegalDocumentRepository documents;
    private final EmbeddingClient embeddingClient;
    private final GeminiProperties geminiProperties;
    private final CostBudgetService budget;

    public ArticleEmbeddingIndexer(ArticleEmbeddingRepository embeddings,
                                   LegalArticleRepository articles,
                                   LegalDocumentRepository documents,
                                   EmbeddingClient embeddingClient,
                                   GeminiProperties geminiProperties,
                                   CostBudgetService budget) {
        this.embeddings = embeddings;
        this.articles = articles;
        this.documents = documents;
        this.embeddingClient = embeddingClient;
        this.geminiProperties = geminiProperties;
        this.budget = budget;
    }

    /** Web ingestion/admin approval is the only caller; attribute it to web work. */
    public int indexReadyArticles(UUID documentId) {
        return indexReadyArticles(documentId, UsageCategory.WEB_COMPLETED);
    }

    public int indexReadyArticles(UUID documentId, UsageCategory category) {
        LegalDocument document = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("legal document not found: " + documentId));
        List<LegalArticle> readyArticles = articles
                .findByDocumentIdAndVerificationStatus(documentId, VerificationStatus.READY);
        int generated = 0;
        for (LegalArticle article : readyArticles) {
            String header = embeddingHeader(document, article);
            List<String> chunks = RetrievalChunker.chunk(header, article.getSearchText());
            for (int index = 0; index < chunks.size(); index++) {
                String text = chunks.get(index);
                String textHash = LegalText.sha256Hex(text);
                if (embeddings.existsByIdentity(
                        article.getId(),
                        geminiProperties.embeddingModelId(),
                        embeddingClient.spaceId(),
                        index,
                        textHash)) {
                    budget.record(category, Usage.embeddingCacheHit(text.length()));
                    continue;
                }
                budget.requireEmbeddingCall();
                EmbeddingClient.Embedding embedding = embeddingClient.embed(
                        text, EmbeddingClient.EmbeddingTaskType.RETRIEVAL_DOCUMENT);
                budget.record(category, Usage.embedding(text.length()));
                if (embedding.dimensions() != geminiProperties.embeddingDimensions()) {
                    throw new IllegalStateException("embedding dimension mismatch for "
                            + article.getArticleCode());
                }
                embeddings.insert(
                        article.getId(),
                        index,
                        geminiProperties.embeddingModelId(),
                        embedding.spaceId(),
                        embedding.dimensions(),
                        textHash,
                        embedding.values(),
                        EmbeddingStatus.READY);
                generated++;
            }
        }
        return generated;
    }

    private static String embeddingHeader(LegalDocument document, LegalArticle article) {
        StringBuilder header = new StringBuilder(document.getTitle());
        if (document.getDocNumber() != null && !document.getDocNumber().isBlank()) {
            header.append(" (").append(document.getDocNumber()).append(')');
        }
        header.append('\n').append(article.getLocator());
        if (article.getEffectiveFrom() != null) {
            header.append("\nHiệu lực từ: ").append(article.getEffectiveFrom());
        }
        return header.toString();
    }
}
