package vn.edu.hsf302.lawrag.ingestion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vn.edu.hsf302.lawrag.ingestion.dto.CorpusImportResult;

/**
 * Optional local/staging seed. Disabled by default; enable with
 * {@code app.ingestion.enabled=true}. Re-running is safe because the importer
 * is idempotent.
 */
@Component
@ConditionalOnProperty(prefix = "app.ingestion", name = "enabled", havingValue = "true")
public class CorpusSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CorpusSeedRunner.class);

    private final CorpusImportService corpusImportService;

    public CorpusSeedRunner(CorpusImportService corpusImportService) {
        this.corpusImportService = corpusImportService;
    }

    @Override
    public void run(ApplicationArguments args) {
        CorpusImportResult result = corpusImportService.importCorpus();
        log.info("Corpus import complete: version={}, documentsCreated={}, documentsReused={}, "
                        + "articlesCreated={}, articlesReused={}, embeddingsGenerated={}, embeddingsReused={}",
                result.corpusVersion(), result.documentsCreated(), result.documentsReused(),
                result.articlesCreated(), result.articlesReused(),
                result.embeddingsGenerated(), result.embeddingsReused());
    }
}
