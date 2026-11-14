package vn.edu.hsf302.lawrag.retrieval.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.edu.hsf302.lawrag.retrieval.dto.QueryAnalysis;

/**
 * Deterministic pre-check before retrieval. It extracts document numbers,
 * Điều/Khoản/Điểm locators, subject/topic signals and the applicable date, and
 * stops on questions whose decisive facts are missing. No model call is made
 * here: the fallback web query is rule-based.
 */
@Component
public class QueryAnalyzer {

    private static final Pattern DOCUMENT_NUMBER = Pattern.compile(
            "(?iu)\\b(\\d{1,4})\\s*/\\s*(\\d{4})\\s*/\\s*([\\p{L}][\\p{L}\\d-]*)\\b");
    private static final Pattern DIEU = Pattern.compile("(?iu)điều\\s+(\\d+)");
    private static final Pattern DIEU_ASCII = Pattern.compile("(?iu)dieu\\s+(\\d+)");
    private static final Pattern KHOAN = Pattern.compile("(?iu)khoản\\s+(\\d+)");
    private static final Pattern KHOAN_ASCII = Pattern.compile("(?iu)khoan\\s+(\\d+)");
    private static final Pattern DIEM = Pattern.compile("(?iu)điểm\\s+([a-zđ])");
    private static final Pattern DIEM_ASCII = Pattern.compile("(?iu)diem\\s+([a-z])");

    private static final Pattern FULL_DATE = Pattern.compile(
            "(?:ngay\\s+)?(\\d{1,2})\\s*[/.\\-]\\s*(\\d{1,2})\\s*[/.\\-]\\s*(\\d{4})");
    private static final Pattern LONG_DATE = Pattern.compile(
            "ngay\\s+(\\d{1,2})\\s+thang\\s+(\\d{1,2})\\s+nam\\s+(\\d{4})");
    private static final Pattern MONTH_YEAR = Pattern.compile("thang\\s+(\\d{1,2})\\s+nam\\s+(\\d{4})");
    private static final Pattern YEAR = Pattern.compile("\\bnam\\s+(\\d{4})\\b");

    private static final Pattern AMBIGUOUS_DATE = Pattern.compile(
            "\\b(nam ngoai|nam kia|nam toi|truoc khi luat moi|sau khi luat moi"
                    + "|truoc day|thoi gian truoc|dao truoc|moi day|sap toi)\\b");

    /**
     * MVP scope is road traffic law only. These phrases route to a polite
     * refusal before retrieval so the system never grows the corpus or invents
     * citations for marriage, tax, land, labour or criminal questions.
     */
    private static final List<String> OUT_OF_SCOPE_TERMS = List.of(
            "ly hon", "ket hon", "hon nhan", "khai sinh", "chia tai san",
            "thue thu nhap", "thue gia tri gia tang", "thue mon bai", "thue truoc ba",
            "thue tieu thu", "thue",
            "dat dai", "so do", "quyen su dung dat", "chuyen nhuong dat",
            "tranh chap dat", "cap so do",
            "lao dong", "hop dong lao dong", "thu viec", "tro cap that nghiep",
            "bao hiem xa hoi", "nghi viec",
            "hinh su", "toi pham", "truy to", "tang tru ma tuy", "phat tu",
            "danh nguoi gay thuong tich", "pham toi", "chua thanh nien pham toi",
            "thua ke", "tranh chap dan su", "bo luat dan su");

    private static final int MAX_QUESTION_LENGTH = 4_000;

    private final Clock clock;

    public QueryAnalyzer(Clock clock) {
        this.clock = clock;
    }

    public QueryAnalysis analyze(String question) {
        return analyze(question, LocalDate.now(clock));
    }

