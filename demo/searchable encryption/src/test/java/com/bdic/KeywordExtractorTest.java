package com.bdic;

import com.bdic.text.KeywordExtractor;
import junit.framework.TestCase;

import java.util.Arrays;
import java.util.List;

/**
 * Keyword extractor tests.
 */
public class KeywordExtractorTest extends TestCase {

    /**
     * Verifies that English natural text is extracted by word while preserving original order.
     */
    public void testExtractWordsFromEnglishText() {
        List<String> keywords = KeywordExtractor.extractWords("searchable encryption protects data privacy");

        assertEquals(Arrays.asList("searchable", "encryption", "protects", "data", "privacy"), keywords);
    }

    /**
     * Verifies that comma-separated keywords are lowercased, trimmed, deduplicated, and one-character terms are filtered.
     */
    public void testCommaSeparatedKeywordsAreNormalizedAndDeduplicated() {
        List<String> keywords = KeywordExtractor.extractCommaSeparated(" Alpha, beta , ALPHA,, a ");

        assertEquals(Arrays.asList("alpha", "beta"), keywords);
    }

    /**
     * Verifies that CJK text additionally generates adjacent two-character fragments for partial-term search.
     */
    public void testExtractWordsAddsCjkBigrams() {
        List<String> keywords = KeywordExtractor.extractWords("\u53ef\u641c\u7d22\u52a0\u5bc6");

        assertTrue(keywords.contains("\u53ef\u641c\u7d22\u52a0\u5bc6"));
        assertTrue(keywords.contains("\u641c\u7d22"));
        assertTrue(keywords.contains("\u52a0\u5bc6"));
    }

    /**
     * Verifies that full keyword values can be extracted from a JSON Searchable_Keywords array.
     */
    public void testExtractJsonKeywordFieldsFromArrayValues() {
        String json = "[{\"Searchable_Keywords\":[\"PROTOCOL:udp\",\"SERVICE:-\",\"STATE:INT\"]}]";

        List<String> keywords = KeywordExtractor.extractJsonKeywordFields(json);

        assertTrue(keywords.contains("protocol:udp"));
        assertTrue(keywords.contains("service:-"));
        assertTrue(keywords.contains("state:int"));
    }

    /**
     * Verifies that a JSON keyword string supports comma separation and automatic normalization.
     */
    public void testExtractJsonKeywordFieldsFromStringValue() {
        String json = "{\"keyword\":\" Alert , Malware,alert \"}";

        List<String> keywords = KeywordExtractor.extractJsonKeywordFields(json);

        assertEquals(Arrays.asList("alert", "malware"), keywords);
    }
}
