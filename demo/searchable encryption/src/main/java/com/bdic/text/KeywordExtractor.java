package com.bdic.text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword extraction utility.
 *
 * <p>Splits user descriptions, document bodies, and file names into searchable tokens. Results preserve insertion order and are deduplicated,
 * reducing index size and keeping test results stable.</p>
 */
public final class KeywordExtractor {

    /** Matches consecutive letters or digits, including Unicode characters such as Chinese, Japanese, and Korean. */
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    /** Matches array values in JSON fields such as keyword/keywords/Searchable_Keywords. */
    private static final Pattern JSON_KEYWORD_ARRAY_PATTERN = Pattern.compile(
            "\"(?i:(?:searchable_)?keywords?|keyword)\"\\s*:\\s*\\[(.*?)]",
            Pattern.DOTALL
    );
    /** Matches string values in JSON fields such as keyword/keywords/Searchable_Keywords. */
    private static final Pattern JSON_KEYWORD_STRING_PATTERN = Pattern.compile(
            "\"(?i:(?:searchable_)?keywords?|keyword)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
    );
    /** Matches string elements in JSON arrays. */
    private static final Pattern JSON_STRING_ITEM_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    /** Filters tokens that are too short to avoid many meaningless one-character terms entering the index. */
    private static final int MIN_TOKEN_LENGTH = 2;

    /** Utility class; instantiation is not needed. */
    private KeywordExtractor() {
    }

    /**
     * Extracts keywords from comma-separated input, commonly used when users manually enter keywords.
     */
    public static List<String> extractCommaSeparated(String input) {
        Set<String> keywords = new LinkedHashSet<>();
        if (input == null || input.isBlank()) {
            return new ArrayList<>();
        }

        // Clean, lowercase, and deduplicate segment by segment; empty and too-short items are filtered by addKeyword.
        for (String rawKeyword : input.split(",")) {
            addKeyword(keywords, rawKeyword);
        }
        return new ArrayList<>(keywords);
    }

    /**
     * Extracts terms from natural text and additionally generates adjacent two-character fragments for CJK text.
     */
    public static List<String> extractWords(String text) {
        Set<String> keywords = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            // Whitespace-delimited languages such as English use regex matches directly; CJK gets additional bigrams.
            String word = normalize(matcher.group());
            if (!addKeyword(keywords, word) || !containsCjk(word)) {
                continue;
            }
            addCjkBigrams(keywords, word);
        }
        return new ArrayList<>(keywords);
    }

    /**
     * Extracts keywords from file names, including base names, extensions, and split fragments.
     */
    public static List<String> extractFileNameKeywords(String fileName) {
        Set<String> keywords = new LinkedHashSet<>();
        if (fileName == null || fileName.isBlank()) {
            return new ArrayList<>();
        }

        String normalizedName = fileName.trim();
        int extensionSeparator = normalizedName.lastIndexOf('.');
        String baseName = extensionSeparator > 0 ? normalizedName.substring(0, extensionSeparator) : normalizedName;
        String extension = extensionSeparator > 0 && extensionSeparator < normalizedName.length() - 1
                ? normalizedName.substring(extensionSeparator + 1)
                : "";

        // Split the base name by common separators first, then add the complete base name and extension.
        keywords.addAll(extractWords(baseName.replaceAll("[_\\-\\.\\(\\)\\[\\]\\{\\}]+", " ")));
        addKeyword(keywords, baseName);
        addKeyword(keywords, extension);
        return new ArrayList<>(keywords);
    }

    /**
     * Extracts keyword fields from JSON text, supporting keyword/keywords/Searchable_Keywords.
     */
    public static List<String> extractJsonKeywordFields(String jsonText) {
        Set<String> keywords = new LinkedHashSet<>();
        if (jsonText == null || jsonText.isBlank()) {
            return new ArrayList<>();
        }

        Matcher arrayMatcher = JSON_KEYWORD_ARRAY_PATTERN.matcher(jsonText);
        while (arrayMatcher.find()) {
            Matcher itemMatcher = JSON_STRING_ITEM_PATTERN.matcher(arrayMatcher.group(1));
            while (itemMatcher.find()) {
                addJsonKeywordValue(keywords, decodeJsonString(itemMatcher.group(1)));
            }
        }

        Matcher stringMatcher = JSON_KEYWORD_STRING_PATTERN.matcher(jsonText);
        while (stringMatcher.find()) {
            for (String rawKeyword : decodeJsonString(stringMatcher.group(1)).split(",")) {
                addJsonKeywordValue(keywords, rawKeyword);
            }
        }
        return new ArrayList<>(keywords);
    }

    private static void addJsonKeywordValue(Set<String> keywords, String rawKeyword) {
        addKeyword(keywords, rawKeyword);
        keywords.addAll(extractWords(rawKeyword));
    }

    /**
     * Normalizes and adds a keyword to the set; the return value indicates whether it is long enough and accepted.
     */
    private static boolean addKeyword(Set<String> keywords, String rawKeyword) {
        String keyword = normalize(rawKeyword);
        if (keyword.length() < MIN_TOKEN_LENGTH) {
            return false;
        }
        keywords.add(keyword);
        return true;
    }

    /**
     * Trims surrounding whitespace and lowercases text so upload indexes and search input use the same form.
     */
    private static String normalize(String rawKeyword) {
        if (rawKeyword == null) {
            return "";
        }
        return rawKeyword.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Handles common escape sequences in JSON strings so keywords remain readable and searchable.
     */
    private static String decodeJsonString(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            return "";
        }

        StringBuilder decoded = new StringBuilder(rawValue.length());
        for (int i = 0; i < rawValue.length(); i++) {
            char current = rawValue.charAt(i);
            if (current != '\\' || i + 1 >= rawValue.length()) {
                decoded.append(current);
                continue;
            }

            char next = rawValue.charAt(++i);
            switch (next) {
                case '"':
                case '\\':
                case '/':
                    decoded.append(next);
                    break;
                case 'b':
                    decoded.append('\b');
                    break;
                case 'f':
                    decoded.append('\f');
                    break;
                case 'n':
                    decoded.append('\n');
                    break;
                case 'r':
                    decoded.append('\r');
                    break;
                case 't':
                    decoded.append('\t');
                    break;
                case 'u':
                    if (i + 4 < rawValue.length()) {
                        String hex = rawValue.substring(i + 1, i + 5);
                        try {
                            decoded.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                            break;
                        } catch (NumberFormatException ignored) {
                            // Fall back to preserving the original text to avoid interrupting extraction with an exception.
                        }
                    }
                    decoded.append("\\u");
                    break;
                default:
                    decoded.append(next);
                    break;
            }
        }
        return decoded.toString();
    }

    /**
     * Checks whether a term contains Chinese, Japanese, or Korean characters.
     */
    private static boolean containsCjk(String word) {
        return word.codePoints().anyMatch(KeywordExtractor::isCjkCodePoint);
    }

    /**
     * Checks whether one code point belongs to the CJK range by Unicode Script.
     */
    private static boolean isCjkCodePoint(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    /**
     * Generates adjacent two-character fragments for CJK text so users can search long strings by partial terms.
     */
    private static void addCjkBigrams(Set<String> keywords, String word) {
        int[] codePoints = word.codePoints().toArray();
        if (codePoints.length <= MIN_TOKEN_LENGTH) {
            return;
        }

        // Use code points instead of chars to avoid splitting surrogate-pair characters incorrectly.
        for (int i = 0; i < codePoints.length - 1; i++) {
            String bigram = new String(codePoints, i, 2);
            addKeyword(keywords, bigram);
        }
    }
}
