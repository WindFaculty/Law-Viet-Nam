package vn.edu.hsf302.lawrag.retrieval.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import vn.edu.hsf302.lawrag.retrieval.dto.QueryAnalysis;

class QueryAnalyzerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final QueryAnalyzer analyzer = new QueryAnalyzer(
            Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZONE));

    @Test
    void extractsDocumentLocatorDateAndSignals() {
        QueryAnalysis analysis = analyzer.analyze(
                "Theo Điều 10 khoản 3 Luật 36/2024/QH15, ngày 01/06/2025 "
                        + "trẻ em ngồi cùng hàng ghế với người lái xe ô tô có vi phạm không?");

        assertThat(analysis.documentNumbers()).containsExactly("36/2024/QH15");
        assertThat(analysis.articleCodes()).containsExactly("dieu-10-khoan-3");
        assertThat(analysis.applicableDate()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(analysis.explicitDate()).isTrue();
        assertThat(analysis.subjects()).contains("o_to");
        assertThat(analysis.topics()).contains("ghe_tre_em");
        assertThat(analysis.requiresClarification()).isFalse();
    }

    @Test
    void yearOnlyDateUsesFirstDayOfThatYear() {
        QueryAnalysis analysis = analyzer.analyze(
                "Năm 2024, người ngồi trên xe máy có phải đội mũ bảo hiểm không?");

        assertThat(analysis.applicableDate()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(analysis.subjects()).contains("xe_mo_to");
        assertThat(analysis.topics()).contains("mu_bao_hiem");
        assertThat(analysis.requiresClarification()).isFalse();
    }

    @Test
    void ambiguousRelativeDateRequiresClarification() {
        QueryAnalysis analysis = analyzer.analyze(
                "Năm ngoái tôi đi xe máy không đội mũ bảo hiểm thì bị phạt bao nhiêu?");

        assertThat(analysis.requiresClarification()).isTrue();
        assertThat(analysis.clarificationQuestion()).contains("mốc thời gian");
    }

    @Test
    void missingVehicleAndViolationRequireClarification() {
        QueryAnalysis analysis = analyzer.analyze("Tôi bị phạt bao nhiêu tiền?");

        assertThat(analysis.requiresClarification()).isTrue();
        assertThat(analysis.clarificationQuestion()).contains("loại phương tiện", "hành vi");
    }

    @Test
    void helmetQuestionWithoutVehicleRequiresClarification() {
        QueryAnalysis analysis = analyzer.analyze("Tôi có phải đội mũ bảo hiểm không?");

        assertThat(analysis.subjects()).isEmpty();
        assertThat(analysis.requiresClarification()).isTrue();
        assertThat(analysis.clarificationQuestion()).contains("loại phương tiện");
    }

    @Test
    void distinctBicycleAndElectricBicycleAreNotConfused() {
        QueryAnalysis analysis = analyzer.analyze(
                "Người đi xe đạp máy phải đội mũ bảo hiểm không?");

        assertThat(analysis.subjects()).containsExactly("xe_dap_may");
        assertThat(analysis.requiresClarification()).isFalse();
        assertThat(analysis.topics()).contains("mu_bao_hiem");
    }

    @Test
    void fallbackSearchQueryIsRuleBasedAndClean() {
        QueryAnalysis analysis = analyzer.analyze(
                "Người điều khiển xe máy có phải đội mũ bảo hiểm không?");

        assertThat(analysis.searchQuery()).contains("xe mô tô", "mũ bảo hiểm");
        assertThat(analysis.searchQuery()).doesNotContain("?");
    }

    @Test
    void recognizesShortDecree168AliasAndTrafficLightPenaltyTopics() {
        QueryAnalysis overview = analyzer.analyze("nghị định 168 là gì");
        assertThat(overview.documentNumbers()).containsExactly("168/2024/NĐ-CP");
        assertThat(overview.requiresClarification()).isFalse();
        assertThat(overview.outOfScope()).isFalse();
        assertThat(overview.searchQuery()).contains("168/2024/NĐ-CP", "phạm vi điều chỉnh");

        QueryAnalysis redLight = analyzer.analyze(
                "Theo NĐ 168 xe máy vượt đèn đỏ bị phạt bao nhiêu tiền?");
        assertThat(redLight.documentNumbers()).containsExactly("168/2024/NĐ-CP");
        assertThat(redLight.subjects()).contains("xe_mo_to");
        assertThat(redLight.topics()).contains("muc_phat", "den_tin_hieu");
        assertThat(redLight.requiresClarification()).isFalse();

        QueryAnalysis distinctYear = analyzer.analyze("Nghị định 168/2013/NĐ-CP quy định gì?");
        assertThat(distinctYear.documentNumbers()).containsExactly("168/2013/NĐ-CP");
    }

    @Test
    void sqlAccentMappingAndVietnameseNormalizationStayAligned() {
        assertThat(VietnameseText.SQL_ACCENTED_LOWER.length())
                .isEqualTo(VietnameseText.SQL_BASE_LOWER.length());
        assertThat(VietnameseText.stripDiacritics("Điều 10 khoản 3"))
                .isEqualTo("Dieu 10 khoan 3");
        assertThat(VietnameseText.normalizeForMatching("xe đạp máy"))
                .isEqualTo("xe dap may");
    }
}
