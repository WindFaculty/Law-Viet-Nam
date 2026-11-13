package vn.edu.hsf302.lawrag.ingestion.service;

import org.springframework.stereotype.Component;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalHierarchyLevel;
import vn.edu.hsf302.lawrag.legaldata.service.LegalText;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic parser for Vietnamese legal structure (Dieu -> Khoan -> Diem).
 * Raw text is sliced directly from the snapshot so citation display remains a
 * verbatim substring; search text is a separate whitespace-normalized copy.
 *
 * <p>Unknown effective dates are left null. An override is applied only when
 * the manifest has explicitly verified it (for example khoan 3 Dieu 10 taking
 * effect on 2026-01-01).
 */
@Component
public class LegalStructureParser {

    public static final String PARSER_VERSION = "legal-structure-parser-v1";

    private static final Pattern CHAPTER = Pattern.compile(
            "^\\s*Chương\\s+([IVXLC\\d]+)\\s*[.:]?\\s*(.*)$");
    private static final Pattern ARTICLE = Pattern.compile(
            "^\\s*Điều\\s+(\\d+)\\s*[.:]\\s*(.*)$");
    private static final Pattern CLAUSE = Pattern.compile(
            "^\\s*(\\d+)\\.\\s*(.*)$");
    private static final Pattern POINT = Pattern.compile(
            "^\\s*([a-zđ])\\)\\s*(.*)$");

    public ParsedDocument parse(String content,
                                LocalDate documentEffectiveFrom,
                                LocalDate documentEffectiveTo,
                                boolean effectiveDatesVerified,
                                Map<String, EffectiveDateOverride> overrides) {
        if (content == null || content.isBlank()) {
            return new ParsedDocument(List.of());
        }
        Map<String, EffectiveDateOverride> safeOverrides = overrides == null ? Map.of() : overrides;
        String[] lines = content.split("\\R", -1);
        List<MutableUnit> units = new ArrayList<>();

        MutableUnit article = null;
        MutableUnit clause = null;
        MutableUnit point = null;
        String chapterLabel = null;
        String chapterPrefix = null;
        boolean pendingChapterTitle = false;

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];

            Matcher chapterMatcher = CHAPTER.matcher(line);
            if (chapterMatcher.matches()) {
                close(point, index - 1);
                point = null;
                close(clause, index - 1);
                clause = null;
                close(article, index - 1);
                article = null;

                chapterPrefix = "Chương " + chapterMatcher.group(1);
                String heading = chapterMatcher.group(2).strip();
                chapterLabel = heading.isEmpty() ? chapterPrefix : chapterPrefix + ". " + heading;
                pendingChapterTitle = heading.isEmpty();
                continue;
            }

            Matcher articleMatcher = ARTICLE.matcher(line);
            if (articleMatcher.matches()) {
                close(point, index - 1);
                point = null;
                close(clause, index - 1);
                clause = null;
                close(article, index - 1);

                String number = articleMatcher.group(1);
                article = new MutableUnit(
                        LegalHierarchyLevel.DIEU,
                        "dieu-" + number,
                        number,
                        articleMatcher.group(2).strip(),
                        null,
                        "Điều " + number,
                        chapterLabel,
                        index);
                units.add(article);
                continue;
            }

            if (article != null) {
                Matcher clauseMatcher = CLAUSE.matcher(line);
                if (clauseMatcher.matches()) {
                    close(point, index - 1);
                    point = null;
                    close(clause, index - 1);

                    String number = clauseMatcher.group(1);
                    clause = new MutableUnit(
                            LegalHierarchyLevel.KHOAN,
                            article.code + "-khoan-" + number,
                            number,
                            null,
                            article.code,
                            article.locator + " khoản " + number,
                            article.scope,
                            index);
                    units.add(clause);
                    continue;
                }
            }

