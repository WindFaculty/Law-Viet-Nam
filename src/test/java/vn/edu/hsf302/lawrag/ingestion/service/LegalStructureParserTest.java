package vn.edu.hsf302.lawrag.ingestion.service;

import org.junit.jupiter.api.Test;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalHierarchyLevel;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegalStructureParserTest {

    private final LegalStructureParser parser = new LegalStructureParser();

    private static final String SAMPLE = """
            Chương I
            NHỮNG QUY ĐỊNH CHUNG

            Điều 10. Chấp hành quy định về trật tự, an toàn giao thông đường bộ
            1. Khoản một.
            2. Khoản hai:
            a) Điểm a;
            b) Điểm b.
            3. Khoản ba.

            Điều 11. Tiêu đề điều mười một
            1. Nội dung điều mười một.
            """;

    @Test
    void parsesDieuKhoanDiemTreeWithExactLocatorsAndParents() {
        LegalStructureParser.ParsedDocument parsed = parser.parse(
                SAMPLE,
                LocalDate.of(2025, 1, 1),
                null,
                true,
                Map.of("dieu-10-khoan-3",
                        new LegalStructureParser.EffectiveDateOverride(LocalDate.of(2026, 1, 1), null)));

        assertThat(parsed.articleCount()).isEqualTo(2);
        assertThat(parsed.units()).hasSize(8);

        LegalStructureParser.ParsedUnit article = unit(parsed, "dieu-10");
        assertThat(article.level()).isEqualTo(LegalHierarchyLevel.DIEU);
        assertThat(article.title()).isEqualTo("Chấp hành quy định về trật tự, an toàn giao thông đường bộ");
        assertThat(article.locator()).isEqualTo("Điều 10");
        assertThat(article.scope()).isEqualTo("Chương I. NHỮNG QUY ĐỊNH CHUNG");
        assertThat(article.rawText()).startsWith("Điều 10.");
        assertThat(article.rawText()).contains("3. Khoản ba.");

        LegalStructureParser.ParsedUnit clause = unit(parsed, "dieu-10-khoan-2");
        assertThat(clause.level()).isEqualTo(LegalHierarchyLevel.KHOAN);
        assertThat(clause.parentCode()).isEqualTo("dieu-10");
        assertThat(clause.locator()).isEqualTo("Điều 10 khoản 2");
        assertThat(clause.rawText()).contains("a) Điểm a;").contains("b) Điểm b.");

        LegalStructureParser.ParsedUnit point = unit(parsed, "dieu-10-khoan-2-diem-a");
        assertThat(point.level()).isEqualTo(LegalHierarchyLevel.DIEM);
        assertThat(point.parentCode()).isEqualTo("dieu-10-khoan-2");
        assertThat(point.locator()).isEqualTo("Điều 10 khoản 2 điểm a");
        assertThat(point.rawText()).isEqualTo("a) Điểm a;");

        LegalStructureParser.ParsedUnit laterArticle = unit(parsed, "dieu-11");
        assertThat(laterArticle.rawText()).doesNotContain("Khoản ba");
        assertThat(unit(parsed, "dieu-11-khoan-1").parentCode()).isEqualTo("dieu-11");
    }

    @Test
    void appliesClauseEffectiveOverrideAndInheritsToChildren() {
        LegalStructureParser.ParsedDocument parsed = parser.parse(
                SAMPLE,
                LocalDate.of(2025, 1, 1),
                null,
                true,
                Map.of("dieu-10-khoan-2",
                        new LegalStructureParser.EffectiveDateOverride(LocalDate.of(2026, 1, 1), null)));

        assertThat(unit(parsed, "dieu-10-khoan-2").effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(unit(parsed, "dieu-10-khoan-2-diem-a").effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(unit(parsed, "dieu-10-khoan-1").effectiveFrom()).isEqualTo(LocalDate.of(2025, 1, 1));
    }

    @Test
    void leavesUnknownEffectiveDatesNullInsteadOfGuessing() {
        LegalStructureParser.ParsedDocument parsed = parser.parse(
                SAMPLE,
                LocalDate.of(2025, 1, 1),
                null,
                false,
                Map.of());

        assertThat(parsed.units()).allSatisfy(unit -> {
            assertThat(unit.effectiveFrom()).isNull();
            assertThat(unit.effectiveTo()).isNull();
        });
    }

    @Test
    void chunkerSplitsLongTextWithoutChangingTheLegalUnit() {
        String header = "Luật thử nghiệm (99/2026/TEST)\nĐiều 1";
        String text = ("Nội dung pháp lý rất dài cần chia nhỏ. ".repeat(120)).strip();

        List<String> chunks = RetrievalChunker.chunk(header, text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).startsWith(header));
        assertThat(RetrievalChunker.chunk(header, text)).isEqualTo(chunks);
    }

    private static LegalStructureParser.ParsedUnit unit(LegalStructureParser.ParsedDocument parsed, String code) {
        return parsed.units().stream()
                .filter(unit -> unit.code().equals(code))
                .findFirst()
                .orElseThrow();
    }
}
