package com.bdic;

import com.bdic.model.DocumentIdGenerator;
import junit.framework.TestCase;

import java.util.HashSet;
import java.util.Set;

/**
 * Document ID generator tests.
 */
public class DocumentIdGeneratorTest extends TestCase {

    /**
     * Verifies that generated IDs have the correct format and do not repeat within the sample range.
     */
    public void testGeneratedDocumentIdFormatAndUniqueness() {
        Set<String> generatedIds = new HashSet<>();

        // Generate 1000 samples and check the doc- prefix plus the 32-character lowercase hexadecimal random part.
        for (int i = 0; i < 1000; i++) {
            String docId = DocumentIdGenerator.generate();

            assertTrue(docId.matches("doc-[0-9a-f]{32}"));
            assertTrue(generatedIds.add(docId));
        }
    }
}
