package vn.edu.hsf302.lawrag.retrieval.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Vietnamese query normalization shared by the analyzer, lexical SQL and the
 * deterministic grader. Diacritics are preserved for display and PostgreSQL
 * full-text search; only matching/search-key comparisons strip them.
 */
public final class VietnameseText {

    public static final String SQL_ACCENTED_LOWER =
            "áàảãạăắằẳẵặâấầẩẫậéèẻẽẹêếềểễệíìỉĩị"
                    + "óòỏõọôốồổỗộơớờởỡợúùủũụưứừửữựýỳỷỹỵđ";
    public static final String SQL_BASE_LOWER =
            "aaaaaaaaaaaaaaaaa"   // a + ă + â
                    + "eeeeeeeeeee"         // e + ê
                    + "iiiii"
                    + "ooooooooooooooooo"   // o + ô + ơ
                    + "uuuuuuuuuuu"         // u + ư
                    + "yyyyy"
                    + "d";

    private static final String TS_NO_MATCH = "zzzznomatch";
    private static final Set<String> STOPWORDS = Set.of(
            "nguoi", "dieu", "khoan", "diem", "luat", "quy", "dinh", "cua", "va", "co",
            "phai", "khong", "duoc", "khi", "tham", "gia", "giao", "thong", "duong", "bo",
            "cho", "tren", "theo", "thi", "la", "mot", "cac", "nhung", "toi", "ban", "hoi",
            "gi", "nao", "bao", "nhieu", "the", "nhu", "ra", "sao", "nay", "do", "vao",
            "tu", "den", "ve", "hay", "xe", "ngay", "thang", "nam", "to", "cung", "can");

    private static final Map<String, String> SUBJECT_DISPLAY = Map.of(
            "o_to", "xe ô tô",
            "xe_mo_to", "xe mô tô, xe gắn máy",
            "xe_may_chuyen_dung", "xe máy chuyên dùng",
            "xe_dap_may", "xe đạp máy",
            "xe_dap", "xe đạp",
            "nguoi_di_bo", "người đi bộ",
            "nguoi_tham_gia_giao_thong", "người tham gia giao thông");

    private static final Map<String, String> TOPIC_DISPLAY = Map.ofEntries(
            Map.entry("mu_bao_hiem", "mũ bảo hiểm"),
            Map.entry("day_dai_an_toan", "dây đai an toàn"),
            Map.entry("ghe_tre_em", "ghế ngồi trẻ em"),
            Map.entry("hieu_luc", "hiệu lực"),
            Map.entry("cho_nguoi", "chở người"),
            Map.entry("nong_do_con", "nồng độ cồn"),
            Map.entry("lan_duong", "làn đường"),
            Map.entry("muc_phat", "mức phạt"),
            Map.entry("den_tin_hieu", "đèn tín hiệu giao thông"),
            Map.entry("chay_qua_toc_do", "chạy quá tốc độ quy định"),
            Map.entry("di_nguoc_chieu", "đi ngược chiều"),
            Map.entry("duong_cao_toc", "đường cao tốc"),
            Map.entry("dung_dien_thoai", "sử dụng điện thoại"),
            Map.entry("khong_co_gplx", "không có giấy phép lái xe"));

    private static final List<SubjectTerm> SUBJECT_TERMS = List.of(
            new SubjectTerm("nguoi tham gia giao thong", "nguoi_tham_gia_giao_thong"),
            new SubjectTerm("nguoi di bo", "nguoi_di_bo"),
            new SubjectTerm("xe dap may", "xe_dap_may"),
            new SubjectTerm("xe dap", "xe_dap"),
            new SubjectTerm("xe may chuyen dung", "xe_may_chuyen_dung"),
            new SubjectTerm("xe mo to", "xe_mo_to"),
            new SubjectTerm("xe gan may", "xe_mo_to"),
            new SubjectTerm("xe may", "xe_mo_to"),
            new SubjectTerm("mo to", "xe_mo_to"),
            new SubjectTerm("xe o to", "o_to"),
            new SubjectTerm("o to", "o_to"),
            new SubjectTerm("xe hoi", "o_to"));

    private VietnameseText() {
    }

    public static String stripDiacritics(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.replace('đ', 'd').replace('Đ', 'D');
    }

