package com.bdic.model;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Document ID generator.
 *
 * <p>The client uses this utility to generate user-visible document IDs for new uploads. The result has a {@code doc-}
 * prefix followed by the hexadecimal representation of 16 random bytes, making it easy to identify in the UI while minimizing collision probability.</p>
 */
public final class DocumentIdGenerator {

    /** Global secure random source used to produce unpredictable document IDs. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** Number of random bytes per document ID; 16 bytes produce 32 hexadecimal characters. */
    private static final int RANDOM_BYTE_LENGTH = 16;

    /** Utility class; the private constructor prevents external instantiation. */
    private DocumentIdGenerator() {
    }

    /**
     * Generates a new random document ID.
     *
     * @return an ID shaped like {@code doc-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx}.
     */
    public static String generate() {
        // Fill a fixed-length random byte array first, then encode it as a lowercase hexadecimal string.
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return "doc-" + HexFormat.of().formatHex(bytes);
    }
}