    public QueryAnalysis analyze(String question, LocalDate defaultApplicableDate) {
        String normalized = normalizeQuestion(question);
        String strippedLower = VietnameseText.stripDiacritics(normalized).toLowerCase(Locale.ROOT);
        String matching = VietnameseText.normalizeForMatching(normalized);

        List<String> documentNumbers = documentNumbers(normalized, strippedLower);
        Locator locator = locator(normalized, strippedLower);
        List<String> articleCodes = locator.articleCodes();
        List<String> subjects = VietnameseText.subjects(normalized);
        List<String> topics = VietnameseText.topics(normalized);

        ParsedDate parsedDate = parseDate(strippedLower);
        boolean ambiguousDate = !parsedDate.explicit()
                && AMBIGUOUS_DATE.matcher(strippedLower).find();
        LocalDate applicableDate = parsedDate.date() != null
                ? parsedDate.date()
                : (defaultApplicableDate != null ? defaultApplicableDate : LocalDate.now(clock));
        boolean outOfScope = isOutOfScope(strippedLower);

        String clarification = outOfScope ? null
                : clarification(parsedDate, ambiguousDate, subjects, topics,
                        documentNumbers, articleCodes);

        String searchQuery = VietnameseText.searchQuery(
                normalized, subjects, topics, documentNumbers, articleCodes);

        return new QueryAnalysis(
                normalized,
                normalized,
                searchQuery,
                documentNumbers,
                articleCodes,
                locator.articleNumber(),
                locator.clauseNumber(),
                locator.pointLetter(),
                subjects,
                topics,
                applicableDate,
                parsedDate.explicit(),
                ambiguousDate || parsedDate.invalid(),
                outOfScope,
                clarification);
    }