    /** Lowercase, diacritic-free, punctuation-free text used for phrase matching. */
    public static String normalizeForMatching(String value) {
        if (value == null) {
            return "";
        }
        return stripDiacritics(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    public static String canonicalDocNumber(String value) {
        if (value == null) {
            return "";
        }
        return value.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** ASCII key used to compare a user-typed document number with the stored one. */
    public static String docNumberKey(String value) {
        return stripDiacritics(canonicalDocNumber(value)).toUpperCase(Locale.ROOT);
    }

    public static boolean containsPhrase(String normalizedText, String normalizedPhrase) {
        if (normalizedText == null || normalizedText.isBlank() || normalizedPhrase == null) {
            return false;
        }
        return (" " + normalizedText + " ").contains(" " + normalizedPhrase + " ");
    }

    /** Distinctive tokens for lexical coverage, without generic legal words or dates. */
    public static List<String> keywords(String question, boolean stripDiacritics) {
        String normalized = stripDiacritics ? stripDiacritics(question) : question;
        normalized = normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
        if (normalized.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() < 2 || STOPWORDS.contains(token) || token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            tokens.add(token);
        }
        return List.copyOf(tokens);
    }

    /** OR-connected tsquery so paraphrases do not have to match every token. */
    public static String tsQuery(String question, boolean stripDiacritics) {
        List<String> tokens = keywords(question, stripDiacritics);
        return tokens.isEmpty() ? TS_NO_MATCH : String.join(" | ", tokens);
    }

    public static String phraseTsQuery(List<String> subjects, List<String> topics, boolean stripDiacritics) {
        List<String> phrases = new ArrayList<>();
        if (topics != null) {
            for (String topic : topics) {
                if ("muc_phat".equals(topic)) {
                    continue;
                }
                String phrase = switch (topic) {
                    case "den_tin_hieu" -> "đèn tín hiệu";
                    case "ghe_tre_em" -> "hàng ghế";
                    default -> TOPIC_DISPLAY.get(topic);
                };
                if (phrase != null && !phrase.isBlank()) {
                    phrases.add(toPhraseClause(phrase, stripDiacritics));
                }
            }
        }
        if (phrases.isEmpty() && subjects != null) {
            for (String subject : subjects) {
                if ("nguoi_tham_gia_giao_thong".equals(subject)) {
                    continue;
                }
                String display = SUBJECT_DISPLAY.get(subject);
                if (display != null && !display.isBlank()) {
                    for (String variant : display.split(",")) {
                        phrases.add(toPhraseClause(variant, stripDiacritics));
                    }
                }
            }
        }
        List<String> nonEmpty = phrases.stream().filter(clause -> !clause.isBlank()).toList();
        return nonEmpty.isEmpty() ? TS_NO_MATCH : String.join(" | ", nonEmpty);
    }

    private static String toPhraseClause(String phrase, boolean stripDiacritics) {
        String source = stripDiacritics ? stripDiacritics(phrase) : phrase;
        String cleaned = source.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\d]+", " ").strip();
        if (cleaned.isBlank()) {
            return "";
        }
        String[] parts = cleaned.split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        return "(" + String.join(" <-> ", parts) + ")";
    }

    public static List<String> subjects(String text) {
        String normalized = normalizeForMatching(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        boolean[] consumed = new boolean[normalized.length()];
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (SubjectTerm term : SUBJECT_TERMS) {
            int from = 0;
            while (from <= normalized.length()) {
                int index = normalized.indexOf(term.phrase(), from);
                if (index < 0) {
                    break;
                }
                boolean overlaps = false;
                for (int position = index; position < index + term.phrase().length(); position++) {
                    if (consumed[position]) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps && hasWordBoundaries(normalized, index, term.phrase().length())) {
                    result.add(term.canonical());
                    for (int position = index; position < index + term.phrase().length(); position++) {
                        consumed[position] = true;
                    }
                }
                from = index + 1;
            }
        }
        return List.copyOf(result);
    }

    private static boolean hasWordBoundaries(String text, int index, int length) {
        int end = index + length;
        boolean left = index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
        boolean right = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
        return left && right;
    }

    public static List<String> topics(String text) {
        String normalized = normalizeForMatching(text);
        List<String> topics = new ArrayList<>();
        if (containsPhrase(normalized, "mu bao hiem")) {
            topics.add("mu_bao_hiem");
        }
        if (containsPhrase(normalized, "day dai an toan") || containsPhrase(normalized, "that day dai")) {
            topics.add("day_dai_an_toan");
        }
        if (containsPhrase(normalized, "hang ghe")
                || containsPhrase(normalized, "ghe truoc")
                || containsPhrase(normalized, "ghe sau")
                || containsPhrase(normalized, "1 35")
                || containsPhrase(normalized, "chieu cao")) {
            topics.add("ghe_tre_em");
        }
        if (containsPhrase(normalized, "hieu luc")) {
            topics.add("hieu_luc");
        }
        if (containsPhrase(normalized, "muc phat")
                || containsPhrase(normalized, "xu phat")
                || containsPhrase(normalized, "bi phat")
                || containsPhrase(normalized, "phat tien")
                || containsPhrase(normalized, "phat bao nhieu")
                || containsPhrase(normalized, "tien phat")
                || containsPhrase(normalized, "hinh phat")) {
            topics.add("muc_phat");
        }
        if (containsPhrase(normalized, "nong do con")
                || containsPhrase(normalized, "ruou")
                || containsPhrase(normalized, "bia")) {
            topics.add("nong_do_con");
        }
        if (containsPhrase(normalized, "lan duong")
                || containsPhrase(normalized, "phan duong")
                || containsPhrase(normalized, "di ben")
                || containsPhrase(normalized, "ben phai")
                || containsPhrase(normalized, "chieu di")
                || containsPhrase(normalized, "bao hieu duong bo")) {
            topics.add("lan_duong");
        }
        if (containsPhrase(normalized, "cho nguoi")
                || containsPhrase(normalized, "cho them")
                || containsPhrase(normalized, "cho toi da")
                || containsPhrase(normalized, "cho qua so nguoi")
                || containsPhrase(normalized, "cho ba nguoi")
                || containsPhrase(normalized, "cho theo 02")
                || containsPhrase(normalized, "cho theo tu 03")) {
            topics.add("cho_nguoi");
        }
        for (Map.Entry<String, List<String>> entry : EXTRA_TOPIC_PHRASES.entrySet()) {
            if (entry.getValue().stream().anyMatch(phrase -> containsPhrase(normalized, phrase))) {
                topics.add(entry.getKey());
            }
        }
        return List.copyOf(topics);
    }

    private static final Map<String, List<String>> EXTRA_TOPIC_PHRASES = Map.ofEntries(
            Map.entry("den_tin_hieu", List.of("den tin hieu", "vuot den do", "vuot den", "hieu lenh cua den")),
            Map.entry("chay_qua_toc_do", List.of("qua toc do", "chay qua toc do")),
            Map.entry("di_nguoc_chieu", List.of("di nguoc chieu")),
            Map.entry("duong_cao_toc", List.of("duong cao toc")),
            Map.entry("dung_dien_thoai", List.of("su dung dien thoai", "dung dien thoai", "nghe dien thoai")),
            Map.entry("khong_co_gplx", List.of(
                    "khong co giay phep lai xe", "khong mang giay phep lai xe",
                    "khong co bang lai", "chua co bang lai")));

    /**
     * Rule-based web query used when Layer 1 never calls Gemini. No extra model
     * round is spent just to invent keywords.
     */
    public static String searchQuery(String question, List<String> subjects, List<String> topics,
                                     List<String> documentNumbers, List<String> articleCodes) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        parts.addAll(documentNumbers);
        parts.addAll(articleCodes.stream().map(VietnameseText::articleCodeToDisplay).toList());
        subjects.stream().map(SUBJECT_DISPLAY::get).filter(java.util.Objects::nonNull).forEach(parts::add);
        topics.stream().map(TOPIC_DISPLAY::get).filter(java.util.Objects::nonNull).forEach(parts::add);
        if (!documentNumbers.isEmpty() && subjects.isEmpty() && topics.isEmpty() && articleCodes.isEmpty()) {
            parts.add("phạm vi điều chỉnh");
        }
        if (parts.isEmpty()) {
            String fallback = question == null ? "" : question.strip();
            return fallback.length() <= 300 ? fallback : fallback.substring(0, 300);
        }
        parts.add("quy định pháp luật giao thông đường bộ");
        String query = String.join(" ", parts);
        return query.length() <= 300 ? query : query.substring(0, 300);
    }

    public static String articleCodeToDisplay(String articleCode) {
        if (articleCode == null || articleCode.isBlank()) {
            return "";
        }
        StringBuilder display = new StringBuilder();
        for (String part : articleCode.split("-")) {
            if (part.equals("dieu")) {
                display.append("Điều ");
            } else if (part.equals("khoan")) {
                display.append("khoản ");
            } else if (part.equals("diem")) {
                display.append("điểm ");
            } else {
                display.append(part);
            }
        }
        return display.toString().strip();
    }

    private record SubjectTerm(String phrase, String canonical) {
    }
}
