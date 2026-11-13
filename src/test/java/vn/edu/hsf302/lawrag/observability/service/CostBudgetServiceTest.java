package vn.edu.hsf302.lawrag.observability.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import vn.edu.hsf302.lawrag.observability.config.ResourceBudgetProperties;

/** Kill switches must fail closed before any provider call or quota charge. */
class CostBudgetServiceTest {

    private static ResourceBudgetProperties properties(boolean crawler, boolean gemini) {
        return new ResourceBudgetProperties(crawler, gemini, 200, 2000, 20, 12000, 5, 2);
    }

    @Test
    void crawlerKillSwitchRefusesWebConfirmation() {
        CostBudgetService service = new CostBudgetService(
                mock(JdbcTemplate.class), properties(false, true), Clock.systemUTC());

        assertThatThrownBy(service::requireWebSearchJob)
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("tạm ngừng");
    }

    @Test
    void geminiKillSwitchFailsClosedForGradingAndEmbedding() {
        CostBudgetService service = new CostBudgetService(
                mock(JdbcTemplate.class), properties(true, false), Clock.systemUTC());

        assertThatThrownBy(service::requireGeminiCall)
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("không tạo câu trả lời");
        assertThatThrownBy(service::requireEmbeddingCall)
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("embedding");
    }

    @Test
    void budgetLimitsExposeTheLockedConfiguration() {
        CostBudgetService service = new CostBudgetService(
                mock(JdbcTemplate.class), properties(true, true), Clock.systemUTC());

        assertThat(service.maxTokensPerContext()).isEqualTo(12000);
        assertThat(service.maxUrlsPerJob()).isEqualTo(5);
        assertThat(service.maxConcurrentWebJobs()).isEqualTo(2);
        assertThat(service.crawlerEnabled()).isTrue();
    }
}