    private static boolean isOutOfScope(String strippedLower) {
        String padded = " " + strippedLower + " ";
        for (String term : OUT_OF_SCOPE_TERMS) {
            if (padded.contains(" " + term + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        String normalized = question.replaceAll("\\p{Cntrl}", " ").strip();
        if (normalized.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("question is too long");
        }
        return normalized.replaceAll("\\s+", " ");
    }

    private record DocumentAlias(Pattern pattern, String numberPrefix, String canonicalDocNumber) {
    }

    private static final List<DocumentAlias> DOCUMENT_ALIASES = List.of(
            new DocumentAlias(
                    Pattern.compile("\\b(?:nghi\\s+dinh(?:\\s+so)?|nd)\\s*-?\\s*168\\b(?!\\s*/\\s*\\d)"),
                    "168/", "168/2024/NĐ-CP"),
            new DocumentAlias(
                    Pattern.compile("\\b(?:nghi\\s+dinh(?:\\s+so)?|nd)\\s*-?\\s*238\\b(?!\\s*/\\s*\\d)"),
                    "238/", "238/2026/NĐ-CP"),
            new DocumentAlias(
                    Pattern.compile("\\bluat(?:\\s+so)?\\s+36\\b(?!\\s*/\\s*\\d)"),
                    "36/", "36/2024/QH15"),
            new DocumentAlias(
                    Pattern.compile("\\bluat(?:\\s+so)?\\s+23\\b(?!\\s*/\\s*\\d)"),
                    "23/", "23/2008/QH12")
    );

    private static List<String> documentNumbers(String normalized, String strippedLower) {
        LinkedHashSet<String> numbers = new LinkedHashSet<>();
        Matcher matcher = DOCUMENT_NUMBER.matcher(normalized);
        while (matcher.find()) {
            numbers.add(VietnameseText.canonicalDocNumber(matcher.group()));
        }
        for (DocumentAlias alias : DOCUMENT_ALIASES) {
            boolean alreadySpecified = numbers.stream()
                    .anyMatch(existing -> existing.startsWith(alias.numberPrefix()));
            if (!alreadySpecified && alias.pattern().matcher(strippedLower).find()) {
                numbers.add(alias.canonicalDocNumber());
            }
        }
        return List.copyOf(numbers);
    }

    private static Locator locator(String normalized, String strippedLower) {
        String articleNumber = firstGroup(DIEU, normalized).orElseGet(
                () -> firstGroup(DIEU_ASCII, strippedLower).orElse(null));
        if (articleNumber == null) {
            return Locator.empty();
        }
        String clauseNumber = firstGroup(KHOAN, normalized).orElseGet(
                () -> firstGroup(KHOAN_ASCII, strippedLower).orElse(null));
        String pointLetter = clauseNumber == null ? null
                : firstGroup(DIEM, normalized).orElseGet(
                        () -> firstGroup(DIEM_ASCII, strippedLower).orElse(null));

        List<String> codes = new ArrayList<>();
        String code = "dieu-" + articleNumber;
        if (clauseNumber != null) {
            code += "-khoan-" + clauseNumber;
            if (pointLetter != null) {
                code += "-diem-" + pointLetter.toLowerCase(Locale.ROOT);
            }
        }
        codes.add(code);
        return new Locator(articleNumber, clauseNumber, pointLetter, List.copyOf(codes));
    }

    private static Optional<String> firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static ParsedDate parseDate(String strippedLower) {
        List<int[]> occupiedSpans = new ArrayList<>();
        List<MatchedDate> matches = new ArrayList<>();
        collectDateMatches(LONG_DATE.matcher(strippedLower), 3, occupiedSpans, matches,
                matcher -> toDate(matcher.group(1), matcher.group(2), matcher.group(3)));
        collectDateMatches(FULL_DATE.matcher(strippedLower), 3, occupiedSpans, matches,
                matcher -> toDate(matcher.group(1), matcher.group(2), matcher.group(3)));
        collectDateMatches(MONTH_YEAR.matcher(strippedLower), 2, occupiedSpans, matches,
                matcher -> toDate("1", matcher.group(1), matcher.group(2)));
        collectDateMatches(YEAR.matcher(strippedLower), 1, occupiedSpans, matches,
                matcher -> toDate("1", "1", matcher.group(1)));
        if (matches.isEmpty()) {
            return ParsedDate.none();
        }
        int lastInvalidStart = matches.stream()
                .filter(match -> match.date().invalid())
                .mapToInt(MatchedDate::start)
                .max()
                .orElse(-1);
        Optional<MatchedDate> bestValid = matches.stream()
                .filter(match -> !match.date().invalid() && match.start() > lastInvalidStart)
                .max(Comparator.comparingInt(MatchedDate::specificity)
                        .thenComparingInt(MatchedDate::start));
        return bestValid.map(MatchedDate::date).orElseGet(ParsedDate::invalidDate);
    }

    private static void collectDateMatches(Matcher matcher,
                                           int specificity,
                                           List<int[]> occupiedSpans,
                                           List<MatchedDate> matches,
                                           Function<Matcher, ParsedDate> extractor) {
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            boolean overlaps = false;
            for (int[] span : occupiedSpans) {
                if (start < span[1] && end > span[0]) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                occupiedSpans.add(new int[]{start, end});
                matches.add(new MatchedDate(start, specificity, extractor.apply(matcher)));
            }
        }
    }

    private record MatchedDate(int start, int specificity, ParsedDate date) {
    }

    private static ParsedDate toDate(String day, String month, String year) {
        try {
            return ParsedDate.explicit(LocalDate.of(
                    Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day)));
        } catch (NumberFormatException | DateTimeException exception) {
            return ParsedDate.invalidDate();
        }
    }

    private static String clarification(ParsedDate parsedDate, boolean ambiguousDate,
                                        List<String> subjects, List<String> topics,
                                        List<String> documentNumbers, List<String> articleCodes) {
        if (parsedDate.invalid()) {
            return "Ngày áp dụng bạn nêu không hợp lệ. Bạn cho biết lại ngày/tháng/năm cụ thể không?";
        }
        if (ambiguousDate) {
            return "Bạn cho biết mốc thời gian cụ thể (ngày/tháng/năm) để xác định quy định áp dụng không?";
        }
        boolean hasExplicitSource = !documentNumbers.isEmpty() || !articleCodes.isEmpty();
        if (hasExplicitSource) {
            return null;
        }
        if (topics.contains("muc_phat") && subjects.isEmpty()) {
            return "Bạn cho biết loại phương tiện và hành vi vi phạm cụ thể để xác định quy định áp dụng không?";
        }
        if (topics.contains("cho_nguoi") && subjects.isEmpty()) {
            return "Bạn cho biết loại xe, số người và độ tuổi người được chở để xác định quy định áp dụng không?";
        }
        if (topics.contains("mu_bao_hiem") && subjects.isEmpty()) {
            return "Bạn đang hỏi cho loại phương tiện nào (xe đạp, xe đạp máy, xe mô tô/xe gắn máy, xe ô tô)?";
        }
        return null;
    }

    private record Locator(String articleNumber, String clauseNumber, String pointLetter,
                           List<String> articleCodes) {

        static Locator empty() {
            return new Locator(null, null, null, List.of());
        }
    }

    private record ParsedDate(LocalDate date, boolean explicit, boolean invalid) {

        static ParsedDate none() {
            return new ParsedDate(null, false, false);
        }

        static ParsedDate explicit(LocalDate date) {
            return new ParsedDate(date, true, false);
        }

        static ParsedDate invalidDate() {
            return new ParsedDate(null, true, true);
        }
    }
}