            if (clause != null) {
                Matcher pointMatcher = POINT.matcher(line);
                if (pointMatcher.matches()) {
                    close(point, index - 1);

                    String letter = pointMatcher.group(1);
                    point = new MutableUnit(
                            LegalHierarchyLevel.DIEM,
                            clause.code + "-diem-" + letter,
                            letter,
                            null,
                            clause.code,
                            clause.locator + " điểm " + letter,
                            clause.scope,
                            index);
                    units.add(point);
                    continue;
                }
            }

            if (pendingChapterTitle && !line.isBlank() && article == null && clause == null) {
                chapterLabel = chapterPrefix + ". " + line.strip();
                pendingChapterTitle = false;
            }
        }

        int lastLine = lines.length - 1;
        close(point, lastLine);
        close(clause, lastLine);
        close(article, lastLine);

        Set<String> codes = new HashSet<>();
        Map<String, LocalDate[]> effectiveRanges = new HashMap<>();
        List<ParsedUnit> parsed = new ArrayList<>(units.size());
        for (MutableUnit unit : units) {
            if (!codes.add(unit.code)) {
                throw new IllegalArgumentException("duplicate legal unit code: " + unit.code);
            }

            LocalDate effectiveFrom = effectiveDatesVerified ? documentEffectiveFrom : null;
            LocalDate effectiveTo = effectiveDatesVerified ? documentEffectiveTo : null;
            LocalDate[] inherited = unit.parentCode == null ? null : effectiveRanges.get(unit.parentCode);
            if (inherited != null) {
                effectiveFrom = inherited[0];
                effectiveTo = inherited[1];
            }
            EffectiveDateOverride override = safeOverrides.get(unit.code);
            if (effectiveDatesVerified && override != null) {
                if (override.effectiveFrom() != null) {
                    effectiveFrom = override.effectiveFrom();
                }
                if (override.effectiveTo() != null) {
                    effectiveTo = override.effectiveTo();
                }
            }
            effectiveRanges.put(unit.code, new LocalDate[]{effectiveFrom, effectiveTo});

            String rawText = joinLines(lines, unit.startLine, unit.endLine).strip();
            parsed.add(new ParsedUnit(
                    unit.level,
                    unit.code,
                    unit.number,
                    unit.title,
                    rawText,
                    LegalText.normalizeWhitespace(rawText),
                    unit.locator,
                    unit.scope,
                    unit.parentCode,
                    effectiveFrom,
                    effectiveTo));
        }
        return new ParsedDocument(List.copyOf(parsed));
    }

    private static void close(MutableUnit unit, int endLine) {
        if (unit != null && unit.endLine < 0) {
            unit.endLine = Math.max(unit.startLine, endLine);
        }
    }

    private static String joinLines(String[] lines, int startLine, int endLine) {
        StringBuilder builder = new StringBuilder();
        for (int index = startLine; index <= endLine && index < lines.length; index++) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(lines[index]);
        }
        return builder.toString();
    }

    public record EffectiveDateOverride(LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    public record ParsedUnit(
            LegalHierarchyLevel level,
            String code,
            String number,
            String title,
            String rawText,
            String searchText,
            String locator,
            String scope,
            String parentCode,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    public record ParsedDocument(List<ParsedUnit> units) {

        public ParsedDocument {
            units = List.copyOf(units);
        }

        public long articleCount() {
            return units.stream().filter(unit -> unit.level() == LegalHierarchyLevel.DIEU).count();
        }
    }

    private static final class MutableUnit {
        private final LegalHierarchyLevel level;
        private final String code;
        private final String number;
        private final String title;
        private final String parentCode;
        private final String locator;
        private final String scope;
        private final int startLine;
        private int endLine = -1;

        private MutableUnit(LegalHierarchyLevel level, String code, String number, String title,
                            String parentCode, String locator, String scope, int startLine) {
            this.level = level;
            this.code = code;
            this.number = number;
            this.title = title;
            this.parentCode = parentCode;
            this.locator = locator;
            this.scope = scope;
            this.startLine = startLine;
        }
    }
}
